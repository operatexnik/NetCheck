# Copies the portable build to the Desktop and creates a shortcut.
# Run after package.cmd:
#   powershell -ExecutionPolicy Bypass -File deploy-desktop.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $root 'dist\NetCheck'
if (-not (Test-Path (Join-Path $source 'NetCheck.exe'))) {
    Write-Error "Run package.cmd first - not found: $source\NetCheck.exe"
    exit 1
}

$desktop = [Environment]::GetFolderPath('Desktop')
$target = Join-Path $desktop 'NetCheck'

# Обновление не сможет перезаписать dll, пока приложение запущено.
$running = @(Get-Process NetCheck -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Write-Output "Closing running NetCheck ($($running.Count) process(es))..."
    $running | ForEach-Object { $_.CloseMainWindow() | Out-Null }
    Start-Sleep -Seconds 2
    Get-Process NetCheck -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 500
}

New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -Path (Join-Path $source '*') -Destination $target -Recurse -Force

$exe = Join-Path $target 'NetCheck.exe'
$shell = New-Object -ComObject WScript.Shell
$shortcutPath = Join-Path $desktop 'NetCheck.lnk'
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $exe
$shortcut.WorkingDirectory = $target
$shortcut.IconLocation = "$exe,0"
$shortcut.Description = 'Net & VPN Checker'
$shortcut.Save()

Write-Output "Done:"
Write-Output "  folder:   $target"
Write-Output "  shortcut: $shortcutPath"
