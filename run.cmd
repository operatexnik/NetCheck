@echo off
cd /d "%~dp0"
if not exist NetCheck.jar call build.cmd
start "" javaw -jar NetCheck.jar
