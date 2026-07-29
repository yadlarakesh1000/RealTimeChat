@echo off
REM Starts one GUI chat client from the runnable jar.  (Windows)
REM
REM   run-client.bat
REM
REM Run it once per person - every window is a separate client.

setlocal
set JAR=target\chat-client.jar

if not exist "%JAR%" (
  echo No %JAR% yet - building it ^(about a minute the first time^)...
  call mvn -q -DskipTests package
  if errorlevel 1 exit /b 1
)

java %* -jar "%JAR%"
