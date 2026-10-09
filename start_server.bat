@echo off
chcp 65001 >nul
title SMS Server (keep this window open; Ctrl+C or run stop_server.bat to stop)

set "JAVA_HOME=D:\tools\jdk\jdk-17.0.11+9"
set "DIR=C:\Users\Microsoft\Documents\AgnesCode\apk\server"
set "JAVA=%JAVA_HOME%\bin\java.exe"
set "JAVAC=%JAVA_HOME%\bin\javac.exe"

if not exist "%DIR%" (
    echo [ERROR] dir not found: %DIR%
    pause
    exit /b 1
)
cd /d "%DIR%"

echo.
echo [1/3] compiling SmsServer.java ...
"%JAVAC%" -encoding UTF-8 SmsServer.java
if errorlevel 1 (
    echo [ERROR] compile failed.
    pause
    exit /b 1
)

echo [2/3] starting server ...
powershell -NoProfile -Command "Start-Process -FilePath '%JAVA%' -ArgumentList '-cp','%DIR%','SmsServer' -WindowStyle Minimized"
set /a tries=0
:wait_loop
timeout /t 1 /nobreak >nul
set /a tries+=1
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
if not errorlevel 1 goto open_web
if %tries% lss 8 goto wait_loop
echo [ERROR] server did not start within 8 seconds.
pause
exit /b 1

:open_web
echo [3/3] opening browser ...
start http://localhost:8080/

echo.
echo ===========================================================
echo  SMS server is running.  Keep this window open.
echo  Local web:    http://localhost:8080/
echo  To find your PC LAN IP: run 'ipconfig' (look for IPv4, e.g. 192.168.1.x)
echo  Phone browser / App server: http://YOUR_PC_IP:8080/
echo  Stop server:  run stop_server.bat, or close this window / Ctrl+C
echo ===========================================================
timeout /t 3 /nobreak >nul
