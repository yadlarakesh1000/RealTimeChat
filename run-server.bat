@echo off
REM Starts the chat server from the runnable jar.  (Windows)
REM
REM   run-server.bat
REM   run-server.bat -Dchat.passphrase="open sesame"
REM
REM Anything you pass is handed to the JVM, which is why %* goes BEFORE -jar.

setlocal
set JAR=target\chat-server.jar

REM Build on first run so a stranger who just cloned the repo can start here.
if not exist "%JAR%" (
  echo No %JAR% yet - building it ^(about a minute the first time^)...
  call mvn -q -DskipTests package
  if errorlevel 1 exit /b 1
)

java %* -jar "%JAR%"
