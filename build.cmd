@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
    echo [ERROR] JDK 21 not found in PATH.
    exit /b 1
)

rem jar.exe lives in the real JDK, while javapath contains only shortcuts.
set "JAR_EXE="
where jar >nul 2>nul && set "JAR_EXE=jar"
if not defined JAR_EXE (
    set "JHOME="
    for /f "tokens=2 delims==" %%i in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /i "java.home"') do set "JHOME=%%i"
    for /f "tokens=* delims= " %%j in ("!JHOME!") do set "JHOME=%%j"
    if exist "!JHOME!\bin\jar.exe" set "JAR_EXE=!JHOME!\bin\jar.exe"
)
if not defined JAR_EXE (
    echo [ERROR] jar.exe not found.
    exit /b 1
)

if exist out rmdir /s /q out
mkdir out

javac -encoding UTF-8 -d out src\netcheck\*.java
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    exit /b 1
)

"!JAR_EXE!" --create --file NetCheck.jar --main-class netcheck.Main -C out .
if errorlevel 1 (
    echo [ERROR] Jar creation failed.
    exit /b 1
)

echo.
echo Build OK: %~dp0NetCheck.jar
