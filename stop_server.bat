@echo off
chcp 65001 >nul
title Stop SMS Server
echo Stopping SMS server on port 8080 ...

rem Find PID listening on 8080 and kill it
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
    echo Killing PID %%p
    taskkill /F /PID %%p >nul 2>&1
)

timeout /t 1 /nobreak >nul
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
if errorlevel 1 (
    echo [OK] SMS server stopped.
) else (
    echo [WARN] something is still on 8080; check manually.
)
timeout /t 2 /nobreak >nul
