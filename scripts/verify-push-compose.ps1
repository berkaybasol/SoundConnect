[CmdletBinding()]
param()

# Offline configuration verification only: no application, database or provider is started.
# All environment files and the fake ADC live in a disposable fixture; real local secrets are never read.
$ErrorActionPreference = "Stop"
$SourceRoot = Split-Path -Parent $PSScriptRoot
$FixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ("soundconnect-push-compose-" + [Guid]::NewGuid().ToString("N"))
$ProjectRoot = Join-Path $FixtureRoot "workspace/backend"
$ExternalRoot = Join-Path $FixtureRoot "external"
$EnvFile = Join-Path $ProjectRoot ".env.local"
$Action = "up"
$script:UsePushCompose = $false
$script:LocalPushSettings = @{ Enabled = $false }
$script:LocalPushEnvironmentBefore = @{}
$script:Checks = 0
$SavedEnvironment = @{}
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Assert-Check([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw "Push Compose verification failed: $Label" }
    $script:Checks++
}

function Assert-Rejected([scriptblock]$Operation, [string]$ExpectedMessage, [string]$Label) {
    $Rejected = $false
    try { & $Operation }
    catch {
        $Rejected = $_.Exception.Message -like "*$ExpectedMessage*"
    }
    Assert-Check $Rejected $Label
}

$DevSource = Get-Content -LiteralPath (Join-Path $SourceRoot "scripts/dev.ps1") -Raw
$ComposeSource = Get-Content -LiteralPath (Join-Path $SourceRoot "compose.yaml") -Raw
$OverlaySource = Get-Content -LiteralPath (Join-Path $SourceRoot "compose.push.local.yaml") -Raw
$ParseErrors = $null
$DevAst = [System.Management.Automation.Language.Parser]::ParseInput($DevSource, [ref]$null, [ref]$ParseErrors)
Assert-Check ($ParseErrors.Count -eq 0) "dev.ps1 parses"

# Load only pure configuration helpers, never dev.ps1's executable startup block.
$Helpers = @("Read-DotEnvMap", "Get-LocalSetting", "Set-LocalPushEnvironment", "Restore-LocalPushEnvironment",
    "Initialize-LocalPushConfiguration", "Get-ComposeArguments", "Invoke-Compose", "Import-DotEnv",
    "Set-NativePushEnvironment", "Invoke-LocalSchemaBootstrap")
foreach ($Name in $Helpers) {
    $Definition = $DevAst.FindAll({ param($Node) $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true) |
        Where-Object { $_.Name -eq $Name } | Select-Object -First 1
    Assert-Check ($null -ne $Definition) "configuration helper exists: $Name"
    . ([scriptblock]::Create($Definition.Extent.Text))
}

$ControlledNames = @([regex]::Matches(($ComposeSource + $OverlaySource), '\$\{([A-Za-z_][A-Za-z0-9_]*)') |
    ForEach-Object { $_.Groups[1].Value }) + @(
    "SOUNDCONNECT_PUSH_ENABLED", "SOUNDCONNECT_FCM_PROJECT_ID", "SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY",
    "SOUNDCONNECT_FCM_ADC_HOST_PATH", "SOUNDCONNECT_FCM_CREDENTIALS_PATH", "GOOGLE_APPLICATION_CREDENTIALS",
    "DOCKER_CONFIG", "COMPOSE_FILE", "COMPOSE_ENV_FILES", "COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES"
) | Sort-Object -Unique

function Write-FixtureEnvironment([hashtable]$Changes = @{}) {
    Restore-LocalPushEnvironment
    $script:UsePushCompose = $false
    $script:LocalPushSettings = @{ Enabled = $false }
    foreach ($Name in @("SOUNDCONNECT_PUSH_ENABLED", "SOUNDCONNECT_FCM_PROJECT_ID", "SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY",
        "SOUNDCONNECT_FCM_ADC_HOST_PATH", "SOUNDCONNECT_FCM_CREDENTIALS_PATH", "GOOGLE_APPLICATION_CREDENTIALS")) {
        Remove-Item -LiteralPath ("Env:" + $Name) -ErrorAction SilentlyContinue
    }
    $Values = @{
        SOUNDCONNECT_POSTGRES_PASSWORD = "fixture-postgres-not-a-secret"
        SPRING_RABBITMQ_PASSWORD = "fixture-rabbit-not-a-secret"
        SOUNDCONNECT_PUSH_ENABLED = "false"
        SOUNDCONNECT_FCM_PROJECT_ID = ""
        SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY = ""
        SOUNDCONNECT_FCM_ADC_HOST_PATH = ""
        SOUNDCONNECT_FCM_CREDENTIALS_PATH = ""
    }
    foreach ($Name in $Changes.Keys) { $Values[$Name] = $Changes[$Name] }
    $Lines = @($Values.Keys | Sort-Object | ForEach-Object { "$_=$($Values[$_])" })
    [IO.File]::WriteAllLines($EnvFile, $Lines, $Utf8)
}

function Get-FixtureModel([switch]$WithoutPush) {
    $ComposeArgs = Get-ComposeArguments -WithoutPush:$WithoutPush
    $ErrorFile = Join-Path $FixtureRoot "compose-stderr.txt"
    $Rendered = & docker compose @ComposeArgs config --format json 2>$ErrorFile
    if ($LASTEXITCODE -ne 0) { throw "Fixture Compose render failed; no application was started." }
    return ($Rendered -join "`n") | ConvertFrom-Json
}

function Assert-NoAdcMount($Model, [string]$Label) {
    $Mounts = @($Model.services.backend.volumes | Where-Object { $_.target -eq "/run/secrets/soundconnect-push-adc.json" })
    Assert-Check ($Mounts.Count -eq 0) $Label
}

try {
    [void](New-Item -ItemType Directory -Path $ProjectRoot, $ExternalRoot, (Join-Path $FixtureRoot "docker-config") -Force)
    foreach ($Name in $ControlledNames) {
        $SavedEnvironment[$Name] = @{
            Present = Test-Path -LiteralPath ("Env:" + $Name)
            Value = [Environment]::GetEnvironmentVariable($Name, "Process")
        }
        # Modern .NET can retain an empty variable when SetEnvironmentVariable
        # receives null. Compose treats that as an override of the fixture file.
        Remove-Item -LiteralPath ("Env:" + $Name) -ErrorAction SilentlyContinue
        Assert-Check (-not (Test-Path -LiteralPath ("Env:" + $Name))) "fixture clears process override: $Name"
    }
    [Environment]::SetEnvironmentVariable("DOCKER_CONFIG", (Join-Path $FixtureRoot "docker-config"), "Process")
    [IO.File]::WriteAllText((Join-Path $ProjectRoot "compose.yaml"), $ComposeSource, $Utf8)
    [IO.File]::WriteAllText((Join-Path $ProjectRoot "compose.push.local.yaml"), $OverlaySource, $Utf8)
    [IO.File]::WriteAllLines((Join-Path $ProjectRoot ".env.worker-db.local"), @(
        "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME=fixture_worker", "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD=fixture-only"), $Utf8)
    [IO.File]::WriteAllLines((Join-Path $ProjectRoot ".env.worker-rabbit.local"), @(
        "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME=fixture-worker", "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD=fixture-only"), $Utf8)
    $AdcPath = Join-Path $ExternalRoot "fake-adc.json"
    [IO.File]::WriteAllText($AdcPath, '{"type":"fixture-not-a-credential"}', $Utf8)
    $FixtureKey = [Convert]::ToBase64String((New-Object byte[] 32))
    $EnabledValues = @{
        SOUNDCONNECT_PUSH_ENABLED = "true"
        SOUNDCONNECT_FCM_PROJECT_ID = "soundconnect-fixture"
        SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY = $FixtureKey
        SOUNDCONNECT_FCM_ADC_HOST_PATH = $AdcPath.Replace('\', '/')
        GOOGLE_APPLICATION_CREDENTIALS = "C:/fixture/incorrect-host-path.json"
    }

    Write-FixtureEnvironment
    Initialize-LocalPushConfiguration
    $Disabled = Get-FixtureModel
    Assert-Check (-not $script:UsePushCompose) "disabled push omits the overlay and needs no credentials"
    Assert-Check ($Disabled.services.backend.environment.SOUNDCONNECT_PUSH_ENABLED -eq "false") "disabled environment flag"
    Assert-Check ($null -eq $Disabled.services.backend.environment.GOOGLE_APPLICATION_CREDENTIALS) "disabled API has no ADC variable"
    Assert-NoAdcMount $Disabled "disabled API has no ADC mount"

    Write-FixtureEnvironment $EnabledValues
    Initialize-LocalPushConfiguration
    $EnabledModel = Get-FixtureModel
    Invoke-Compose @("config", "--quiet")
    Assert-Check ($LASTEXITCODE -eq 0) "production Compose helper accepts the existing array call convention"
    $Api = $EnabledModel.services.backend
    Assert-Check $script:UsePushCompose "enabled push selects overlay"
    Assert-Check ($Api.environment.SOUNDCONNECT_PUSH_ENABLED -eq "true") "enabled API flag"
    Assert-Check ($Api.environment.GOOGLE_APPLICATION_CREDENTIALS -eq "/run/secrets/soundconnect-push-adc.json") "ADC discovery receives the container path"
    Assert-Check ($Api.environment.SOUNDCONNECT_FCM_CREDENTIALS_PATH -eq "") "private-key loader remains unused"
    $Mounts = @($Api.volumes | Where-Object { $_.target -eq "/run/secrets/soundconnect-push-adc.json" })
    Assert-Check ($Mounts.Count -eq 1) "exactly one ADC mount"
    Assert-Check ($Mounts[0].type -eq "bind" -and $Mounts[0].read_only -eq $true) "ADC mount is read-only bind"
    # Compose omits false-valued bind fields from canonical JSON; true is emitted explicitly.
    Assert-Check ($Mounts[0].bind.create_host_path -ne $true -and $OverlaySource -match 'create_host_path:\s*false') "missing credential files are not created as directories"
    Assert-Check ([IO.Path]::GetFullPath($Mounts[0].source) -eq [IO.Path]::GetFullPath($AdcPath)) "mount source is the validated external path"
    foreach ($Service in $EnabledModel.services.PSObject.Properties.Name | Where-Object { $_ -ne "backend" }) {
        $Before = $Disabled.services.$Service | ConvertTo-Json -Depth 50 -Compress
        $After = $EnabledModel.services.$Service | ConvertTo-Json -Depth 50 -Compress
        Assert-Check ($Before -eq $After) "push leaves service unchanged: $Service"
        $Forbidden = @($EnabledModel.services.$Service.environment.PSObject.Properties.Name |
            Where-Object { $_ -eq "GOOGLE_APPLICATION_CREDENTIALS" -or $_ -like "SOUNDCONNECT_FCM_*" -or $_ -like "SOUNDCONNECT_PUSH_*" })
        Assert-Check ($Forbidden.Count -eq 0) "no push credentials reach service: $Service"
    }

    # Mock the bootstrap's mutations; still render its exact Compose selection.
    $script:BootstrapCommands = @()
    $script:FailBootstrapWait = $false
    function Invoke-Compose([string[]]$Arguments, [switch]$WithoutPush) {
        $script:BootstrapCommands += ($Arguments -join " ")
        Assert-Check $WithoutPush.IsPresent "bootstrap command excludes ADC overlay"
        Assert-Check ($env:SOUNDCONNECT_PUSH_ENABLED -eq "false") "bootstrap command forces push off"
        if ($Arguments[0] -eq "up") {
            $BootstrapModel = Get-FixtureModel -WithoutPush
            Assert-Check ($BootstrapModel.services.backend.environment.SOUNDCONNECT_PUSH_ENABLED -eq "false") "bootstrap overrides enabled env_file"
            Assert-NoAdcMount $BootstrapModel "bootstrap does not mount ADC"
        }
    }
    function Wait-LocalComposeServicesHealthy([string[]]$Services) {
        if ($script:FailBootstrapWait) { throw "fixture-bootstrap-wait-failed" }
    }
    Invoke-LocalSchemaBootstrap
    Assert-Check ($env:SOUNDCONNECT_PUSH_ENABLED -eq "true") "successful bootstrap restores configured push"
    Assert-Check ($script:BootstrapCommands[-1] -eq "stop backend") "temporary bootstrap backend is stopped"
    $script:FailBootstrapWait = $true
    Assert-Rejected { Invoke-LocalSchemaBootstrap } "fixture-bootstrap-wait-failed" "bootstrap failure is propagated"
    Assert-Check ($env:SOUNDCONNECT_PUSH_ENABLED -eq "true") "failed bootstrap also restores configured push"
    Assert-Check ($script:BootstrapCommands[-1] -eq "stop backend") "failed bootstrap still stops backend"

    $Action = "boot"
    Write-FixtureEnvironment $EnabledValues
    Initialize-LocalPushConfiguration
    Assert-Check (-not $script:UsePushCompose) "native boot omits container mount"
    Import-DotEnv $EnvFile
    Set-NativePushEnvironment
    Assert-Check ($env:GOOGLE_APPLICATION_CREDENTIALS -eq $AdcPath) "native JVM inherits the real host ADC environment variable"
    Assert-Check ([string]::IsNullOrEmpty($env:SOUNDCONNECT_FCM_CREDENTIALS_PATH)) "native ADC does not invoke private-key loader"

    $Action = "up"
    Write-FixtureEnvironment @{ SOUNDCONNECT_PUSH_ENABLED = "true" }
    [Environment]::SetEnvironmentVariable("SOUNDCONNECT_PUSH_ENABLED", "false", "Process")
    Initialize-LocalPushConfiguration
    $OverriddenDisabled = Get-FixtureModel
    Assert-Check (-not $script:UsePushCompose -and $OverriddenDisabled.services.backend.environment.SOUNDCONNECT_PUSH_ENABLED -eq "false") "process kill switch overrides dotenv enablement without credentials"
    Assert-NoAdcMount $OverriddenDisabled "process kill switch removes the credential mount"
    foreach ($Action in @("config", "reset-local")) {
        Write-FixtureEnvironment $EnabledValues
        Initialize-LocalPushConfiguration
        Assert-Check $script:UsePushCompose "activation/configuration action selects push overlay: $Action"
    }
    $Action = "up"
    foreach ($Case in @(
        @{ Key = "SOUNDCONNECT_PUSH_ENABLED"; Value = "invalid"; Error = "must be true or false" },
        @{ Key = "SOUNDCONNECT_FCM_PROJECT_ID"; Value = "Invalid Project"; Error = "valid SOUNDCONNECT_FCM_PROJECT_ID" },
        @{ Key = "SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY"; Value = "invalid-base64"; Error = "32 random bytes" },
        @{ Key = "SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY"; Value = [Convert]::ToBase64String((New-Object byte[] 31)); Error = "32 random bytes" },
        @{ Key = "SOUNDCONNECT_FCM_CREDENTIALS_PATH"; Value = "some-private-key.json"; Error = "leave SOUNDCONNECT_FCM_CREDENTIALS_PATH empty" },
        @{ Key = "SOUNDCONNECT_FCM_ADC_HOST_PATH"; Value = "relative.json"; Error = "absolute local file path" },
        @{ Key = "SOUNDCONNECT_FCM_ADC_HOST_PATH"; Value = (Join-Path $ExternalRoot "missing.json"); Error = "external ADC file is missing" },
        @{ Key = "SOUNDCONNECT_FCM_ADC_HOST_PATH"; Value = $ExternalRoot; Error = "external ADC file is missing" },
        @{ Key = "SOUNDCONNECT_FCM_ADC_HOST_PATH"; Value = (Join-Path $ProjectRoot "compose.yaml"); Error = "outside the SoundConnect workspace" }
    )) {
        $Values = $EnabledValues.Clone()
        $Values[$Case.Key] = $Case.Value
        Write-FixtureEnvironment $Values
        Assert-Rejected { Initialize-LocalPushConfiguration } $Case.Error "invalid setting rejected: $($Case.Key)"
    }

    foreach ($Action in @("down", "logs", "ps", "infra", "idea")) {
        Write-FixtureEnvironment @{ SOUNDCONNECT_PUSH_ENABLED = "true" }
        Initialize-LocalPushConfiguration
        Assert-Check (-not $script:UsePushCompose) "recovery/infrastructure action needs no ADC: $Action"
    }
    $RegistryStart = $DevSource.IndexOf('$LocalSchemaMigrations = @(')
    $RegistryEnd = $DevSource.IndexOf("`n)", $RegistryStart)
    $Registry = $DevSource.Substring($RegistryStart, $RegistryEnd - $RegistryStart)
    $PushMigration = "2026-09-22-push-delivery-foundation.sql"
    Assert-Check ([regex]::Matches($Registry, [regex]::Escape($PushMigration)).Count -eq 1) "push migration registered exactly once"
    Assert-Check ($Registry.IndexOf($PushMigration) -gt $Registry.IndexOf("2026-09-10-listener-account-erasure.sql")) "push migration follows erased_at prerequisite"
    Write-Host "Push Compose configuration verified: $script:Checks checks passed. No containers, database changes or real credentials were used." -ForegroundColor Green
}
finally {
    Restore-LocalPushEnvironment
    foreach ($Name in $SavedEnvironment.Keys) {
        if ($SavedEnvironment[$Name].Present) {
            [Environment]::SetEnvironmentVariable($Name, $SavedEnvironment[$Name].Value, "Process")
        }
        else {
            Remove-Item -LiteralPath ("Env:" + $Name) -ErrorAction SilentlyContinue
        }
    }
    # Only remove this invocation's exact generated fixture beneath the system temp directory.
    $ResolvedFixture = [IO.Path]::GetFullPath($FixtureRoot)
    $ResolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($ResolvedFixture.StartsWith($ResolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($ResolvedFixture) -match '^soundconnect-push-compose-[a-f0-9]{32}$' -and
        (Test-Path -LiteralPath $ResolvedFixture)) {
        Remove-Item -LiteralPath $ResolvedFixture -Recurse -Force
    }
}
