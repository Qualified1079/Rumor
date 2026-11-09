@echo off
rem Windows launcher for the headless :node peer: starts it in this console
rem (live log) and opens its localhost status page. Build the jar first with
rem   gradlew :node:jar
rem Extra args pass through, e.g. rumor-node.cmd --peer 127.0.0.1:47000
setlocal
set "JAR=%~dp0..\node\build\libs\node.jar"
if not exist "%JAR%" (
  echo node.jar not found - run: gradlew :node:jar
  pause
  exit /b 1
)
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
title rumor node
start "" /b cmd /c "timeout /t 4 >nul & start http://127.0.0.1:8180/"
"%JAVA%" -jar "%JAR%" %*
pause
