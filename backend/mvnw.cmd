@echo off
setlocal
set "MAVEN_VERSION=3.9.11"
set "MAVEN_DIR=%~dp0.mvn\wrapper\dists\apache-maven-%MAVEN_VERSION%"
set "MAVEN_ZIP=%~dp0.mvn\wrapper\apache-maven-%MAVEN_VERSION%-bin.zip"
set "MAVEN_REPO=%~dp0.mvn\repository"

if not exist "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" (
  echo Bootstrapping Apache Maven %MAVEN_VERSION% into %MAVEN_DIR% ...
  if not exist "%MAVEN_DIR%" mkdir "%MAVEN_DIR%"
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile '%MAVEN_ZIP%'"
  if errorlevel 1 exit /b 1
  if not exist "%MAVEN_ZIP%" exit /b 1
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -LiteralPath '%MAVEN_ZIP%' -DestinationPath '%MAVEN_DIR%' -Force"
  if errorlevel 1 exit /b 1
  if not exist "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" exit /b 1
  del "%MAVEN_ZIP%" >nul 2>nul
)

if not exist "%MAVEN_REPO%" mkdir "%MAVEN_REPO%"
call "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" -Dmaven.repo.local="%MAVEN_REPO%" %*
exit /b %ERRORLEVEL%
