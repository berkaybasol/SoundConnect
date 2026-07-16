[CmdletBinding()]
param(
    [string]$Bucket = $env:S3_PRIVATE_BUCKET,
    [switch]$Apply,
    [switch]$EnableProtectedUploadExpiry,
    [switch]$ConfirmLegacyProtectedMediaMigrated
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Bucket)) {
    throw 'S3_PRIVATE_BUCKET (or -Bucket) is required.'
}

if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
    throw 'AWS CLI v2 is required.'
}

if ($EnableProtectedUploadExpiry -and -not $ConfirmLegacyProtectedMediaMigrated) {
    throw @'
Refusing to enable the private-bucket media/ expiry rule. First migrate every
legacy READY private/unlisted asset from physical media/ to private-verified/,
verify the database storage keys, then re-run with both
-EnableProtectedUploadExpiry and -ConfirmLegacyProtectedMediaMigrated.
'@
}

$protectedRuleId = 'soundconnect-expire-abandoned-protected-uploads'
$managedIds = @(
    'soundconnect-expire-abandoned-quarantine',
    $protectedRuleId
)
$templatePath = Join-Path $PSScriptRoot 'private-media-lifecycle.json'
$desired = Get-Content -LiteralPath $templatePath -Raw | ConvertFrom-Json

# Preserve every lifecycle rule not owned by SoundConnect. The generated file
# is the complete configuration because put-bucket-lifecycle-configuration is
# a replace API, not a patch API.
$allExistingRules = @()
$existingRules = @()
$existingOutput = @(& aws s3api get-bucket-lifecycle-configuration --bucket $Bucket 2>&1)
$existingExitCode = $LASTEXITCODE
$existingRaw = $existingOutput -join [Environment]::NewLine
if ($existingExitCode -eq 0 -and $existingRaw) {
    $existing = $existingRaw | ConvertFrom-Json
    $allExistingRules = @($existing.Rules)
    $existingRules = @($allExistingRules | Where-Object { $_.ID -notin $managedIds })
}
elseif ($existingExitCode -ne 0 -and $existingRaw -notmatch 'NoSuchLifecycleConfiguration') {
    throw "Cannot read the existing lifecycle configuration: $existingRaw"
}

$desiredProtectedRule = @($desired.Rules | Where-Object { $_.ID -eq $protectedRuleId }) | Select-Object -First 1
if ($null -eq $desiredProtectedRule) {
    throw "Lifecycle template is missing managed rule '$protectedRuleId'."
}
$existingProtectedRule = @($allExistingRules | Where-Object { $_.ID -eq $protectedRuleId }) | Select-Object -First 1
if ($EnableProtectedUploadExpiry) {
    $desiredProtectedRule.Status = 'Enabled'
}
elseif ($null -ne $existingProtectedRule -and $existingProtectedRule.Status -eq 'Enabled') {
    # Once explicitly enabled, ordinary future runs must not silently disable it.
    $desiredProtectedRule.Status = 'Enabled'
}

$configuration = [ordered]@{
    Rules = @($existingRules) + @($desired.Rules)
}
$rendered = $configuration | ConvertTo-Json -Depth 20

if (-not $Apply) {
    Write-Host 'Dry run only. The merged lifecycle configuration is:'
    $rendered
    Write-Host "Re-run with -Apply to update private bucket '$Bucket'."
    exit 0
}

$temporaryFile = Join-Path ([System.IO.Path]::GetTempPath()) (
    'soundconnect-private-media-lifecycle-' + [guid]::NewGuid() + '.json'
)
try {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($temporaryFile, $rendered, $utf8NoBom)
    & aws s3api put-bucket-lifecycle-configuration `
        --bucket $Bucket `
        --lifecycle-configuration ('file://' + $temporaryFile)
    if ($LASTEXITCODE -ne 0) {
        throw 'AWS CLI rejected the lifecycle configuration.'
    }
    Write-Host "Private media lifecycle configured for '$Bucket'."
}
finally {
    Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
}
