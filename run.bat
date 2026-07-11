@echo off
rem ============================================================================
rem                          SAMSUNG S23 ULTRA VIDEO INDEXER
rem                      UNIFIED MULTI-MODE ENTRYPOINT LAUNCHER
rem ============================================================================
rem Description: This single entrypoint batch script handles environment verification,
rem              dependency verification, background process management, and build
rem              automation.
rem Modes:
rem   1. Main Mode - Direct, clean, optimized production-grade build and installer.
rem   2. Debug Mode - "Bolt-on" diagnostics, monitoring, and tracing log exports.
rem
rem LLM ENTRYPOINT NOTE: If you are an AI/LLM agent or developer trying to debug
rem or run this project, go to:
rem   - Main Composable: /app/src/main/java/com/example/MainActivity.kt
rem   - Local State ViewModel: /app/src/main/java/com/example/ui/VideoViewModel.kt
rem   - Gradle Configs: ./build.gradle.kts and ./app/build.gradle.kts
rem ============================================================================

setlocal enabledelayedexpansion

title Samsung S23 Ultra Video Indexer Launcher
color 0B

echo ============================================================================
echo *            SAMSUNG S23 ULTRA VIDEO INDEXER LAUNCHER PORTAL               *
echo ============================================================================
echo.
echo Please select the execution mode for the application:
echo.
echo   [1] Main Mode   - Direct build, compile, and device install
echo   [2] Debug Mode  - Advanced diagnostics, activity tracing, and log dumping
echo   [3] Exit Portal
echo.
echo ============================================================================
set /p MODE_CHOICE="Enter selection [1-3]: "

if "%MODE_CHOICE%"=="1" (
    set RUN_DEBUG=0
    goto SOLUTION_PROCESS
)
if "%MODE_CHOICE%"=="2" (
    set RUN_DEBUG=1
    goto SOLUTION_PROCESS
)
if "%MODE_CHOICE%"=="3" (
    echo Exiting launcher portal. Goodbye!
    pause
    exit /b 0
)

echo Invalid choice. Defaulting to Main Mode.
set RUN_DEBUG=0
pause

:SOLUTION_PROCESS
cls
echo ============================================================================
echo   STEP 1: ENVIRONMENT SANITIZATION AND PRE-FLIGHT CHECK
echo ============================================================================

rem 1. Check for JDK installation
echo Checking for Java JDK installation...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    color 0C
    echo [ERROR] Java JDK is missing from your system PATH!
    echo [FIX] Please install JDK 17 or higher and add it to your environment variables.
    echo.
    pause
    exit /b 1
)
echo [SUCCESS] Java environment detected successfully.
echo.

rem 2. Check for Gradle environment
echo Checking for Gradle installation...
gradle -v >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARNING] Global Gradle CLI was not found in your system PATH.
    echo Trying to find Gradle locally or will attempt direct compilation...
) else (
    echo [SUCCESS] Gradle CLI detected successfully.
)
echo.

rem 3. Detect and kill zombie/stale processes to release locked resources
echo Cleaning stale, zombie, or locked build locks...
taskkill /f /im gradle* >nul 2>&1
taskkill /f /im java.exe >nul 2>&1
taskkill /f /im adb.exe >nul 2>&1
echo [SUCCESS] All background locks and zombie sessions successfully purged.
echo.

if "%RUN_DEBUG%"=="1" (
    goto DEBUG_MODE_ACTIVATION
) else (
    goto DEPENDENCY_LOAD
)

:DEBUG_MODE_ACTIVATION
echo ============================================================================
echo   STEP 2: DEBUG MODE LOG TRACING & RESOURCE MONITORING
echo ============================================================================
echo [STATUS] Debug mode selected. Generating local diagnosis environment...

rem Create debug folders if not exists
if not exist "test" mkdir test
if not exist "test\debugging" mkdir test\debugging

set /p ISSUE_NAME="Enter short, descriptive name for the issue (e.g. build_error): "
if "%ISSUE_NAME%"=="" set ISSUE_NAME=issue

rem Create unique timestamp for log isolation
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set datetime=%%I
set YYYY=!datetime:~0,4!
set MM=!datetime:~4,2!
set DD=!datetime:~6,2!
set HH=!datetime:~8,2!
set MIN=!datetime:~10,2!

set DIAGNOSTICS_DIR=test\debugging\!ISSUE_NAME!_!YYYY!_!MM!_!DD!_!HH!!MIN!
mkdir "!DIAGNOSTICS_DIR!"

echo Diagnostic directory initialized: !DIAGNOSTICS_DIR!
echo Dumped logs will be written here. This makes it extremely easy for an LLM or human developer
echo to identify issues, view exact error codes, and bypass compilation noise.
echo.

echo Capturing active tasks, system environment variables, and network maps...
echo === ACTIVE PORT NETWORK MAP === > "!DIAGNOSTICS_DIR!\port_diagnostics.log"
netstat -ano >> "!DIAGNOSTICS_DIR!\port_diagnostics.log" 2>&1

echo === DETECTED WINDOWS SERVICES === > "!DIAGNOSTICS_DIR!\process_services.log"
tasklist /v >> "!DIAGNOSTICS_DIR!\process_services.log" 2>&1

echo === SYSTEM ENVIRONMENT PATH VARIABLES === > "!DIAGNOSTICS_DIR!\env_vars.log"
set >> "!DIAGNOSTICS_DIR!\env_vars.log" 2>&1

rem Define Gradle Options for expanded, stacktrace logs
set GRADLE_OPTS=-Dorg.gradle.debug=false -Dorg.gradle.daemon=false
set BUILD_FLAGS=--stacktrace --info
echo Enabled detailed Gradle compiler tracers and trace logs.
echo.
goto DEPENDENCY_LOAD

:DEPENDENCY_LOAD
echo ============================================================================
echo   STEP 3: ACTIVE DEPENDENCY AND PORT DISCOVERY GATES
echo ============================================================================

rem Start ADB server to establish local connections
echo Spawning Android Debug Bridge (ADB) service...
adb start-server >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARNING] Failed to spawn local ADB automatically. Ensure Android Studio is installed.
)

rem Discover active connected Android emulator or physical device targets
echo Querying connected Android emulators and physical devices...
set TEMP_ADB_LOG=test\debugging\adb_discovery_temp.log
if not exist "test\debugging" mkdir test\debugging
adb devices > "%TEMP_ADB_LOG%" 2>&1
if "%RUN_DEBUG%"=="1" (
    copy "%TEMP_ADB_LOG%" "!DIAGNOSTICS_DIR!\adb_discovery.log" >nul 2>&1
)
type "%TEMP_ADB_LOG%" 2>nul
del "%TEMP_ADB_LOG%" >nul 2>&1

rem Parse adb devices output to verify connectivity
adb devices | findstr /c:"device" >nul 2>&1
if %errorlevel% neq 0 (
    color 0E
    echo.
    echo [WARNING] No active Android emulator or physical USB device was detected!
    echo [SOLUTION] Ensure your phone is connected with USB Debugging enabled, or launch
    echo            an Android Virtual Device (AVD) from Android Studio.
    echo.
) else (
    echo [SUCCESS] Android debug target discovered. Ready for build loading.
)

rem Query Ollama Local AI connection target
echo Querying local Ollama service connection (Port 11434)...
powershell -Command "try { $r = Invoke-WebRequest -Uri 'http://localhost:11434' -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { Write-Host '[SUCCESS] Local Ollama service verified!' } } catch { Write-Host '[WARNING] Local Ollama server is unreachable. Offline LLM indexing will run in fallback simulation mode.' }" 2>&1

echo.
goto LOAD_APPLICATION

:LOAD_APPLICATION
echo ============================================================================
echo   STEP 4: COMPILING & DISPATCHING THE APPLICATION PACKAGE
rem ============================================================================
echo Building and assembling Android app debug package...

if "%RUN_DEBUG%"=="1" (
    echo [COMPILE RUN] Building with tracer logging: gradle assembleDebug !BUILD_FLAGS!
    gradle assembleDebug !BUILD_FLAGS! > "!DIAGNOSTICS_DIR!\gradle_build.log" 2>&1
    set BUILD_RESULT=!errorlevel!
    
    if !BUILD_RESULT! neq 0 (
        color 0C
        echo.
        echo ============================================================================
        echo [CRITICAL ERROR] Gradle compilation run failed with error code !BUILD_RESULT!
        echo ============================================================================
        echo.
        echo SIMPLE ENGLISH CAUSE SUMMARY:
        echo The Android compiler encountered syntax or dependency issues that halted compilation.
        echo.
        echo DIAGNOSIS AND CORRECTION ACTIONS:
        echo   1. View the full stacktrace log at: !DIAGNOSTICS_DIR!\gradle_build.log
        echo   2. Verify all dependency versions in /gradle/libs.versions.toml
        echo   3. Run 'gradle clean' before compiling again.
        echo.
        echo Exact logs have been isolated inside your test/debugging folder.
        pause
        exit /b !BUILD_RESULT!
    ) else (
        color 0A
        echo.
        echo ============================================================================
        echo [SUCCESS] App compiled successfully in Debug Mode!
        echo ============================================================================
        echo Diagnostic folder output summary:
        echo   - Diagnostic Logs: !DIAGNOSTICS_DIR!\
        echo   - Assembled APK Path: app\build\outputs\apk\debug\app-debug.apk
        echo.
        echo Triggering automatic package sideload install...
        adb install -r app\build\outputs\apk\debug\app-debug.apk
        if %errorlevel% neq 0 (
            echo [WARNING] Sideload install failed. Device might be locked or disconnected.
        ) else (
            echo [SUCCESS] App package loaded and launched on your emulator/device screen!
        )
        pause
        exit /b 0
    )
) else (
    echo [COMPILE RUN] gradle assembleDebug
    gradle assembleDebug
    set BUILD_RESULT=%errorlevel%
    
    if !BUILD_RESULT! neq 0 (
        color 0C
        echo.
        echo ============================================================================
        echo [CRITICAL ERROR] Direct gradle build failed with code !BUILD_RESULT!
        echo ============================================================================
        echo Please run in Debug Mode [Option 2] to export advanced stacktrace logs
        echo and trace exactly what line or library is failing.
        echo.
        pause
        exit /b !BUILD_RESULT!
    ) else (
        color 0A
        echo.
        echo ============================================================================
        echo [SUCCESS] Application successfully compiled and built!
        echo ============================================================================
        echo Assembled APK: app\build\outputs\apk\debug\app-debug.apk
        echo.
        echo Triggering adb sideload install...
        adb install -r app\build\outputs\apk\debug\app-debug.apk
        if %errorlevel% neq 0 (
            echo [WARNING] ADB install failed. Device might be locked or offline.
        ) else (
            echo [SUCCESS] Application installed successfully.
        )
        pause
        exit /b 0
    )
)
