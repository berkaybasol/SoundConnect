[CmdletBinding()]
param(
    [ValidateSet("up", "idea", "infra", "boot", "down", "logs", "ps", "config", "reset-local")]
    [string]$Action = "up",

    [string]$Service,

    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $ProjectRoot ".env.local"
$EnvTemplate = Join-Path $ProjectRoot ".env.example"
$WorkerDbEnvFile = Join-Path $ProjectRoot ".env.worker-db.local"
$WorkerRabbitEnvFile = Join-Path $ProjectRoot ".env.worker-rabbit.local"
$script:UsePushCompose = $false
$script:UseLocalApiImage = $false
$script:LocalPushSettings = @{ Enabled = $false }
$script:LocalPushEnvironmentBefore = @{}
$LocalSchemaMigrations = @(
    @{ Name = "Studio domain"; Path = Join-Path $ProjectRoot "scripts\db\2026-07-21-studio-domain.sql" },
    @{ Name = "Collab domain"; Path = Join-Path $ProjectRoot "scripts\db\2026-08-11-collab-domain.sql" },
    @{ Name = "Collab moderation"; Path = Join-Path $ProjectRoot "scripts\db\2026-08-11-collab-moderation.sql" },
    @{ Name = "Collab notification outbox"; Path = Join-Path $ProjectRoot "scripts\db\2026-08-11-collab-notification-outbox.sql" },
    @{ Name = "TableGroup hardening"; Path = Join-Path $ProjectRoot "scripts\db\2026-08-17-tablegroup-hardening.sql" },
    @{ Name = "TableGroup who-pays game"; Path = Join-Path $ProjectRoot "scripts\db\2026-08-30-tablegroup-who-pays-game.sql" },
    @{ Name = "TableGroup global feed"; Path = Join-Path $ProjectRoot "scripts\db\2026-08-31-tablegroup-global-feed.sql" },
    @{ Name = "TableGroup description"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-01-tablegroup-description.sql" },
    @{ Name = "TableGroup optional venue"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-01-tablegroup-optional-venue.sql" },
    @{ Name = "TableGroup meeting time"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-01-tablegroup-meeting-time.sql" },
    @{ Name = "TableGroup chat idempotency"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-02-tablegroup-chat-idempotency.sql" },
    @{ Name = "TableGroup notification type spelling"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-02-tablegroup-notification-type-spelling.sql" },
    @{ Name = "TableGroup strict create contract"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-02-tablegroup-create-contract-strict.sql" },
    @{ Name = "Listener ghost profile"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-03-listener-ghost-profile.sql" },
    @{ Name = "Listener Spotify playlists"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-04-listener-spotify-playlists.sql" },
    @{ Name = "Event performer consent"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-04-event-performer-consent.sql" },
    @{ Name = "Musician calendar"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-05-musician-calendar.sql" },
    @{ Name = "Performer calendar opt-in"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-05-performer-calendar-opt-in.sql" },
    @{ Name = "Event profile visibility consent"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-05-event-profile-visibility-consent.sql" },
    @{ Name = "Reciprocal musician events"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-05-reciprocal-musician-events.sql" },
    @{ Name = "Event profile publications"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-06-event-profile-publications.sql" },
    @{ Name = "Event plans"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-21-event-plans.sql" },
    @{ Name = "Event management history"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-21-event-management-history.sql" },
    @{ Name = "Band member titles"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-06-band-member-titles.sql" },
    @{ Name = "Band invitation identity"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-07-band-invitation-identity.sql" },
    @{ Name = "Notification replay receipts"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-07-notification-replay-receipts.sql" },
    @{ Name = "Venue analytics"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-08-venue-analytics.sql" },
    @{ Name = "Venue suggestions"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-08-venue-suggestions.sql" },
    @{ Name = "Event audience intents"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-08-event-audience-intents.sql" },
    @{ Name = "Comment likes"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-09-comment-likes.sql" },
    @{ Name = "Event post comments"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-09-event-post-comments.sql" },
    @{ Name = "Overthinking lifecycle"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-09-overthinking-lifecycle.sql" },
    @{ Name = "Overthinking notification outbox"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-09-overthinking-notification-outbox.sql" },
    @{ Name = "Overthinking inbox seen"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-overthinking-inbox-seen.sql" },
    @{ Name = "Overthinking profile shares"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-overthinking-profile-shares.sql" },
    @{ Name = "Overthinking production safety"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-overthinking-production-safety.sql" },
    @{ Name = "Listener account erasure"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-listener-account-erasure.sql" },
    @{ Name = "TableGroup profile shares"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-tablegroup-profile-shares.sql" },
    @{ Name = "TableGroup profile share history"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-tablegroup-profile-share-history.sql" },
    @{ Name = "Musician feed preferences"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-11-musician-feed-preferences.sql" },
    @{ Name = "Overthinking profile share engagement"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-11-overthinking-profile-share-engagement.sql" },
    @{ Name = "Musician feed delivery"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-11-musician-feed-delivery.sql" },
    @{ Name = "Musician feed continuation replay"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-11-musician-feed-replay.sql" },
    @{ Name = "Musician feed feedback"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-11-musician-feed-feedback.sql" },
    @{ Name = "Musician feed online indexes"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-11-musician-feed-indexes.sql" },
    @{ Name = "Like users pagination index"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-12-like-users-pagination.sql" },
    @{ Name = "Musician feed feedback lookup"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-13-musician-feed-feedback-lookup.sql" },
    @{ Name = "Musician feed moderation"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-13-musician-feed-moderation.sql" },
    @{ Name = "Musician feed retention lookup"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-13-musician-feed-retention-lookup.sql" },
    @{ Name = "Musician feed recent views"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-14-musician-feed-recent-views.sql" },
    @{ Name = "Feed announcements"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-13-feed-announcements.sql" },
    @{ Name = "Announcement feed plan"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-13-announcement-feed-plan.sql" },
    @{ Name = "Announcement analytics"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-13-announcement-analytics.sql" },
    @{ Name = "Mainstage content audience"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-14-mainstage-content-audience.sql" },
    @{ Name = "Marketplace domain and catalog"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-20-marketplace-domain.sql" },
    @{ Name = "Marketplace protected media"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-20-marketplace-media.sql" },
    @{ Name = "Protected image thumbnails"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-20-protected-image-thumbnails.sql" },
    @{ Name = "Push delivery foundation"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-22-push-delivery-foundation.sql"; Marker = "2026-09-22-push-delivery-foundation" },
    @{ Name = "Push device registration revision"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-23-push-device-registration-revision.sql"; Marker = "2026-09-23-push-device-registration-revision" },
    @{ Name = "DM send reliability"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-23-dm-send-reliability.sql" },
    @{ Name = "DM conversation pagination"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-23-dm-conversation-pagination.sql" },
    @{ Name = "Push native venue capability"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-24-push-native-venue-capability.sql"; Marker = "2026-09-24-push-native-venue-capability" },
    @{ Name = "Venue application notifications"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-24-venue-application-notifications.sql"; Marker = "2026-09-24-venue-application-notifications" },
    @{ Name = "Studio reservation notification outbox"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-24-studio-reservation-notification-outbox.sql" },
    @{ Name = "Follow notification outbox"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-27-follow-notification-outbox.sql" },
    @{ Name = "Push native studio capability"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-24-push-native-studio-capability.sql"; Marker = "2026-09-24-push-native-studio-capability" },
    @{ Name = "Push native follow capability"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-28-push-native-follow-capability.sql"; Marker = "2026-09-28-push-native-follow-capability" },
    @{ Name = "Push native media capability"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-29-push-native-media-capability.sql"; Marker = "2026-09-29-push-native-media-capability" }
    @{ Name = "Push native band capability"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-29-push-native-band-capability.sql"; Marker = "2026-09-29-push-native-band-capability" },
    @{ Name = "Table notification target"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-30-table-notification-target.sql"; Marker = "2026-09-30-table-notification-target" },
    @{ Name = "Push native table capability"; Path = Join-Path $ProjectRoot "scripts\db\2026-10-01-push-native-table-capability.sql"; Marker = "2026-10-01-push-native-table-capability" },
    @{ Name = "Push native collab capability"; Path = Join-Path $ProjectRoot "scripts\db\2026-10-01-push-native-collab-capability.sql"; Marker = "2026-10-01-push-native-collab-capability" }
    @{ Name = "Push native overthinking capability"; Path = Join-Path $ProjectRoot "scripts\db\2026-10-01-push-native-overthinking-capability.sql"; Marker = "2026-10-01-push-native-overthinking-capability" }
    @{ Name = "Media notification identity"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-28-media-notification-identity.sql"; Marker = "2026-09-28-media-notification-identity" }
)

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found on PATH."
    }
}

function Assert-LocalEnv {
    if (Test-Path -LiteralPath $EnvFile) {
        return
    }

    Copy-Item -LiteralPath $EnvTemplate -Destination $EnvFile
    Write-Host "Created .env.local from .env.example." -ForegroundColor Yellow
    Write-Host "Fill in the real local secrets, then run this command again." -ForegroundColor Yellow
    exit 2
}

function Read-DotEnvMap([string]$Path) {
    $Values = @{}
    foreach ($Line in Get-Content -LiteralPath $Path) {
        $Trimmed = $Line.Trim()
        if (-not $Trimmed -or $Trimmed.StartsWith("#")) {
            continue
        }

        $Pair = $Trimmed.Split("=", 2)
        if ($Pair.Count -ne 2 -or -not $Pair[0].Trim()) {
            throw "Invalid entry in ${Path}: $Line"
        }

        $Key = $Pair[0].Trim()
        if ($Values.ContainsKey($Key)) {
            throw "Duplicate key '$Key' in $Path"
        }

        $Value = $Pair[1].Trim()
        if ($Value.Length -ge 2 -and (($Value.StartsWith('"') -and $Value.EndsWith('"')) -or ($Value.StartsWith("'") -and $Value.EndsWith("'")))) {
            $Value = $Value.Substring(1, $Value.Length - 2)
        }
        $Values[$Key] = $Value
    }
    return $Values
}

function New-RandomHexSecret {
    $Bytes = New-Object byte[] 32
    $Generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $Generator.GetBytes($Bytes)
    }
    finally {
        $Generator.Dispose()
    }
    return -join ($Bytes | ForEach-Object { $_.ToString("x2") })
}

function New-WorkerCredentialFile(
    [string]$Path,
    [string]$Description,
    [string]$UsernameKey,
    [string]$Username,
    [string]$PasswordKey
) {
    $Secret = New-RandomHexSecret
    $Lines = @(
        "# Generated by scripts/dev.ps1. Local only; never commit this file.",
        "${UsernameKey}=${Username}",
        "${PasswordKey}=${Secret}"
    )
    $Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $Lines, $Utf8NoBom)
    Write-Host "Generated isolated $Description credentials." -ForegroundColor Green
}

function Assert-WorkerCredentialFile(
    [string]$Path,
    [string]$Description,
    [string]$UsernameKey,
    [string]$PasswordKey,
    [string]$UsernamePattern
) {
    $Values = Read-DotEnvMap $Path
    $AllowedKeys = @($UsernameKey, $PasswordKey)
    $UnexpectedKeys = @($Values.Keys | Where-Object { $_ -notin $AllowedKeys })
    if ($UnexpectedKeys) {
        throw "$Description credential file contains unsupported keys: $($UnexpectedKeys -join ', ')"
    }

    $Username = $Values[$UsernameKey]
    $Password = $Values[$PasswordKey]
    if ([string]::IsNullOrWhiteSpace($Username) -or $Username -notmatch $UsernamePattern) {
        throw "$Description username is missing or contains unsupported characters."
    }
    if ([string]::IsNullOrWhiteSpace($Password) -or $Password.Length -lt 48 -or $Password -notmatch '^[A-Za-z0-9._~-]+$') {
        throw "$Description password must be an independently generated URL-safe secret of at least 48 characters."
    }
}

function Initialize-WorkerCredentials {
    if (-not (Test-Path -LiteralPath $WorkerDbEnvFile)) {
        New-WorkerCredentialFile `
            -Path $WorkerDbEnvFile `
            -Description "media-worker PostgreSQL" `
            -UsernameKey "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME" `
            -Username "soundconnect_media_worker" `
            -PasswordKey "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD"
    }
    if (-not (Test-Path -LiteralPath $WorkerRabbitEnvFile)) {
        New-WorkerCredentialFile `
            -Path $WorkerRabbitEnvFile `
            -Description "media-worker RabbitMQ" `
            -UsernameKey "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME" `
            -Username "soundconnect-media-worker" `
            -PasswordKey "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD"
    }

    Assert-WorkerCredentialFile `
        -Path $WorkerDbEnvFile `
        -Description "media-worker PostgreSQL" `
        -UsernameKey "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME" `
        -PasswordKey "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD" `
        -UsernamePattern '^[A-Za-z0-9_-]+$'
    Assert-WorkerCredentialFile `
        -Path $WorkerRabbitEnvFile `
        -Description "media-worker RabbitMQ" `
        -UsernameKey "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME" `
        -PasswordKey "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD" `
        -UsernamePattern '^[A-Za-z0-9._-]+$'
}

function Assert-ApiEnvironmentBoundary {
    $Values = Read-DotEnvMap $EnvFile
    $WorkerKeys = @($Values.Keys | Where-Object { $_ -like 'SOUNDCONNECT_MEDIA_WORKER_*' })
    if ($WorkerKeys) {
        throw ".env.local is API-only and must not contain media-worker variables. Use the generated .env.worker-*.local files."
    }
}

function Assert-NoPlaceholders {
    $RequiredKeys = @(
        "SOUNDCONNECT_POSTGRES_PASSWORD",
        "SOUNDCONNECT_JWT_SECRETKEY",
        "SPRING_RABBITMQ_PASSWORD",
        "MAILERSEND_API_KEY",
        "MAILERSEND_SENDER_EMAIL",
        "SPOTIFY_CLIENT_ID",
        "SPOTIFY_CLIENT_SECRET"
    )

    $PlaceholderKeys = foreach ($Line in Get-Content -LiteralPath $EnvFile) {
        $Trimmed = $Line.Trim()
        if (-not $Trimmed -or $Trimmed.StartsWith("#")) {
            continue
        }

        $Pair = $Trimmed.Split("=", 2)
        if ($Pair.Count -ne 2) {
            continue
        }

        $Value = $Pair[1].Trim().Trim('"').Trim("'")
        $Key = $Pair[0].Trim()
        if ($Key -in $RequiredKeys -and ($Value -match "^(change-me|replace-me(?:$|\..*)|replace-with-.+)$" -or $Value -match "example\.com")) {
            $Key
        }
    }

    if ($PlaceholderKeys) {
        $Names = ($PlaceholderKeys | Sort-Object -Unique) -join ", "
        throw "Replace placeholder values in .env.local before startup: $Names"
    }
}

function Get-LocalSetting([hashtable]$Values, [string]$Name) {
    $ProcessValue = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ($null -ne $ProcessValue) { return $ProcessValue }
    return $Values[$Name]
}

function Set-LocalPushEnvironment([string]$Name, [string]$Value) {
    if (-not $script:LocalPushEnvironmentBefore.ContainsKey($Name)) {
        $script:LocalPushEnvironmentBefore[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Restore-LocalPushEnvironment {
    foreach ($Name in $script:LocalPushEnvironmentBefore.Keys) {
        [Environment]::SetEnvironmentVariable($Name, $script:LocalPushEnvironmentBefore[$Name], "Process")
    }
    $script:LocalPushEnvironmentBefore = @{}
}

function Initialize-LocalPushConfiguration {
    # Recovery/read-only and infrastructure-only actions never require an ADC file.
    if ($Action -notin @("up", "boot", "config", "reset-local")) { return }
    $Values = Read-DotEnvMap $EnvFile
    $Enabled = Get-LocalSetting $Values "SOUNDCONNECT_PUSH_ENABLED"
    if ([string]::IsNullOrWhiteSpace($Enabled)) { $Enabled = "false" }
    if ($Enabled -notmatch '^(?i:true|false)$') {
        throw "SOUNDCONNECT_PUSH_ENABLED must be true or false."
    }
    if ($Enabled -ieq "false") { return }

    $Project = Get-LocalSetting $Values "SOUNDCONNECT_FCM_PROJECT_ID"
    if ($Project -cnotmatch '^[a-z][a-z0-9-]{4,28}[a-z0-9]$') {
        throw "Enabled push requires a valid SOUNDCONNECT_FCM_PROJECT_ID."
    }
    $EncryptionKey = Get-LocalSetting $Values "SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY"
    try { $KeyBytes = [Convert]::FromBase64String($EncryptionKey) }
    catch { throw "Enabled push requires SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY as base64-encoded 32 random bytes." }
    if ($KeyBytes.Length -ne 32) {
        throw "Enabled push requires SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY as base64-encoded 32 random bytes."
    }
    [Array]::Clear($KeyBytes, 0, $KeyBytes.Length)
    if (-not [string]::IsNullOrWhiteSpace((Get-LocalSetting $Values "SOUNDCONNECT_FCM_CREDENTIALS_PATH"))) {
        throw "Local push uses impersonated ADC; leave SOUNDCONNECT_FCM_CREDENTIALS_PATH empty."
    }

    $AdcPath = Get-LocalSetting $Values "SOUNDCONNECT_FCM_ADC_HOST_PATH"
    if ([string]::IsNullOrWhiteSpace($AdcPath) -or $AdcPath -notmatch '^[A-Za-z]:[\\/]') {
        throw "Enabled push requires SOUNDCONNECT_FCM_ADC_HOST_PATH as an absolute local file path outside the workspace."
    }
    if (-not (Test-Path -LiteralPath $AdcPath -PathType Leaf)) {
        throw "The configured external ADC file is missing. Complete local ADC login before enabling push."
    }
    $AdcPath = (Resolve-Path -LiteralPath $AdcPath).ProviderPath
    $WorkspaceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $ProjectRoot)).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($AdcPath.StartsWith($WorkspaceRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The ADC file must remain outside the SoundConnect workspace; never copy credentials into a repository."
    }
    # Do not let junctions/symlinks disguise a workspace path or mount a different file.
    $PathEntry = Get-Item -LiteralPath $AdcPath -Force
    while ($null -ne $PathEntry) {
        if (($PathEntry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "The external ADC path must not traverse a symlink or junction."
        }
        if ($PathEntry -is [IO.FileInfo]) { $PathEntry = $PathEntry.Directory }
        else { $PathEntry = $PathEntry.Parent }
    }

    $script:LocalPushSettings = @{
        Enabled = $true
        Project = $Project
        EncryptionKey = $EncryptionKey
        AdcPath = $AdcPath
    }
    # Normalize the values used by Compose interpolation without printing any credentials.
    Set-LocalPushEnvironment "SOUNDCONNECT_PUSH_ENABLED" "true"
    Set-LocalPushEnvironment "SOUNDCONNECT_FCM_PROJECT_ID" $Project
    Set-LocalPushEnvironment "SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY" $EncryptionKey
    Set-LocalPushEnvironment "SOUNDCONNECT_FCM_ADC_HOST_PATH" $AdcPath
    $script:UsePushCompose = $Action -ne "boot"
}

function Initialize-LocalApiImage {
    # Optional immutable local deployment selection; never a secret or a build input.
    # boot/idea run source code explicitly and do not use the Docker API image.
    if ($Action -notin @("up", "config", "reset-local")) { return }
    $SelectionPath = Join-Path $ProjectRoot ".local-api-image"
    if (-not (Test-Path -LiteralPath $SelectionPath -PathType Leaf)) { return }
    $Image = (Get-Content -Raw -LiteralPath $SelectionPath).Trim()
    if ($Image -cnotmatch '^sha256:[a-f0-9]{64}$') {
        throw ".local-api-image must contain one immutable local sha256 image ID."
    }
    $Found = & docker image inspect --format '{{.Id}}' $Image 2>$null
    if ($LASTEXITCODE -ne 0 -or ($Found -join "").Trim() -cne $Image) {
        throw "Selected local API image is unavailable; restore it or explicitly remove .local-api-image to resume source builds."
    }
    Set-LocalPushEnvironment "SOUNDCONNECT_LOCAL_API_IMAGE" $Image
    $script:UseLocalApiImage = $true
}

function Get-ComposeArguments([switch]$WithoutPush) {
    $Result = @("--project-directory", $ProjectRoot, "--env-file", $EnvFile,
        "--file", (Join-Path $ProjectRoot "compose.yaml"))
    if ($script:UsePushCompose -and -not $WithoutPush) {
        $Result += @("--file", (Join-Path $ProjectRoot "compose.push.local.yaml"))
    }
    if ($script:UseLocalApiImage) {
        $Result += @("--file", (Join-Path $ProjectRoot "compose.api-image.local.yaml"))
    }
    return $Result
}

function Invoke-Compose([string[]]$Arguments, [switch]$WithoutPush) {
    $ComposeArgs = Get-ComposeArguments -WithoutPush:$WithoutPush
    & docker compose @ComposeArgs @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE."
    }
}

function Get-LocalPostgresConnection {
    $Values = Read-DotEnvMap $EnvFile
    return @{
        Username = if ($Values["SOUNDCONNECT_POSTGRES_USERNAME"]) { $Values["SOUNDCONNECT_POSTGRES_USERNAME"] } else { "soundconnect" }
        Database = if ($Values["SOUNDCONNECT_POSTGRES_DB"]) { $Values["SOUNDCONNECT_POSTGRES_DB"] } else { "soundconnect" }
    }
}

function Test-LocalBaseSchemaReady {
    $Connection = Get-LocalPostgresConnection
    $Query = "SELECT to_regclass('public.tbl_user') IS NOT NULL AND to_regclass('public.tbl_studio_profile') IS NOT NULL AND to_regclass('public.tbl_media_asset') IS NOT NULL AND to_regclass('public.tbl_role') IS NOT NULL AND to_regclass('public.tbl_permissions') IS NOT NULL;"
    $ComposeArgs = Get-ComposeArguments
    $Output = & docker compose @ComposeArgs `
        exec -T postgres psql `
        -X -qAt `
        -U $Connection.Username `
        -d $Connection.Database `
        -c $Query 2>$null
    return $LASTEXITCODE -eq 0 -and (($Output -join "").Trim() -eq "t")
}

function Test-LocalComposeServiceHealthy([string]$Service) {
    $ComposeArgs = Get-ComposeArguments
    $ContainerIds = & docker compose @ComposeArgs `
        ps --quiet $Service 2>$null
    $ComposeExitCode = $LASTEXITCODE
    $ContainerId = @($ContainerIds | Where-Object { $_ } | Select-Object -First 1)
    if ($ComposeExitCode -ne 0 -or $ContainerId.Count -eq 0) {
        return $false
    }

    $HealthStatus = & docker inspect `
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' `
        $ContainerId[0] 2>$null
    $InspectExitCode = $LASTEXITCODE
    return $InspectExitCode -eq 0 -and (($HealthStatus -join "").Trim() -eq "healthy")
}

function Wait-LocalComposeServicesHealthy(
    [string[]]$Services,
    [int]$TimeoutSeconds = 420
) {
    Write-Host "Waiting for healthy services: $($Services -join ', ')" -ForegroundColor Cyan
    $Deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $Deadline) {
        $Unhealthy = @($Services | Where-Object { -not (Test-LocalComposeServiceHealthy $_) })
        if ($Unhealthy.Count -eq 0) {
            Write-Host "Required services are healthy." -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }

    $ComposeArgs = Get-ComposeArguments
    & docker compose @ComposeArgs ps
    throw "Services did not become healthy within ${TimeoutSeconds}s: $($Services -join ', ')"
}

# Historical capability migrations are forward steps, not repeatable constraint repairs:
# replaying V1/V2/V3 would reject or narrow existing newer device registrations.
function Invoke-LocalSchemaQuery([string]$Query) {
    $Connection = Get-LocalPostgresConnection
    $ComposeArgs = Get-ComposeArguments
    $Output = & docker compose @ComposeArgs `
        exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 `
        -U $Connection.Username -d $Connection.Database -c $Query
    if ($LASTEXITCODE -ne 0) { throw "Local schema verification failed with exit code $LASTEXITCODE." }
    return ($Output -join "").Trim()
}

function Test-LocalMigrationApplied([hashtable]$Migration) {
    if (-not $Migration.Marker) { return $false }
    if ($Migration.Marker -notmatch '^20[0-9]{2}-[0-9]{2}-[0-9]{2}-[a-z0-9-]+$') {
        throw "Invalid local migration marker."
    }
    if ((Invoke-LocalSchemaQuery "SELECT to_regclass('public.soundconnect_schema_migrations') IS NOT NULL;") -eq "f") {
        return $false
    }
    return (Invoke-LocalSchemaQuery "SELECT EXISTS(SELECT 1 FROM soundconnect_schema_migrations WHERE migration_id='$($Migration.Marker)');") -eq "t"
}

function Assert-LocalPushSchemaReady {
    # A marker is only evidence that a forward step ran. Keep shape checks and the
    # application's existing PushOperations startup gate; never silently bless drift.
    $Query = @"
SELECT
    (SELECT count(*) FROM soundconnect_schema_migrations WHERE migration_id IN (
        '2026-09-22-push-delivery-foundation','2026-09-23-push-device-registration-revision',
        '2026-09-24-push-native-venue-capability','2026-09-24-venue-application-notifications',
        '2026-09-24-push-native-studio-capability','2026-09-28-push-native-follow-capability','2026-09-29-push-native-media-capability','2026-09-29-push-native-band-capability','2026-09-30-table-notification-target','2026-10-01-push-native-table-capability','2026-10-01-push-native-collab-capability','2026-10-01-push-native-overthinking-capability')) = 12
    AND EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass
        AND conname='ck_push_device_presentation' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_DM_V1%'
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V2%'
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V3%'
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V4%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V5%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V6%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V7%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V8%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V9%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V10%')
    AND EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass
        AND conname='ck_push_device_application_scope' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%application_scope_id%'
        AND pg_get_constraintdef(oid) LIKE '%presentation_version IS NOT NULL%'
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V3%'
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V4%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V5%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V6%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V7%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V8%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V9%' AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V10%')
    AND to_regclass('public.tbl_studio_reservation_notification_outbox') IS NOT NULL;
SELECT approved_venue_id FROM tbl_venue_applications LIMIT 0;
SELECT installation_id,generation,token_ciphertext,client_revision,presentation_version,application_scope_id FROM tbl_push_device LIMIT 0;
"@
    if ((Invoke-LocalSchemaQuery $Query) -ne "t") {
        throw "Local push schema does not satisfy V8 prerequisites; inspect the schema instead of replaying older capability constraints."
    }
}

function Sync-LocalSchemas {
    foreach ($Migration in $LocalSchemaMigrations) {
        if (-not (Test-Path -LiteralPath $Migration.Path)) {
            throw "Local schema source is missing: $($Migration.Path)"
        }
    }
    if (-not (Test-LocalBaseSchemaReady)) {
        throw "Local base schema is not ready; run '.\\dev.cmd up' once before using an IDE-only backend."
    }

    $Connection = Get-LocalPostgresConnection
    foreach ($Migration in $LocalSchemaMigrations) {
        if (Test-LocalMigrationApplied $Migration) {
            Write-Host "Already applied local $($Migration.Name) migration; preserving newer schema." -ForegroundColor DarkGray
            continue
        }
        Write-Host "Applying local $($Migration.Name) migration..." -ForegroundColor Cyan
        $ComposeArgs = Get-ComposeArguments
        Get-Content -Raw -LiteralPath $Migration.Path | & docker compose @ComposeArgs `
            exec -T postgres psql `
            -X -v ON_ERROR_STOP=1 `
            -U $Connection.Username `
            -d $Connection.Database
        if ($LASTEXITCODE -ne 0) {
            throw "$($Migration.Name) migration failed with exit code $LASTEXITCODE."
        }
    }
    Assert-LocalPushSchemaReady
    Write-Host "Local application schemas, including push delivery, are ready." -ForegroundColor Green
    return $true
}

function Import-DotEnv([string]$Path) {
    foreach ($Line in Get-Content -LiteralPath $Path) {
        $Trimmed = $Line.Trim()
        if (-not $Trimmed -or $Trimmed.StartsWith("#")) {
            continue
        }

        $Pair = $Trimmed.Split("=", 2)
        if ($Pair.Count -ne 2 -or -not $Pair[0].Trim()) {
            throw "Invalid entry in ${Path}: $Line"
        }

        $Value = $Pair[1].Trim()
        if ($Value.Length -ge 2 -and (($Value.StartsWith('"') -and $Value.EndsWith('"')) -or ($Value.StartsWith("'") -and $Value.EndsWith("'")))) {
            $Value = $Value.Substring(1, $Value.Length - 2)
        }

        [Environment]::SetEnvironmentVariable($Pair[0].Trim(), $Value, "Process")
    }
}

function Invoke-LocalSchemaBootstrap {
    $PreviousPushEnabled = [Environment]::GetEnvironmentVariable("SOUNDCONNECT_PUSH_ENABLED", "Process")
    try {
        # The base schema must exist before SQL can create the push tables/marker.
        # Omit the ADC overlay entirely and override env_file's configured enable flag.
        [Environment]::SetEnvironmentVariable("SOUNDCONNECT_PUSH_ENABLED", "false", "Process")
        Invoke-Compose -Arguments @("up", "--build", "--detach", "backend") -WithoutPush
        Wait-LocalComposeServicesHealthy @("backend")
    }
    finally {
        try { Invoke-Compose -Arguments @("stop", "backend") -WithoutPush }
        finally {
            [Environment]::SetEnvironmentVariable("SOUNDCONNECT_PUSH_ENABLED", $PreviousPushEnabled, "Process")
        }
    }
}

function Set-NativePushEnvironment {
    # Import-DotEnv creates process variables, but explicit validated settings take precedence.
    if (-not $script:LocalPushSettings.Enabled) {
        Set-LocalPushEnvironment "SOUNDCONNECT_PUSH_ENABLED" "false"
        return
    }
    Set-LocalPushEnvironment "SOUNDCONNECT_PUSH_ENABLED" "true"
    Set-LocalPushEnvironment "SOUNDCONNECT_FCM_PROJECT_ID" $script:LocalPushSettings.Project
    Set-LocalPushEnvironment "SOUNDCONNECT_PUSH_TOKEN_ENCRYPTION_KEY" $script:LocalPushSettings.EncryptionKey
    Set-LocalPushEnvironment "SOUNDCONNECT_FCM_CREDENTIALS_PATH" ""
    Set-LocalPushEnvironment "GOOGLE_APPLICATION_CREDENTIALS" $script:LocalPushSettings.AdcPath
}

Assert-Command "docker"
Assert-LocalEnv
Assert-ApiEnvironmentBoundary
Initialize-WorkerCredentials

Push-Location $ProjectRoot
try {
    Initialize-LocalPushConfiguration
    Initialize-LocalApiImage
    switch ($Action) {
        "up" {
            Assert-NoPlaceholders
            # Never start a new binary against an existing pre-migration schema.
            # On a genuinely empty database Hibernate performs the one-time
            # legacy bootstrap; every subsequent start applies the idempotent SQL
            # while API and worker traffic are stopped.
            Invoke-Compose @("stop", "backend", "media-worker")
            Invoke-Compose @("up", "--detach", "postgres", "rabbitmq", "redis")
            Wait-LocalComposeServicesHealthy @("postgres", "rabbitmq", "redis")
            if (-not (Test-LocalBaseSchemaReady)) {
                Write-Host "Bootstrapping an empty local schema once with Hibernate..." -ForegroundColor Yellow
                Invoke-LocalSchemaBootstrap
                if (-not (Test-LocalBaseSchemaReady)) {
                    throw "The bootstrap backend became healthy, but the required local base schema is incomplete."
                }
            }
            [void](Sync-LocalSchemas)
            Invoke-Compose @("up", "--build", "--detach")
            Wait-LocalComposeServicesHealthy @("postgres", "rabbitmq", "redis", "backend", "media-worker")
            Invoke-Compose @("ps")
        }
        "idea" {
            Assert-NoPlaceholders
            Invoke-Compose @("up", "--detach", "postgres", "rabbitmq", "redis")
            Invoke-Compose @("stop", "backend", "media-worker")
            Wait-LocalComposeServicesHealthy @("postgres", "rabbitmq", "redis")
            [void](Sync-LocalSchemas)
            Write-Host "Infrastructure is ready. Compose API/native worker are stopped; start SoundConnectApplication from IntelliJ." -ForegroundColor Green
        }
        "infra" {
            Assert-NoPlaceholders
            Invoke-Compose @("up", "--detach", "postgres", "rabbitmq", "redis")
            Invoke-Compose @("ps")
        }
        "boot" {
            Assert-NoPlaceholders
            Invoke-Compose @("up", "--detach", "postgres", "rabbitmq", "redis")
            Invoke-Compose @("stop", "backend", "media-worker")
            Wait-LocalComposeServicesHealthy @("postgres", "rabbitmq", "redis")
            if (-not (Test-LocalBaseSchemaReady)) {
                throw "Local base schema is empty. Run '.\dev.cmd up' once before '.\dev.cmd boot'."
            }
            [void](Sync-LocalSchemas)
            Import-DotEnv $EnvFile
            Set-NativePushEnvironment
            & (Join-Path $ProjectRoot "gradlew.bat") bootRun
            if ($LASTEXITCODE -ne 0) {
                throw "Gradle bootRun failed with exit code $LASTEXITCODE."
            }
        }
        "down" {
            Invoke-Compose @("down", "--remove-orphans")
        }
        "logs" {
            $Arguments = @("logs", "--follow", "--tail", "200")
            if ($Service) {
                $Arguments += $Service
            }
            Invoke-Compose $Arguments
        }
        "ps" {
            Invoke-Compose @("ps")
        }
        "config" {
            Invoke-Compose @("config", "--quiet")
            Write-Host "Compose configuration is valid." -ForegroundColor Green
        }
        "reset-local" {
            if (-not $Force) {
                throw "This permanently deletes local PostgreSQL, Redis, and RabbitMQ data. Run: .\dev.cmd reset-local -Force"
            }

            Assert-NoPlaceholders
            Invoke-Compose @("down", "--volumes", "--remove-orphans")
            Invoke-Compose @("up", "--detach", "postgres", "rabbitmq", "redis")
            Wait-LocalComposeServicesHealthy @("postgres", "rabbitmq", "redis")
            Invoke-LocalSchemaBootstrap
            if (-not (Test-LocalBaseSchemaReady)) {
                throw "The backend is healthy, but the required local base schema is incomplete."
            }
            [void](Sync-LocalSchemas)
            Invoke-Compose @("up", "--build", "--detach")
            Wait-LocalComposeServicesHealthy @("postgres", "rabbitmq", "redis", "backend", "media-worker")
            Invoke-Compose @("ps")
        }
    }
}
finally {
    Restore-LocalPushEnvironment
    Pop-Location
}
