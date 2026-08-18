@echo off

if %OS%=="Windows_NT" @setlocal
if %OS%=="WINNT" @setlocal

if exist "%~dp0wanaku-keycloak-admin.exe" (
    "%~dp0wanaku-keycloak-admin.exe" %*
    exit /b %ERRORLEVEL%
)

@java -jar "%~dp0quarkus-run.jar" %*
