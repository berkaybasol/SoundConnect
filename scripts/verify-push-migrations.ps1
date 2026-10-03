[CmdletBinding()]
param()

# Real PostgreSQL regression of the launcher's migration helpers, not a local startup.
# Never reads .env.local/ADC, executes dev.ps1's startup block, or uses Compose.
# Only the 14 push/source SQL steps through V10 run against minimal, disposable base tables.
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$DockerExe = (Get-Command docker -CommandType Application | Select-Object -First 1).Source
$FixtureName = "soundconnect-push-migrations-" + [Guid]::NewGuid().ToString("N")
$FixtureContainerId = $null
$FixtureDatabase = "postgres"
$FixtureRoot = Join-Path ([IO.Path]::GetTempPath()) $FixtureName
$StderrFile = Join-Path $FixtureRoot "postgres-stderr.txt"
$Checks = 0
$Cases = New-Object 'System.Collections.Generic.List[string]'
$AppliedSteps = New-Object 'System.Collections.Generic.List[string]'
$Image = "postgres:16.4-alpine"

function Assert-Check([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw "Push migration verification failed: $Label" }
    $script:Checks++
}

function Assert-Rejected([scriptblock]$Operation, [string]$ExpectedMessage, [string]$Label) {
    $Rejected = $false
    try { & $Operation | Out-Null }
    catch { $Rejected = $_.Exception.Message -like "*$ExpectedMessage*"; if (-not $Rejected) { Write-Host "Unexpected fixture rejection: $($_.Exception.Message)" } }
    Assert-Check $Rejected $Label
}

function Invoke-FixturePsql([string[]]$PsqlArguments, [string]$Sql = "") {
    if ($FixtureContainerId -notmatch '^[a-f0-9]{64}$') { throw "Fixture container identity is unavailable." }
    $PreviousPreference = $ErrorActionPreference
    try {
        # Native stderr is retained in the private fixture for expected rejection tests.
        $ErrorActionPreference = "Continue"
        if ($Sql) {
            $Output = $Sql | & $DockerExe exec -i -e "PGOPTIONS=-c client_min_messages=warning" $FixtureContainerId psql -q -v VERBOSITY=verbose @PsqlArguments 2>$StderrFile
        }
        else {
            $Output = & $DockerExe exec -i -e "PGOPTIONS=-c client_min_messages=warning" $FixtureContainerId psql -q -v VERBOSITY=verbose @PsqlArguments 2>$StderrFile
        }
        $ExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $PreviousPreference }
    $global:LASTEXITCODE = $ExitCode
    return $Output
}

function Invoke-FixtureSql([string]$Sql) {
    $Result = Invoke-FixturePsql @("-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", $FixtureDatabase) $Sql
    if ($LASTEXITCODE -ne 0) { throw "Fixture SQL failed: $(Get-Content -LiteralPath $StderrFile -Raw)" }
    return ($Result -join "").Trim()
}

# The production helpers still construct their own psql calls. This narrow adapter
# refuses every command except psql against the owned disposable container.
function docker {
    $Command = @($args)
    $PipelineText = @($input) -join "`n"
    if ($Command.Count -lt 7 -or ($Command[0..5] -join " ") -cne "compose --fixture-only exec -T postgres psql") {
        throw "Forbidden Docker command in migration fixture: count=$($Command.Count), prefix=$($Command[0..([Math]::Min(5,$Command.Count-1))] -join ' ')."
    }
    if ($PipelineText) {
        $Matched = @($LocalSchemaMigrations | Where-Object { (Get-Content -LiteralPath $_.Path -Raw).Trim() -ceq $PipelineText.Trim() })
        if ($Matched.Count -ne 1) { throw "Unexpected SQL pipeline in migration fixture." }
        $AppliedSteps.Add([IO.Path]::GetFileName($Matched[0].Path))
    }
    Invoke-FixturePsql $Command[6..($Command.Count - 1)] $PipelineText
}

function Get-LocalPostgresConnection { return @{ Username = "postgres"; Database = $FixtureDatabase } }
function Get-ComposeArguments { return ,@("--fixture-only") }

function New-FixtureDatabase([string]$Name) {
    if ($Name -notmatch '^case_[a-z0-9_]+$') { throw "Unsafe fixture database name." }
    $script:FixtureDatabase = "postgres"
    [void](Invoke-FixtureSql "CREATE DATABASE $Name;")
    $script:FixtureDatabase = $Name
    [void](Invoke-FixtureSql @"
CREATE TABLE tbl_user(id uuid PRIMARY KEY, erased_at timestamptz);
CREATE TABLE tbl_studio_profile(id uuid PRIMARY KEY);
CREATE TABLE tbl_media_asset(id uuid PRIMARY KEY);
CREATE TABLE tbl_role(id uuid PRIMARY KEY);
CREATE TABLE tbl_permissions(id uuid PRIMARY KEY);
CREATE TABLE tbl_notification(id uuid PRIMARY KEY, type varchar(80) CHECK(type IN ('MESSAGE_RECEIVED')));
CREATE TABLE tbl_venues(id uuid PRIMARY KEY);
CREATE TABLE tbl_venue_applications(id uuid PRIMARY KEY, user_id uuid, application_date timestamptz, created_at timestamptz);
CREATE TABLE tbl_table_group_participants(id uuid PRIMARY KEY);
CREATE TABLE tbl_table_group_notification_outbox(
    event_id uuid PRIMARY KEY, recipient_id uuid NOT NULL, notification_type varchar(64) NOT NULL,
    payload jsonb NOT NULL, occurred_at timestamptz NOT NULL);
INSERT INTO tbl_user VALUES ('10000000-0000-4000-8000-000000000001',NULL);
INSERT INTO tbl_table_group_notification_outbox VALUES (
    '40000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001',
    'TABLE_JOIN_REQUEST_RECEIVED','{"tableId":"50000000-0000-4000-8000-000000000001"}','2026-10-03T00:00:00Z');
"@)
    $AppliedSteps.Clear()
}

function Apply-Prefix([int]$Length) {
    foreach ($Migration in @($LocalSchemaMigrations | Where-Object { $_.Marker } | Select-Object -First $Length)) {
        [void](Invoke-FixtureSql (Get-Content -LiteralPath $Migration.Path -Raw))
    }
}

function Add-Device([string]$Version, [int]$Suffix, [switch]$Scoped, [switch]$Revoked) {
    $Id = "20000000-0000-4000-8000-{0:D12}" -f $Suffix
    $ScopeColumn = ""
    $ScopeValue = ""
    if ($Scoped) {
        $ScopeColumn = ",application_scope_id"
        $ScopeValue = ",'30000000-0000-4000-8000-000000000001'"
    }
    $RevokedValue = if ($Revoked) { "'2026-09-27T00:00:00Z'" } else { "NULL" }
    [void](Invoke-FixtureSql @"
INSERT INTO tbl_push_device(installation_id,user_id,generation,platform,permission,last_seen_at,
    client_revision,presentation_version,revoked_at$ScopeColumn)
VALUES('$Id','10000000-0000-4000-8000-000000000001',3,'ANDROID','AUTHORIZED',
    '2026-09-27T00:00:00Z',17,'$Version',$RevokedValue$ScopeValue);
"@)
}

function Get-DeviceSnapshot {
    return Invoke-FixtureSql "SELECT coalesce(jsonb_agg((jsonb_build_object('application_scope_id',NULL) || to_jsonb(d)) ORDER BY installation_id)::text,'[]') FROM tbl_push_device d;"
}

function Assert-ReadyShape {
    Assert-LocalPushSchemaReady
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM soundconnect_schema_migrations;") -eq "12") "all 12 forward markers exist"
    [void](Invoke-FixtureSql "SELECT enabled,disabled_categories FROM tbl_push_preference LIMIT 0; SELECT status,expires_at,lease_until,finished_at FROM tbl_push_delivery LIMIT 0;")
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='tbl_table_group_participants' AND column_name='application_id' AND data_type='uuid';") -eq "1") "TABLE application identity column exists"
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM pg_trigger WHERE tgrelid='tbl_table_group_notification_outbox'::regclass AND tgname='trg_table_notification_event' AND tgenabled='O';") -eq "1") "TABLE source capture trigger is enabled"
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM tbl_table_notification_event e JOIN tbl_table_group_notification_outbox o USING(event_id) WHERE e.recipient_id=o.recipient_id AND e.notification_type=o.notification_type AND e.payload=o.payload AND e.occurred_at=o.occurred_at;") -eq (Invoke-FixtureSql "SELECT count(*) FROM tbl_table_group_notification_outbox;")) "TABLE source evidence is backfilled exactly"
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='tbl_studio_reservation_notification_outbox';") -eq "17") "outbox has 17 columns"
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM pg_constraint WHERE conrelid='tbl_studio_reservation_notification_outbox'::regclass AND contype='c' AND convalidated;") -eq "11") "outbox has 11 validated checks"
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM pg_indexes WHERE tablename='tbl_studio_reservation_notification_outbox' AND indexname LIKE 'idx_%';") -eq "4") "outbox has four indexes"
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='tbl_follow_notification_outbox';") -eq "16") "follow outbox has 16 columns"
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM pg_constraint WHERE conrelid='tbl_follow_notification_outbox'::regclass AND contype='c' AND convalidated;") -eq "7") "follow outbox has seven validated checks"
}

$Source = Get-Content -LiteralPath (Join-Path $ProjectRoot "scripts/dev.ps1") -Raw
$ParseErrors = $null
$Ast = [System.Management.Automation.Language.Parser]::ParseInput($Source, [ref]$null, [ref]$ParseErrors)
Assert-Check ($ParseErrors.Count -eq 0) "dev.ps1 parses"
foreach ($Name in @("Invoke-LocalSchemaQuery", "Test-LocalMigrationApplied", "Assert-LocalPushSchemaReady", "Test-LocalBaseSchemaReady", "Sync-LocalSchemas")) {
    $Definition = $Ast.FindAll({ param($Node) $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true) |
        Where-Object { $_.Name -eq $Name } | Select-Object -First 1
    Assert-Check ($null -ne $Definition) "production helper exists: $Name"
    . ([scriptblock]::Create($Definition.Extent.Text))
}
$Registry = $Ast.FindAll({ param($Node) $Node -is [System.Management.Automation.Language.AssignmentStatementAst] }, $true) |
    Where-Object { $_.Left.Extent.Text -eq '$LocalSchemaMigrations' } | Select-Object -First 1
Assert-Check ($null -ne $Registry) "production migration registry exists"
. ([scriptblock]::Create($Registry.Extent.Text))
$ExpectedFiles = @(
    "2026-09-22-push-delivery-foundation.sql",
    "2026-09-23-push-device-registration-revision.sql",
    "2026-09-24-push-native-venue-capability.sql",
    "2026-09-24-venue-application-notifications.sql",
    "2026-09-24-studio-reservation-notification-outbox.sql",
    "2026-09-27-follow-notification-outbox.sql",
    "2026-09-24-push-native-studio-capability.sql",
    "2026-09-28-push-native-follow-capability.sql",
    "2026-09-29-push-native-media-capability.sql",
    "2026-09-29-push-native-band-capability.sql",
    "2026-09-30-table-notification-target.sql",
    "2026-10-01-push-native-table-capability.sql",
    "2026-10-01-push-native-collab-capability.sql",
    "2026-10-01-push-native-overthinking-capability.sql"
)
$LocalSchemaMigrations = @($LocalSchemaMigrations | Where-Object { [IO.Path]::GetFileName($_.Path) -in $ExpectedFiles })
Assert-Check ((@($LocalSchemaMigrations | ForEach-Object { [IO.Path]::GetFileName($_.Path) }) -join ',') -ceq ($ExpectedFiles -join ',')) "each push migration occurs once in monotonic order"
Assert-Check (@($LocalSchemaMigrations | Where-Object { $_.Marker }).Count -eq 12) "12 capability/source steps carry markers"
Assert-Check (-not $LocalSchemaMigrations[4].Marker) "outbox remains repeatable"
Assert-Check (-not $LocalSchemaMigrations[5].Marker) "follow outbox is independent of the 12 push/source markers"
$SqlHashesBefore = @($LocalSchemaMigrations | ForEach-Object { (Get-FileHash -LiteralPath $_.Path -Algorithm SHA256).Hash }) -join ','

try {
    [void](New-Item -ItemType Directory -Path $FixtureRoot)
    Assert-Check ([string]::IsNullOrWhiteSpace($env:DOCKER_HOST)) "no external Docker host override"
    Assert-Check ([string]::IsNullOrWhiteSpace($env:DOCKER_CONTEXT)) "no external Docker context override"
    $Context = & $DockerExe context show
    Assert-Check ($Context -in @("default", "desktop-linux")) "local Docker context"
    $ImageId = & $DockerExe image inspect $Image --format '{{.Id}}'
    Assert-Check ($LASTEXITCODE -eq 0) "PostgreSQL fixture image already available (no pull)"
    $FixtureContainerId = (& $DockerExe run --detach --rm --network none --tmpfs /var/lib/postgresql/data --label "soundconnect.fixture=$FixtureName" --name $FixtureName --env POSTGRES_HOST_AUTH_METHOD=trust $Image).Trim()
    Assert-Check ($LASTEXITCODE -eq 0 -and $FixtureContainerId -match '^[a-f0-9]{64}$') "created owned isolated PostgreSQL"
    $Ready = $false
    for ($Try = 0; $Try -lt 30; $Try++) {
        & $DockerExe exec $FixtureContainerId pg_isready -U postgres 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $Ready = $true; break }
        Start-Sleep -Seconds 1
    }
    Assert-Check $Ready "disposable PostgreSQL is ready"
    $Isolation = (& $DockerExe inspect $FixtureContainerId | ConvertFrom-Json)[0]
    Assert-Check ($Isolation.HostConfig.NetworkMode -eq "none") "fixture has no network"
    Assert-Check (@($Isolation.Mounts | Where-Object { $_.Type -ne "tmpfs" }).Count -eq 0) "fixture has no host or persistent data mount"
    Assert-Check (@($Isolation.HostConfig.PortBindings.PSObject.Properties).Count -eq 0) "fixture has no published ports"

    New-FixtureDatabase "case_fresh"
    Assert-Check (Sync-LocalSchemas) "fresh database migrates"
    Assert-Check (($AppliedSteps -join ',') -ceq ($ExpectedFiles -join ',')) "fresh database executes each forward step in order"
    Assert-ReadyShape
    [void](Invoke-FixtureSql @"
INSERT INTO tbl_table_group_notification_outbox VALUES (
    '40000000-0000-4000-8000-000000000002','10000000-0000-4000-8000-000000000001',
    'TABLE_EXPIRED','{"tableId":"50000000-0000-4000-8000-000000000001"}','2026-10-03T00:01:00Z');
"@)
    Assert-Check ((Invoke-FixtureSql "SELECT count(*) FROM tbl_table_notification_event;") -eq "2") "TABLE trigger captures new source events after migration"
    $Cases.Add("Fresh minimal base schema -> V10, repeatable outboxes and TABLE source capture")

    Add-Device "ANDROID_DM_V1" 1
    Add-Device "ANDROID_NATIVE_V2" 2
    Add-Device "ANDROID_NATIVE_V3" 3
    Add-Device "ANDROID_NATIVE_V4" 4
    Add-Device "ANDROID_NATIVE_V3" 5 -Scoped
    Add-Device "ANDROID_NATIVE_V4" 6 -Scoped
    Add-Device "ANDROID_NATIVE_V4" 7 -Revoked
    Add-Device "ANDROID_NATIVE_V5" 9
    Add-Device "ANDROID_NATIVE_V5" 10 -Scoped
    foreach ($Version in 6..10) {
        Add-Device "ANDROID_NATIVE_V$Version" ($Version * 10 + 1)
        Add-Device "ANDROID_NATIVE_V$Version" ($Version * 10 + 2) -Scoped
        Add-Device "ANDROID_NATIVE_V$Version" ($Version * 10 + 3) -Revoked
    }
    $Before = Get-DeviceSnapshot
    $MarkersBefore = Invoke-FixtureSql "SELECT jsonb_agg(to_jsonb(m) ORDER BY migration_id)::text FROM soundconnect_schema_migrations m;"
    $ConstraintsBefore = Invoke-FixtureSql "SELECT string_agg(pg_get_constraintdef(oid),';' ORDER BY conname) FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass;"
    for ($Replay = 1; $Replay -le 2; $Replay++) {
        $AppliedSteps.Clear()
        Assert-Check (Sync-LocalSchemas) "V10 replay $Replay succeeds"
        Assert-Check (($AppliedSteps -join ',') -ceq ($ExpectedFiles[4..5] -join ',')) "V10 replay $Replay skips all marked capability/source steps"
        Assert-Check ((Get-DeviceSnapshot) -ceq $Before) "V10 replay $Replay preserves all 24 device rows exactly"
        Assert-Check ((Invoke-FixtureSql "SELECT jsonb_agg(to_jsonb(m) ORDER BY migration_id)::text FROM soundconnect_schema_migrations m;") -ceq $MarkersBefore) "V10 replay $Replay preserves marker timestamps"
        Assert-Check ((Invoke-FixtureSql "SELECT string_agg(pg_get_constraintdef(oid),';' ORDER BY conname) FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass;") -ceq $ConstraintsBefore) "V10 replay $Replay preserves constraints"
    }
    Assert-ReadyShape
    $Cases.Add("Existing ordinary/scoped/revoked V10 and legacy devices -> two exact-preserving replays")

    # Demonstrate the reported regression itself, on disposable rows only.
    Assert-Rejected { Invoke-FixtureSql (Get-Content -LiteralPath $LocalSchemaMigrations[1].Path -Raw) } "23514" "historical V1 replay rejects current device rows"
    Assert-Check ((Get-DeviceSnapshot) -ceq $Before) "failed historical replay rolls back without row changes"
    Assert-LocalPushSchemaReady
    Assert-Rejected { Invoke-FixtureSql (Get-Content -LiteralPath $LocalSchemaMigrations[12].Path -Raw) } "23514" "historical V9 replay rejects a current V10 row"
    Assert-Check ((Get-DeviceSnapshot) -ceq $Before) "failed V9 replay rolls back without row changes"
    Assert-Check ((Invoke-FixtureSql "SELECT string_agg(pg_get_constraintdef(oid),';' ORDER BY conname) FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass;") -ceq $ConstraintsBefore) "failed historical replays preserve V10 constraints"
    Assert-LocalPushSchemaReady
    $Cases.Add("Historical V1 and V9 replay on current devices -> rejection and transactional rollback")

    # TABLE source identity is its own marker between V7 and V8, so marker
    # count no longer equals capability version + 1 after that forward step.
    $PrefixVersions = @(1, 2, 3, 4, 5, 6, 7, 7, 8, 9)
    foreach ($Prefix in 2..11) {
        New-FixtureDatabase "case_prefix_$Prefix"
        Apply-Prefix $Prefix
        $Capability = $PrefixVersions[$Prefix - 2]
        $Version = if ($Capability -eq 1) { "ANDROID_DM_V1" } else { "ANDROID_NATIVE_V$Capability" }
        Add-Device $Version $Prefix
        if ($Prefix -eq 4) { Add-Device "ANDROID_NATIVE_V3" 8 -Scoped }
        $BeforePrefix = Get-DeviceSnapshot
        Assert-Check (Sync-LocalSchemas) "existing V$Capability with $Prefix markers upgrades forward"
        foreach ($Skipped in 0..($Prefix - 1)) {
            $MarkerFile = @($LocalSchemaMigrations | Where-Object { $_.Marker })[$Skipped].Path
            Assert-Check (-not $AppliedSteps.Contains([IO.Path]::GetFileName($MarkerFile))) "upgrade skips existing marker $Skipped"
        }
        Assert-Check ((Get-DeviceSnapshot) -ceq $BeforePrefix) "upgrade preserves existing V$Capability device values"
        Assert-ReadyShape
        $Cases.Add("Existing V$Capability / $Prefix-marker prefix and device rows -> forward-only V10 upgrade")
    }

    New-FixtureDatabase "case_presentation_drift"
    [void](Sync-LocalSchemas)
    [void](Invoke-FixtureSql "ALTER TABLE tbl_push_device DROP CONSTRAINT ck_push_device_presentation; ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_presentation CHECK(presentation_version IS NULL OR presentation_version='ANDROID_DM_V1');")
    Assert-Rejected { Sync-LocalSchemas } "Local push schema does not satisfy" "marker plus narrowed presentation fails readiness"
    $Cases.Add("All markers but narrowed presentation CHECK -> explicit readiness failure")

    New-FixtureDatabase "case_scope_drift"
    [void](Sync-LocalSchemas)
    [void](Invoke-FixtureSql "ALTER TABLE tbl_push_device DROP CONSTRAINT ck_push_device_application_scope; ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_application_scope CHECK(application_scope_id IS NULL OR platform='ANDROID');")
    Assert-Rejected { Sync-LocalSchemas } "Local push schema does not satisfy" "marker plus relaxed scope fails readiness"
    $Cases.Add("All markers but malformed application-scope CHECK -> explicit readiness failure")

    New-FixtureDatabase "case_column_drift"
    [void](Sync-LocalSchemas)
    [void](Invoke-FixtureSql "ALTER TABLE tbl_venue_applications DROP COLUMN approved_venue_id;")
    Assert-Rejected { Sync-LocalSchemas } "Local schema verification failed" "marker plus missing prerequisite column propagates SQL failure"
    $Cases.Add("All markers but missing approved-venue column -> fail closed on psql error")

    New-FixtureDatabase "case_missing_current_marker"
    [void](Sync-LocalSchemas)
    [void](Invoke-FixtureSql "DELETE FROM soundconnect_schema_migrations WHERE migration_id='2026-10-01-push-native-overthinking-capability';")
    Assert-Rejected { Assert-LocalPushSchemaReady } "Local push schema does not satisfy" "current shape without V10 marker fails readiness"
    $Cases.Add("Valid V10 shape but missing latest marker -> explicit readiness failure")

    Assert-Rejected { Test-LocalMigrationApplied @{ Marker = "not-a-marker'" } } "Invalid local migration marker" "marker interpolation accepts only registry format"
    $SqlHashesAfter = @($LocalSchemaMigrations | ForEach-Object { (Get-FileHash -LiteralPath $_.Path -Algorithm SHA256).Hash }) -join ','
    Assert-Check ($SqlHashesAfter -ceq $SqlHashesBefore) "all 14 dated SQL files remain byte-identical"
    Write-Host "PASS: $($Cases.Count) PostgreSQL scenarios, $Checks assertions."
    $Cases | ForEach-Object { Write-Host "  PASS $_" }
    Write-Host "Fixture image: $ImageId"
}
finally {
    if ($FixtureContainerId) {
        $Owned = (& $DockerExe inspect $FixtureContainerId | ConvertFrom-Json)[0]
        if ($Owned.Id -cne $FixtureContainerId -or $Owned.Name -cne "/$FixtureName" -or $Owned.Config.Labels.'soundconnect.fixture' -cne $FixtureName) {
            throw "Refusing to remove a container without the exact fixture identity and label."
        }
        & $DockerExe rm --force $FixtureContainerId | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Disposable PostgreSQL cleanup failed." }
        Write-Host "Disposable PostgreSQL removed; no persistent fixture data remains."
    }
    if (Test-Path -LiteralPath $FixtureRoot) {
        $ResolvedRoot = [IO.Path]::GetFullPath($FixtureRoot)
        $TempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
        if (-not $ResolvedRoot.StartsWith($TempRoot,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $ResolvedRoot) -notmatch '^soundconnect-push-migrations-[a-f0-9]{32}$') {
            throw "Unsafe temporary fixture cleanup path."
        }
        Remove-Item -LiteralPath $ResolvedRoot -Recurse -Force
    }
}
