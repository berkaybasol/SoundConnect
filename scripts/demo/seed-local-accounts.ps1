[CmdletBinding()]
param(
    [switch]$Apply,
    [string]$PasswordFile,
    [string]$AccountsFile = (Join-Path $PSScriptRoot 'accounts.json')
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$OutputDirectory = Join-Path $ProjectRoot 'tmp/marketplace-demo'
$GuardFile = Join-Path $OutputDirectory 'docker-identity.local.json'
$OutputFile = Join-Path $OutputDirectory $(if ($Apply) { 'accounts-created.json' } else { 'accounts-preview.json' })
$OldDemoPassword = [Environment]::GetEnvironmentVariable('SOUNDCONNECT_DEMO_PASSWORD', 'Process')

function Invoke-DockerRead([string[]]$DockerArguments) {
    $Result = & docker @DockerArguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw ('Local Docker identity check failed during ' + $DockerArguments[0] + '; no database writes allowed.') }
    return ($Result -join "`n").Trim()
}

try {
    # This command never uses compose up/build, Gradle or the existing API process.
    $DockerEndpoint = Invoke-DockerRead -DockerArguments @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}')
    if ($DockerEndpoint -notmatch '^(npipe:|unix:)') { throw 'The seed requires a local Docker socket; remote contexts are refused.' }
    $ContainerIds = @( (Invoke-DockerRead -DockerArguments @('ps', '--filter', 'label=com.docker.compose.project=soundconnect-local', '--filter', 'label=com.docker.compose.service=postgres', '--format', '{{.ID}}')) -split "`n" | Where-Object { $_ })
    if ($ContainerIds.Count -ne 1) { throw 'Exactly one running soundconnect-local/postgres container is required.' }
    $ContainerId = $ContainerIds[0]
    # Only specific non-secret inspect fields are read, never Config.Env.
    $ComposeLabels = (Invoke-DockerRead -DockerArguments @('inspect', '--format', '{{json .Config.Labels}}', $ContainerId)) | ConvertFrom-Json
    $LabelRoot = $ComposeLabels.'com.docker.compose.project.working_dir'
    if ([IO.Path]::GetFullPath($LabelRoot).TrimEnd('\','/') -ine $ProjectRoot.TrimEnd('\','/')) { throw 'Docker Compose project belongs to a different directory.' }
    $Ports = (Invoke-DockerRead -DockerArguments @('inspect', '--format', '{{json .NetworkSettings.Ports}}', $ContainerId)) | ConvertFrom-Json
    $Bindings = @($Ports.'5432/tcp')
    if ($Bindings.Count -ne 1 -or $Bindings[0].HostIp -notin @('127.0.0.1', '::1')) { throw 'PostgreSQL must expose exactly one loopback-only host port.' }
    $ProofSql = "select json_build_object('database',current_database(),'systemIdentifier',system_identifier::text)::text from pg_control_system();"
    $ProofRaw = $ProofSql | & docker exec -i $ContainerId sh -c 'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot prove PostgreSQL identity inside the selected container.' }
    $Proof = ($ProofRaw -join "`n").Trim() | ConvertFrom-Json
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    @{ project='soundconnect-local'; service='postgres'; root=$ProjectRoot; containerId=$ContainerId;
       hostPort=[int]$Bindings[0].HostPort; database=$Proof.database; systemIdentifier=$Proof.systemIdentifier;
       checkedAt=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath $GuardFile -Encoding utf8

    if ($PasswordFile) {
        try { $Credentials = Get-Content -LiteralPath $PasswordFile -Raw | ConvertFrom-Json }
        catch { throw 'Password file could not be read as JSON; credential content was not logged.' }
        if (-not ($Credentials.password -is [string]) -or $Credentials.password.Length -lt 16) { throw 'Password file must contain a password string of at least 16 characters.' }
        [Environment]::SetEnvironmentVariable('SOUNDCONNECT_DEMO_PASSWORD', $Credentials.password, 'Process')
        $Credentials = $null
    }
    if ($Apply -and -not [Environment]::GetEnvironmentVariable('SOUNDCONNECT_DEMO_PASSWORD', 'Process')) { throw 'Apply requires -PasswordFile or SOUNDCONNECT_DEMO_PASSWORD.' }
    $CacheRoot = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle/caches/modules-2/files-2.1'
    $Dependencies = @(
        @('org.springframework.security','spring-security-crypto','6.4.3'),
        @('org.springframework','spring-jcl','6.2.3'),
        @('org.postgresql','postgresql','42.7.5'),
        @('com.fasterxml.jackson.core','jackson-core','2.18.2'),
        @('com.fasterxml.jackson.core','jackson-databind','2.18.2'),
        @('com.fasterxml.jackson.core','jackson-annotations','2.18.2')
    )
    $Jars = foreach ($Dependency in $Dependencies) {
        $Directory = Join-Path $CacheRoot ($Dependency -join '/')
        $Matches = @(Get-ChildItem -LiteralPath $Directory -Recurse -File -Filter ($Dependency[1]+'-'+$Dependency[2]+'.jar'))
        if ($Matches.Count -ne 1) { throw 'Required existing Java dependency is missing or ambiguous; no downloads/builds will be started.' }
        $Matches[0].FullName
    }
    $Java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { Join-Path $env:JAVA_HOME 'bin/java.exe' }
            elseif (Test-Path 'C:\Program Files\Java\jdk-21.0.9\bin\java.exe') { 'C:\Program Files\Java\jdk-21.0.9\bin\java.exe' }
            else { (Get-Command java -ErrorAction Stop).Source }
    $Mode = if ($Apply) { 'apply' } else { 'dry-run' }
    # JDK source-file mode compiles this CLI in memory; backend build/classes stays untouched.
    & $Java --source 21 --class-path ($Jars -join [IO.Path]::PathSeparator) (Join-Path $PSScriptRoot 'LocalDemoAccounts.java') `
        --mode $Mode --root $ProjectRoot --accounts ([IO.Path]::GetFullPath($AccountsFile)) --guard $GuardFile --output $OutputFile
    if ($LASTEXITCODE -ne 0) { throw 'Local demo seed did not complete. No credential details logged; inspect the safe validation message above.' }
} finally {
    [Environment]::SetEnvironmentVariable('SOUNDCONNECT_DEMO_PASSWORD', $OldDemoPassword, 'Process')
}
