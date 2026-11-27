@echo off
rem Запуск с консолью — удобно смотреть ошибки.
cd /d "%~dp0"
if not exist NetCheck.jar call build.cmd
java -jar NetCheck.jar
pause
