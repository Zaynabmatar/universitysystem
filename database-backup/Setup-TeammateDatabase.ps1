<#
    One-click teammate setup for the University System project.

    What it does:
      1. Restores the .bak file found next to this script to a database
         named universitymanagementDB on YOUR SQL Server instance.
      2. Leaves your personal DB credentials/config alone -- it never writes to
         ~/.universitysystem/db.properties. If that file does not exist yet, it
         copies db.properties.example there as a starting point for you to edit.
      3. Never touches project code/git -- database only.
      4. Verifies the restore (row counts + presence of the audit triggers).
      5. Launches the app with `mvn javafx:run`.

    Usage (from anywhere):
        .\Setup-TeammateDatabase.ps1 -SqlInstance "localhost\SQLEXPRESS"

    Or, for a default (unnamed) instance / static port:
        .\Setup-TeammateDatabase.ps1 -SqlInstance "localhost"
        .\Setup-TeammateDatabase.ps1 -SqlInstance "localhost,1433"

    If your instance uses SQL auth (not Windows auth), add:
        -SqlUser sa -SqlPassword "yourpassword"
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$SqlInstance,

    [string]$SqlUser,
    [string]$SqlPassword,

    [string]$DatabaseName = "universitymanagementDB",

    [switch]$SkipRun
)

$ErrorActionPreference = "Stop"

$backupCandidates = @(Get-ChildItem -Path $PSScriptRoot -Filter *.bak -File)
if ($backupCandidates.Count -eq 0) {
    throw "No .bak file found in $PSScriptRoot. Make sure the database backup shipped alongside this script."
}
if ($backupCandidates.Count -gt 1) {
    throw "Multiple .bak files found in $PSScriptRoot ($(($backupCandidates | ForEach-Object Name) -join ', ')). Keep exactly one so the correct backup is unambiguous."
}
$backupFile = $backupCandidates[0].FullName
$projectRoot = Split-Path $PSScriptRoot -Parent

# Auth args shared by every sqlcmd call below: SQL auth if -SqlUser was given, else Windows auth.
$AuthArgs = if ($SqlUser) { @("-U", $SqlUser, "-P", $SqlPassword) } else { @("-E") }

function Invoke-Sql {
    param([string]$Query)
    & sqlcmd -S $SqlInstance -C -b @AuthArgs -Q $Query
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed (exit $LASTEXITCODE) running: $Query" }
}

function Invoke-SqlRaw {
    # Like Invoke-Sql but returns rows for parsing: no headers, pipe-delimited.
    param([string]$Query)
    $output = & sqlcmd -S $SqlInstance -C -h -1 -s "|" @AuthArgs -Q $Query
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed (exit $LASTEXITCODE) running: $Query" }
    return $output
}

Write-Host "==> Reading logical file names from the backup..." -ForegroundColor Cyan
$fileListRaw = Invoke-SqlRaw -Query "SET NOCOUNT ON; RESTORE FILELISTONLY FROM DISK = N'$backupFile'"
if (-not $fileListRaw) { throw "Could not read backup file list. Check -SqlInstance / credentials." }

$dataLogical = $null
$logLogical  = $null
foreach ($line in $fileListRaw) {
    $cols = $line -split '\|'
    if ($cols.Count -lt 3) { continue }
    $logicalName = $cols[0].Trim()
    $fileType    = $cols[2].Trim()
    if ($fileType -eq "D" -and -not $dataLogical) { $dataLogical = $logicalName }
    if ($fileType -eq "L" -and -not $logLogical)  { $logLogical  = $logicalName }
}
if (-not $dataLogical -or -not $logLogical) {
    throw "Could not determine logical file names from the backup header."
}
Write-Host "    data file: $dataLogical / log file: $logLogical"

Write-Host "==> Resolving this instance's default data/log directories..." -ForegroundColor Cyan
$pathsRaw = Invoke-SqlRaw -Query "SET NOCOUNT ON; SELECT CAST(SERVERPROPERTY('InstanceDefaultDataPath') AS NVARCHAR(500)) + '|' + CAST(SERVERPROPERTY('InstanceDefaultLogPath') AS NVARCHAR(500))"
$parts = ($pathsRaw | Where-Object { $_ -match '\|' } | Select-Object -First 1) -split '\|'
$dataDir = $parts[0].Trim()
$logDir  = $parts[1].Trim()
if (-not $dataDir -or -not $logDir) { throw "Could not resolve default data/log paths for $SqlInstance." }

$mdfPath = Join-Path $dataDir "$DatabaseName.mdf"
$ldfPath = Join-Path $logDir  "${DatabaseName}_log.ldf"

Write-Host "==> Restoring $DatabaseName from backup (this replaces any existing '$DatabaseName' database on THIS instance only)..." -ForegroundColor Cyan
$restoreQuery = @"
IF DB_ID(N'$DatabaseName') IS NOT NULL
BEGIN
    ALTER DATABASE [$DatabaseName] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
END
RESTORE DATABASE [$DatabaseName]
FROM DISK = N'$backupFile'
WITH REPLACE, RECOVERY,
     MOVE N'$dataLogical' TO N'$mdfPath',
     MOVE N'$logLogical'  TO N'$ldfPath',
     STATS = 10;
ALTER DATABASE [$DatabaseName] SET MULTI_USER;
"@
Invoke-Sql -Query $restoreQuery

Write-Host "==> Verifying the restored database..." -ForegroundColor Cyan
$verifyQuery = @"
SET NOCOUNT ON;
SELECT
    (SELECT COUNT(*) FROM dbo.users)      AS users,
    (SELECT COUNT(*) FROM dbo.grades)     AS grades,
    (SELECT COUNT(*) FROM dbo.audit_log)  AS audit_rows,
    (SELECT COUNT(*) FROM dbo.sections)   AS sections,
    (SELECT COUNT(*) FROM sys.triggers WHERE name = 'trg_Grade_Audit')   AS has_grade_audit_trigger,
    (SELECT COUNT(*) FROM sys.triggers WHERE name = 'trg_Section_Audit') AS has_section_audit_trigger;
"@
Invoke-Sql -Query $verifyQuery

Write-Host ""
Write-Host "==> Database config (kept separate from project code)" -ForegroundColor Cyan
$dbConfigDir = Join-Path $env:USERPROFILE ".universitysystem"
$dbConfigFile = Join-Path $dbConfigDir "db.properties"
$dbConfigExample = Join-Path $projectRoot "db.properties.example"
if (-not (Test-Path $dbConfigFile)) {
    New-Item -ItemType Directory -Force -Path $dbConfigDir | Out-Null
    Copy-Item $dbConfigExample $dbConfigFile
    Write-Host "    Created $dbConfigFile from the template." -ForegroundColor Yellow
    Write-Host "    Edit it now: set db.instance (or db.port), db.user/db.password for '$SqlInstance'." -ForegroundColor Yellow
    Write-Host "    This file lives outside the project folder and is never committed." -ForegroundColor Yellow
} else {
    Write-Host "    $dbConfigFile already exists -- left untouched." -ForegroundColor Green
}

if ($SkipRun) {
    Write-Host "==> Skipping app launch (-SkipRun was passed)." -ForegroundColor Cyan
    return
}

if (-not (Test-Path $dbConfigFile) -or (Get-Content $dbConfigFile -Raw) -match '<your SQL Server instance name>') {
    Write-Host ""
    Write-Host "==> $dbConfigFile still has template values. Edit it, then run:" -ForegroundColor Yellow
    Write-Host "        mvn javafx:run" -ForegroundColor Yellow
    Write-Host "    from $projectRoot" -ForegroundColor Yellow
    return
}

Write-Host "==> Launching the app (mvn javafx:run)..." -ForegroundColor Cyan
Push-Location $projectRoot
try {
    mvn javafx:run
} finally {
    Pop-Location
}
