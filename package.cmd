@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

if not exist NetCheck.jar call build.cmd
if errorlevel 1 exit /b 1

rem jpackage находится в настоящем JDK, а не в javapath.
set "JHOME="
for /f "tokens=2 delims==" %%i in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /i "java.home"') do set "JHOME=%%i"
for /f "tokens=* delims= " %%j in ("!JHOME!") do set "JHOME=%%j"
if not exist "!JHOME!\bin\jpackage.exe" (
    echo [ERROR] jpackage.exe not found in "!JHOME!\bin".
    exit /b 1
)

if exist dist rmdir /s /q dist
if exist dist-input rmdir /s /q dist-input
mkdir dist-input
copy /y NetCheck.jar dist-input\ >nul

"!JHOME!\bin\jpackage.exe" --type app-image --name NetCheck --app-version 1.0 ^
  --input dist-input --main-jar NetCheck.jar --main-class netcheck.Main ^
  --icon app.ico --dest dist --vendor "NetCheck" --description "Net & VPN Checker" ^
  --java-options "-Xmx256m" --java-options "-Dfile.encoding=UTF-8" ^
  --add-modules java.base,java.desktop,java.logging,jdk.crypto.ec,jdk.unsupported
if errorlevel 1 (
    echo [ERROR] jpackage failed.
    exit /b 1
)

echo.
echo Portable app: %~dp0dist\NetCheck\NetCheck.exe
echo No Java installation is required on the target machine.
