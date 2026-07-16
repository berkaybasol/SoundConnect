[CmdletBinding()]
param(
    [string]$EnvFile = ".env.local"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ResolvedEnv = Join-Path $ProjectRoot $EnvFile

if (-not (Test-Path -LiteralPath $ResolvedEnv)) {
    throw "Compose env file not found: $ResolvedEnv"
}

$Rendered = & docker compose --project-directory $ProjectRoot --env-file $ResolvedEnv config --format json
if ($LASTEXITCODE -ne 0) {
    throw "docker compose config failed with exit code $LASTEXITCODE"
}
$Model = $Rendered | ConvertFrom-Json
$Api = $Model.services.backend
$Worker = $Model.services.'media-worker'
$DbRole = $Model.services.'media-worker-db-role'
$RabbitRole = $Model.services.'media-worker-rabbit-role'

function Assert-Equal([string]$Label, $Actual, $Expected) {
    if ($Actual -ne $Expected) {
        throw "$Label expected '$Expected' but was '$Actual'"
    }
}

function Assert-EnvironmentAbsent([string]$Label, $Service, [string[]]$Names) {
    $Present = @($Service.environment.PSObject.Properties.Name)
    foreach ($Name in $Names) {
        if ($Present -contains $Name) {
            throw "$Label must not receive $Name"
        }
    }
}

function Assert-EnvironmentPrefixAbsent([string]$Label, $Service, [string]$Prefix) {
    $Present = @($Service.environment.PSObject.Properties.Name | Where-Object { $_.StartsWith($Prefix, [System.StringComparison]::Ordinal) })
    if ($Present) {
        throw "$Label must not receive variables with prefix $Prefix"
    }
}

function Assert-EnvironmentPresent([string]$Label, $Service, [string[]]$Names) {
    foreach ($Name in $Names) {
        $Property = $Service.environment.PSObject.Properties[$Name]
        if ($null -eq $Property -or [string]::IsNullOrWhiteSpace([string]$Property.Value)) {
            throw "$Label must receive a non-empty $Name value"
        }
    }
}

function Assert-LoopbackPorts([string]$Label, $Service) {
    foreach ($Port in @($Service.ports)) {
        if ($Port.host_ip -ne "127.0.0.1") {
            throw "$Label published ports must bind only to 127.0.0.1"
        }
    }
}

Assert-Equal "API Docker target" $Api.build.target "api"
Assert-Equal "worker Docker target" $Worker.build.target "media-worker"
Assert-Equal "API HLS worker" $Api.environment.SOUNDCONNECT_MEDIA_TRANSCODE_WORKER_ENABLED "false"
Assert-Equal "API image worker" $Api.environment.SOUNDCONNECT_MEDIA_IMAGE_VARIANT_WORKER_ENABLED "false"
Assert-Equal "API dispatcher" $Api.environment.SOUNDCONNECT_MEDIA_TRANSCODE_DISPATCH_ENABLED "true"
Assert-Equal "worker HLS worker" $Worker.environment.SOUNDCONNECT_MEDIA_TRANSCODE_WORKER_ENABLED "true"
Assert-Equal "worker image worker" $Worker.environment.SOUNDCONNECT_MEDIA_IMAGE_VARIANT_WORKER_ENABLED "true"
Assert-Equal "worker dispatcher" $Worker.environment.SOUNDCONNECT_MEDIA_TRANSCODE_DISPATCH_ENABLED "false"
Assert-Equal "worker public ACL" $Worker.environment.SOUNDCONNECT_MEDIA_WORKER_S3_ACL_PUBLIC_READ_ON_PUT "false"
Assert-Equal "worker marker max age" $Worker.environment.SOUNDCONNECT_MEDIA_WORKER_HEALTH_MAX_AGE_SECONDS "60"
Assert-Equal "worker read-only root" $Worker.read_only $true

if ([string]::IsNullOrWhiteSpace($Worker.environment.SOUNDCONNECT_MEDIA_WORKER_CDN_BASE_URL)) {
    throw "Local media-worker must receive an explicit/fallback public CDN URL"
}

if ($null -ne $Worker.ports -and @($Worker.ports).Count -gt 0) {
    throw "media-worker must expose no ports"
}
if (@($Worker.cap_drop) -notcontains "ALL") {
    throw "media-worker must drop all Linux capabilities"
}
if (-not (@($Worker.security_opt) -contains "no-new-privileges:true")) {
    throw "media-worker must enable no-new-privileges"
}
if (-not (@($Worker.tmpfs) -match "noexec" -and @($Worker.tmpfs) -match "nosuid" -and @($Worker.tmpfs) -match "nodev")) {
    throw "media-worker /tmp must be noexec,nosuid,nodev"
}

Assert-EnvironmentPrefixAbsent "API" $Api "SOUNDCONNECT_MEDIA_WORKER_"
Assert-EnvironmentPresent "media-worker" $Worker @(
    "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME",
    "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD",
    "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME",
    "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD"
)
Assert-EnvironmentPresent "media-worker database role bootstrap" $DbRole @(
    "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME",
    "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD"
)
Assert-EnvironmentPresent "media-worker RabbitMQ role bootstrap" $RabbitRole @(
    "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME",
    "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD"
)
Assert-EnvironmentPrefixAbsent "media-worker database role bootstrap" $DbRole "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_"
Assert-EnvironmentPrefixAbsent "media-worker RabbitMQ role bootstrap" $RabbitRole "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_"
Assert-LoopbackPorts "PostgreSQL" $Model.services.postgres
Assert-LoopbackPorts "RabbitMQ" $Model.services.rabbitmq
Assert-LoopbackPorts "Redis" $Model.services.redis

Assert-EnvironmentAbsent "media-worker" $Worker @(
    "SOUNDCONNECT_JWT_SECRETKEY",
    "MAILERSEND_API_KEY",
    "GOOGLE_CLIENT_ID",
    "GOOGLE_CLIENT_SECRET",
    "SPOTIFY_CLIENT_ID",
    "SPOTIFY_CLIENT_SECRET",
    "SOUNDCONNECT_POSTGRES_PASSWORD",
    "SPRING_RABBITMQ_PASSWORD",
    "S3_ACCESS_KEY",
    "S3_SECRET_KEY",
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_PROFILE",
    "SOUNDCONNECT_POSTGRES_URL",
    "SOUNDCONNECT_POSTGRES_USERNAME",
    "SOUNDCONNECT_POSTGRES_PASSWORD",
    "SOUNDCONNECT_POSTGRE_URL",
    "SOUNDCONNECT_POSTGRE_USERNAME",
    "SOUNDCONNECT_POSTGRE_PASSWORD",
    "SPRING_DATASOURCE_URL",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "SPRING_DATASOURCE_JNDI_NAME",
    "SPRING_DATASOURCE_HIKARI_JDBC_URL",
    "SPRING_DATASOURCE_HIKARI_USERNAME",
    "SPRING_DATASOURCE_HIKARI_PASSWORD",
    "SPRING_RABBITMQ_ADDRESSES",
    "SPRING_RABBITMQ_USERNAME",
    "SPRING_RABBITMQ_PASSWORD",
    "SPRING_RABBITMQ_VIRTUAL_HOST",
    "SPRING_RABBITMQ_SSL_KEY_STORE",
    "SPRING_RABBITMQ_SSL_KEY_STORE_PASSWORD",
    "SPRING_RABBITMQ_SSL_TRUST_STORE",
    "SPRING_RABBITMQ_SSL_TRUST_STORE_PASSWORD",
    "SPRING_DATA_REDIS_URL",
    "SPRING_DATA_REDIS_USERNAME",
    "SPRING_DATA_REDIS_PASSWORD",
    "SPRING_REDIS_URL",
    "SPRING_REDIS_USERNAME",
    "SPRING_REDIS_PASSWORD",
    "REDIS_URL",
    "REDIS_USERNAME",
    "REDIS_PASSWORD"
)

$ComposeSource = Get-Content -LiteralPath (Join-Path $ProjectRoot "compose.yaml") -Raw
if ($ComposeSource -match "media-worker-local-only") {
    throw "Compose must not contain a hard-coded media-worker credential fallback"
}

Write-Host "Media-worker Compose capability boundary is valid." -ForegroundColor Green
