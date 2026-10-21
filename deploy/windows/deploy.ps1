<#
.SYNOPSIS
    Installs or updates Organizer on this Windows machine.

.DESCRIPTION
    Copies the jar to the deploy folder and runs it as a scheduled task that starts with Windows,
    as the SYSTEM account. Your data (data\organizer.db and the AI model in data\models) stays in the
    deploy folder between updates, and the database is backed up before every update.

    Run from an elevated (administrator) PowerShell. The Jenkinsfile calls this script; you can also
    run it by hand:

        .\gradlew.bat buildFatJar
        powershell -ExecutionPolicy Bypass -File deploy\windows\deploy.ps1 -Jar build\libs\organizer.jar

    To protect the app with a password, set $env:ORGANIZER_PASSWORD before running the script. It is
    read from the environment so that it never appears in command lines or build logs.

    -Stop stops the app until the next restart of Windows (Start-ScheduledTask Organizer starts it
    again). -Uninstall also removes the scheduled task and firewall rule; the deploy folder and your
    data stay.
#>
param(
    [string]$Jar = '',
    [string]$DeployDir = 'C:\Organizer',
    [int]$Port = 8080,
    [string]$PublicUrl = '',
    [ValidateSet('on', 'off')][string]$AI = 'on',
    # java.exe of a Java 21+ installation. Defaults to JAVA_HOME, then to java on the PATH.
    [string]$JavaExe = '',
    [string]$TaskName = 'Organizer',
    [int]$KeepBackups = 5,
    [switch]$Stop,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

function Quote([string]$value) {
    # A single-quoted PowerShell string; PowerShell also treats typographic quotes as quote characters.
    "'" + ($value -replace '[''\u2018\u2019\u201A\u201B]', '$0$0') + "'"
}

function Find-Java {
    $java = $JavaExe
    if (-not $java -and $env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    if (-not $java) { $java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source }
    if (-not $java -or -not (Test-Path $java)) {
        throw 'Java not found. Install Java 21 or newer and set JAVA_HOME, or pass -JavaExe.'
    }
    $java = (Resolve-Path $java).Path
    # The scheduled task keeps using this java.exe, so check its version now rather than at startup.
    $release = Join-Path (Split-Path (Split-Path $java)) 'release'
    if ((Test-Path $release) -and ((Get-Content $release -Raw) -match 'JAVA_VERSION="(\d+)')) {
        if ([int]$Matches[1] -lt 21) { throw "$java is Java $($Matches[1]); Organizer needs Java 21 or newer." }
    }
    $java
}

function Stop-Organizer([string]$jarPath) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $TaskName
    }
    # Stopping the task ends the launcher script but not the java process it started.
    $deadline = (Get-Date).AddSeconds(30)
    while ($true) {
        $running = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
            $_.CommandLine -and $_.CommandLine.IndexOf($jarPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
        })
        if ($running.Count -eq 0) { return }
        if ((Get-Date) -gt $deadline) { throw "Organizer (java.exe, pid $($running[0].ProcessId)) did not stop." }
        $running | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 1
    }
}

function Backup-Database([string]$dataDir, [string]$backupDir) {
    $database = Join-Path $dataDir 'organizer.db'
    if (-not (Test-Path $database)) { return }
    # The -wal file holds the latest changes; a backup needs both files together.
    $target = Join-Path $backupDir (Get-Date -Format 'yyyy-MM-dd_HH-mm-ss')
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Get-ChildItem $dataDir -Filter 'organizer.db*' | Where-Object { $_.Name -match '^organizer\.db(-wal)?$' } |
        Copy-Item -Destination $target
    Write-Host "Backed up the database to $target"
    Get-ChildItem $backupDir -Directory | Sort-Object Name -Descending | Select-Object -Skip $KeepBackups |
        Remove-Item -Recurse -Force
}

function Write-Launcher([string]$path, [string]$java, [string]$jarPath, [string]$dataDir, [string]$logDir) {
    $settings = [ordered]@{
        ORGANIZER_PORT = "$Port"
        ORGANIZER_DB = (Join-Path $dataDir 'organizer.db')
        ORGANIZER_AI = $AI
    }
    if ($PublicUrl) { $settings.ORGANIZER_PUBLIC_URL = $PublicUrl }
    if ($env:ORGANIZER_PASSWORD) { $settings.ORGANIZER_PASSWORD = $env:ORGANIZER_PASSWORD }

    $lines = @('# Written by deploy.ps1; changes are overwritten on the next deploy.')
    foreach ($setting in $settings.GetEnumerator()) { $lines += "`$env:$($setting.Key) = $(Quote $setting.Value)" }
    $lines += @"
`$logs = $(Quote $logDir)
Set-Location $(Quote $DeployDir)
while (`$true) {
    if (Test-Path "`$logs\organizer.log") { Move-Item -Force "`$logs\organizer.log" "`$logs\organizer.previous.log" }
    `$app = Start-Process -FilePath $(Quote $java) -ArgumentList '-jar', $(Quote ('"' + $jarPath + '"')) ``
        -NoNewWindow -PassThru -RedirectStandardOutput "`$logs\organizer.log" -RedirectStandardError "`$logs\organizer.err.log"
    `$null = `$app.Handle  # keeps ExitCode available after the process ends
    `$app.WaitForExit()
    if (`$app.ExitCode -eq 0) { break }
    Start-Sleep -Seconds 10  # crashed: start it again
}
"@
    # UTF-8 with a byte order mark, so Windows PowerShell reads a non-ASCII password correctly.
    [IO.File]::WriteAllText($path, ($lines -join "`r`n") + "`r`n", (New-Object Text.UTF8Encoding $true))
    # The launcher may contain the password: only SYSTEM and Administrators may read it.
    & icacls.exe $path /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Couldn't restrict access to $path (icacls exit code $LASTEXITCODE)." }
}

function Register-Organizer([string]$launcher) {
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$launcher`"" -WorkingDirectory $DeployDir
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    # No time limit (the default stops tasks after 3 days), and normal priority: the default "below
    # normal" makes reading photos many times slower.
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -Priority 4 `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
        -Settings $settings -Description 'Organizer inventory web app' -Force | Out-Null
}

function Open-Firewall {
    $rule = "Organizer-TCP-$Port"
    if (Get-NetFirewallRule -Name $rule -ErrorAction SilentlyContinue) { return }
    # Home and work networks only, not public Wi-Fi.
    New-NetFirewallRule -Name $rule -DisplayName "Organizer (TCP $Port)" -Direction Inbound -Protocol TCP `
        -LocalPort $Port -Action Allow -Profile Private, Domain | Out-Null
    Write-Host "Allowed incoming connections on port $Port (private and domain networks)."
}

function Wait-Organizer([string]$logDir) {
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        try {
            Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 5 | Out-Null
            return
        } catch {
            # Not listening yet, or 401: up and asking for the password.
            $response = $_.Exception.Response
            if ($response -and [int]$response.StatusCode -eq 401) { return }
        }
    }
    foreach ($log in 'organizer.log', 'organizer.err.log') {
        $file = Join-Path $logDir $log
        if (Test-Path $file) { Write-Host "--- $log ---"; Get-Content $file -Tail 40 | Write-Host }
    }
    throw "Organizer didn't answer on port $Port within 90 seconds."
}

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw 'Run this script as an administrator: it registers a scheduled task and a firewall rule.' }

$jarPath = Join-Path $DeployDir 'organizer.jar'
if ($Stop -or $Uninstall) {
    Stop-Organizer $jarPath
    Write-Host 'Organizer is stopped.'
    if ($Uninstall) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
        Get-NetFirewallRule -Name 'Organizer-TCP-*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        Write-Host "Removed the scheduled task and firewall rule. Your data is still in $DeployDir."
    }
    return
}

if (-not $Jar -or -not (Test-Path $Jar)) { throw "Jar '$Jar' not found. Build it with .\gradlew.bat buildFatJar and pass -Jar build\libs\organizer.jar" }
$java = Find-Java
$dataDir = Join-Path $DeployDir 'data'
$logDir = Join-Path $DeployDir 'logs'
$launcher = Join-Path $DeployDir 'run-organizer.ps1'
foreach ($dir in $DeployDir, $dataDir, $logDir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

Write-Host "Deploying Organizer to $DeployDir (port $Port, Java: $java)"
Stop-Organizer $jarPath
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Something else is already using port $Port. Stop it or deploy with another port."
}
Backup-Database $dataDir (Join-Path $DeployDir 'backups')
Copy-Item -Force $Jar $jarPath
Write-Launcher $launcher $java $jarPath $dataDir $logDir
Register-Organizer $launcher
Open-Firewall
Start-ScheduledTask -TaskName $TaskName
Wait-Organizer $logDir
Write-Host "Organizer is running: http://localhost:$Port (logs in $logDir)"
