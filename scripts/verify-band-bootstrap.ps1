#requires -Version 7.0
param([Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ContainerId)
# Adapter for the real dev.ps1 registry/selection/execution helpers. It never
# sources startup, imports .env.local, or calls Compose against the shared stack.
$ErrorActionPreference='Stop'
$ProjectRoot=Split-Path -Parent $PSScriptRoot
$DockerExe=(Get-Command docker -CommandType Application|Select-Object -First 1).Source
$database='bil007_band_startup'
$owner='bil007-band-startup'
function Assert-Owned {
 $raw=& $DockerExe inspect $ContainerId
 if($LASTEXITCODE -ne 0){throw 'Fixture container unavailable'}
 $info=($raw|ConvertFrom-Json)[0]
 if($info.Id -cne $ContainerId -or $info.Config.Labels.'soundconnect.fixture' -cne $owner -or -not $info.State.Running){throw 'Not the owned BAND fixture'}
}
Assert-Owned
$source=Get-Content "$PSScriptRoot/dev.ps1" -Raw
$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$null,[ref]$errors)
if($errors.Count){throw 'dev.ps1 parse error'}
foreach($name in @('Invoke-LocalSchemaQuery','Test-LocalMigrationApplied','Sync-LocalSchemas')){
 $defs=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)|Where-Object Name -CEQ $name)
 if($defs.Count -ne 1){throw "Missing/duplicate helper: $name"}
 . ([scriptblock]::Create($defs[0].Extent.Text))
}
$registry=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst]},$true)|Where-Object {$_.Left.Extent.Text -ceq '$LocalSchemaMigrations'})
if($registry.Count -ne 1){throw 'Missing/duplicate production registry'}
. ([scriptblock]::Create($registry[0].Extent.Text))
$expected=@('2026-09-28-media-notification-identity','2026-09-29-band-notification-identity')
$all=$LocalSchemaMigrations
$LocalSchemaMigrations=@($all|Where-Object {$_.Marker -cin $expected})
if(($LocalSchemaMigrations.Marker -join ',') -cne ($expected -join ',')){throw 'Missing/duplicate/wrong-order identity registry entries'}
foreach($step in $LocalSchemaMigrations){if([IO.Path]::GetFileNameWithoutExtension($step.Path) -cne $step.Marker){throw 'Wrong registry marker/file pair'}}
$script:applied=[Collections.Generic.List[string]]::new()
function Get-ComposeArguments { return ,@('--bil007-owned') }
function Get-LocalPostgresConnection { return @{Username='fixture';Database=$database} }
function Test-LocalBaseSchemaReady {
 return (Invoke-LocalSchemaQuery "select current_database()='$database' and to_regclass('tbl_notification') is not null;") -ceq 't'
}
function Assert-LocalPushSchemaReady {
 # This adapter selects only the two identity migrations; full capability order
 # is covered separately by verify-push-migrations.ps1, using the same helpers.
 foreach($migration in $LocalSchemaMigrations){if(-not (Test-LocalMigrationApplied $migration)){throw 'Identity marker missing after real SQL'}}
}
function docker {
 $tokens=@($args);$sql=@($input)-join "`n"
 if($tokens.Count -lt 7 -or ($tokens[0..5]-join ' ') -cne 'compose --bil007-owned exec -T postgres psql'){throw 'Forbidden fixture command'}
 Assert-Owned
 if($sql){
  $match=@($LocalSchemaMigrations|Where-Object {(Get-Content $_.Path -Raw).Trim() -ceq $sql.Trim()})
  if($match.Count -ne 1){throw 'Unexpected migration SQL'}
  $script:applied.Add($match[0].Marker)
  $sql|& $DockerExe exec -i $ContainerId psql @($tokens[6..($tokens.Count-1)])
 }else{& $DockerExe exec $ContainerId psql @($tokens[6..($tokens.Count-1)])}
 $global:LASTEXITCODE=$LASTEXITCODE
}
Write-Output 'BIL007_REGISTRY_START'
[void](Sync-LocalSchemas)
Write-Output ('BIL007_REGISTRY_PASS applied='+($script:applied -join ','))
