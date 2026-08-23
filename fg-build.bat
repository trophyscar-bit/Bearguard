@echo off
REM Paths corrected after the toolchain moved out of OneDrive. Maven and the
REM bundled Temurin JDK both live under C:\Frostguard-tools now; the old C:\apache-maven-3.9.12
REM path never existed on this machine, so `call mvn` below failed with "not recognized". JAVA_HOME
REM is set explicitly too, because there is no system-wide Java install for mvn to fall back on.
REM
REM Updated for the upstream module restructure (fg-app -> modules/desktop,
REM packaged via packaging/desktop). The old fg-app\target\frostguard-*.jar path is gone -- the real
REM launchable jar + lib/ classpath now live under packaging\desktop\target\input\, produced by the
REM new packaging module's dependency-copy step, not modules/desktop's own package phase.
set "JAVA_HOME=C:\Frostguard-tools\jdk-21.0.12+8"
set "PATH=C:\Frostguard-tools\apache-maven-3.9.9\bin;%JAVA_HOME%\bin;%PATH%"
echo ==========================================
echo      Bearguard Quick Recompile Script
echo ==========================================

REM Refuse to build a working tree that does not match HEAD.
REM
REM Maven compiles the WORKING TREE, not HEAD, and three sessions share this checkout. So an
REM uncommitted edit by anyone ships in the next build by anyone, and the jar silently stops
REM matching the commit history it appears to represent -- prod ends up running code that was
REM never committed and nothing in git records it. Only modules\ and packaging\ are checked,
REM because those are what actually compile; a dirty .gitignore or a stray temp\ png does not
REM reach the jar. Untracked files count too: a new .java nobody has added yet still compiles.
REM
REM This runs BEFORE the stop on purpose. Refusing after stopping would leave prod down for a
REM build that was never going to happen.
REM
REM Escape hatch: fg-build.bat --allow-dirty, for when the dirty state IS what you mean to build.
set "ALLOW_DIRTY="
if /i "%~1"=="--allow-dirty" set "ALLOW_DIRTY=1"
if defined ALLOW_DIRTY echo [WARN] --allow-dirty: building uncommitted changes on purpose.
if defined ALLOW_DIRTY goto :tree_ok

where git >nul 2>&1
if errorlevel 1 (
    echo [WARN] git not on PATH -- cannot verify the tree matches HEAD. Continuing unguarded.
    goto :tree_ok
)

set "DIRTY="
git -C "%~dp0." diff --quiet HEAD -- modules packaging
if errorlevel 1 set "DIRTY=1"
for /f "delims=" %%F in ('git -C "%~dp0." ls-files --others --exclude-standard -- modules packaging') do set "DIRTY=1"
if not defined DIRTY goto :tree_ok

echo.
echo [ERROR] Refusing to build: modules\ or packaging\ has changes that are not committed.
echo         Maven compiles the working tree, so these WOULD ship in the jar and prod would
echo         be running code with no commit behind it.
echo.
git -C "%~dp0." status --short -- modules packaging
echo.
echo         Commit or stash them, then run this again.
echo         If you really mean to build them:  fg-build.bat --allow-dirty
pause
exit /b 1

:tree_ok

REM Claim the shared checkout for the whole stop -> build -> relaunch, not just the build. Several
REM sessions share C:\Bearguard, and the window where someone else can switch a branch or start
REM their own build spans all three steps, not the compile alone.
REM Set BEARGUARD_OWNER to identify yourself; it defaults to the machine's username.
if not defined BEARGUARD_OWNER set "BEARGUARD_OWNER=%USERNAME%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0prod-lock.ps1" acquire -Owner "%BEARGUARD_OWNER%" -Reason "fg-build" -Root "%~dp0."
if errorlevel 1 (
    echo [ERROR] Someone else is working in this checkout -- refusing to build over them.
    echo         Ask them, or if they are definitely gone:
    echo             powershell -File prod-lock.ps1 acquire -Owner "%BEARGUARD_OWNER%" -Reason "..." -Force
    pause
    exit /b 1
)

echo.
echo Stopping Bearguard gracefully (checkpoints the WAL and backs settings up first)...
REM This used to be taskkill /F on java.exe + javaw.exe. A hard kill strands writes in
REM frostguard.db-wal, which is how settings were lost twice, and it also killed every OTHER Java
REM process on the machine including a dev instance. stop-bearguard.ps1 backs up, asks the window
REM to close, and confirms it went; if it will not close we abort rather than build against a
REM running instance holding the jars.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-bearguard.ps1" -Root "%~dp0."
if errorlevel 1 (
    echo [ERROR] Bearguard is still running -- refusing to build on top of a live instance.
    echo         Close it by hand and run this again.
    pause
    exit /b 1
)

REM adb holds file handles under the build tree; it is stateless and safe to stop hard.
taskkill /F /IM adb.exe >nul 2>&1
timeout /t 2 >nul

echo.
echo Building project (clean + package, packaging/desktop pulls in modules/desktop)...
call mvn -pl packaging/desktop -am clean package -DskipTests
if errorlevel 1 (
    echo [WARN] First build attempt failed. Applying quick cleanup for transient resource-copy issues...
    if exist "modules\vision\target" rmdir /S /Q "modules\vision\target"
    timeout /t 2 >nul

    echo.
    echo Retrying build once...
    call mvn -pl packaging/desktop -am clean package -DskipTests
    if errorlevel 1 (
        echo [ERROR] Build failed after retry!
        pause
        exit /b %errorlevel%
    )
)

echo.
echo Verifying packaged app JAR integrity...
set "APP_JAR="
for %%F in ("packaging\desktop\target\input\frostguard-desktop-*.jar") do set "APP_JAR=%%~fF"

if not defined APP_JAR (
    echo [ERROR] App JAR not found in packaging\desktop\target\input.
    pause
    exit /b 1
)

where jar >nul 2>&1
if errorlevel 1 (
    echo [WARN] 'jar' tool not found in PATH. Skipping JAR content verification.
) else (
    jar tf "%APP_JAR%" | findstr /C:"dev/frostguard/app/panel/launcher/LauncherLayoutController.class" >nul
    if errorlevel 1 (
        echo [WARN] LauncherLayoutController.class missing in packaged JAR. Rebuilding modules/desktop once...
        call mvn -pl modules/desktop -am clean package -DskipTests
        if errorlevel 1 (
            echo [ERROR] Fallback modules/desktop rebuild failed!
            pause
            exit /b %errorlevel%
        )
        call mvn -pl packaging/desktop clean package -DskipTests
        if errorlevel 1 (
            echo [ERROR] Fallback packaging/desktop rebuild failed!
            pause
            exit /b %errorlevel%
        )

        set "APP_JAR="
        for %%F in ("packaging\desktop\target\input\frostguard-desktop-*.jar") do set "APP_JAR=%%~fF"
        jar tf "%APP_JAR%" | findstr /C:"dev/frostguard/app/panel/launcher/LauncherLayoutController.class" >nul
        if errorlevel 1 (
            echo [ERROR] Packaged JAR is still incomplete after fallback rebuild.
            pause
            exit /b 1
        )
    )
)

echo.
echo ==========================================
echo BUILD SUCCESSFUL!
echo ==========================================
echo.

REM Release on the way out. The failure paths above exit without releasing on purpose rather than
REM through a fragile batch restructure: the lock carries a 20-minute stale timeout, so an
REM abandoned one expires by itself and names its owner when the next person takes it. A lock left
REM by a build that died is a nuisance for 20 minutes; a lock released by a path that did not
REM finish is a second session building on top of a half-built tree.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0prod-lock.ps1" release -Owner "%BEARGUARD_OWNER%" -Root "%~dp0."

set "OUTPUT_DIR=%CD%\packaging\desktop\target\input"
if exist "%OUTPUT_DIR%" (
    echo Opening packaged app input directory: %OUTPUT_DIR%
    start "" explorer "%OUTPUT_DIR%"
) else (
    echo [WARN] Output directory not found: %OUTPUT_DIR%
)

pause
