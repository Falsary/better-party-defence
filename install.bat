@echo off
setlocal EnableExtensions
set "PLUGIN_DIR=%~dp0"
set "DEST_DIR=%USERPROFILE%\.runelite\sideloaded-plugins"
set "JAR="

for %%F in ("%PLUGIN_DIR%build\libs\betterpartydefence-1.1.0.jar" "%PLUGIN_DIR%build\libs\betterpartydefence.jar") do (
  if exist "%%F" set "JAR=%%F"
)

if not defined JAR (
  echo No built jar found. Building it now...
  call "%PLUGIN_DIR%gradlew.bat" jar
  if errorlevel 1 exit /b 1
  if exist "%PLUGIN_DIR%build\libs\betterpartydefence-1.1.0.jar" set "JAR=%PLUGIN_DIR%build\libs\betterpartydefence-1.1.0.jar"
)

if not defined JAR (
  echo ERROR: build completed but the Better Party Defence jar was not found.
  exit /b 1
)

if not exist "%DEST_DIR%" mkdir "%DEST_DIR%"
copy /Y "%JAR%" "%DEST_DIR%\BetterPartyDefence.jar" >nul
if errorlevel 1 exit /b 1

echo Installed developer sideload:
echo   %DEST_DIR%\BetterPartyDefence.jar
echo.
echo Start RuneLite in true developer mode to load sideloaded plugins.
pause
