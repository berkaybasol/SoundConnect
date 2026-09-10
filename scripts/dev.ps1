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
    @{ Name = "Band member titles"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-06-band-member-titles.sql" },
    @{ Name = "Band invitation identity"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-07-band-invitation-identity.sql" },
    @{ Name = "Notification replay receipts"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-07-notification-replay-receipts.sql" },
    @{ Name = "Venue analytics"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-08-venue-analytics.sql" },
    @{ Name = "Event audience intents"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-08-event-audience-intents.sql" },
    @{ Name = "Comment likes"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-09-comment-likes.sql" },
    @{ Name = "Event post comments"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-09-event-post-comments.sql" },
    @{ Name = "Overthinking lifecycle"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-09-overthinking-lifecycle.sql" },
    @{ Name = "Overthinking notification outbox"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-09-overthinking-notification-outbox.sql" },
    @{ Name = "Overthinking inbox seen"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-overthinking-inbox-seen.sql" },
    @{ Name = "Overthinking profile shares"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-overthinking-profile-shares.sql" },
    @{ Name = "Overthinking production safety"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-overthinking-production-safety.sql" },
    @{ Name = "Listener account erasure"; Path = Join-Path $ProjectRoot "scripts\db\2026-09-10-listener-account-erasure.sql" }
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

function Invoke-Compose([string[]]$Arguments) {
    & docker compose --project-directory $ProjectRoot --env-file $EnvFile @Arguments
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
    $Output = & docker compose `
        --project-directory $ProjectRoot `
        --env-file $EnvFile `
        exec -T postgres psql `
        -X -qAt `
        -U $Connection.Username `
        -d $Connection.Database `
        -c $Query 2>$null
    return $LASTEXITCODE -eq 0 -and (($Output -join "").Trim() -eq "t")
}

function Test-LocalComposeServiceHealthy([string]$Service) {
    $ContainerIds = & docker compose `
        --project-directory $ProjectRoot `
        --env-file $EnvFile `
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

    & docker compose --project-directory $ProjectRoot --env-file $EnvFile ps
    throw "Services did not become healthy within ${TimeoutSeconds}s: $($Services -join ', ')"
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
        Write-Host "Applying local $($Migration.Name) migration..." -ForegroundColor Cyan
        Get-Content -Raw -LiteralPath $Migration.Path | & docker compose `
            --project-directory $ProjectRoot `
            --env-file $EnvFile `
            exec -T postgres psql `
            -X -v ON_ERROR_STOP=1 `
            -U $Connection.Username `
            -d $Connection.Database
        if ($LASTEXITCODE -ne 0) {
            throw "$($Migration.Name) migration failed with exit code $LASTEXITCODE."
        }
    }
    Write-Host "Local Studio, Collab, TableGroup, listener-profile, event-consent, performer-calendar, and event-publication schemas are ready." -ForegroundColor Green
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

Assert-Command "docker"
Assert-LocalEnv
Assert-ApiEnvironmentBoundary
Initialize-WorkerCredentials

Push-Location $ProjectRoot
try {
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
                Invoke-Compose @("up", "--build", "--detach", "backend")
                Wait-LocalComposeServicesHealthy @("backend")
                Invoke-Compose @("stop", "backend")
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
            Invoke-Compose @("up", "--build", "--detach", "postgres", "rabbitmq", "redis", "backend")
            Wait-LocalComposeServicesHealthy @("postgres", "rabbitmq", "redis", "backend")
            if (-not (Test-LocalBaseSchemaReady)) {
                throw "The backend is healthy, but the required local base schema is incomplete."
            }
            Invoke-Compose @("stop", "backend")
            [void](Sync-LocalSchemas)
            Invoke-Compose @("up", "--build", "--detach")
            Wait-LocalComposeServicesHealthy @("postgres", "rabbitmq", "redis", "backend", "media-worker")
            Invoke-Compose @("ps")
        }
    }
}
finally {
    Pop-Location
}
