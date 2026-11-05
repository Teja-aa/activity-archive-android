@echo off
setlocal
set OUT=activity_archive_diagnostics
if not exist "%OUT%" mkdir "%OUT%"
echo Checking connected Android device...
adb devices
if errorlevel 1 goto :error

echo Collecting UsageStats service dump...
adb shell dumpsys usagestats > "%OUT%\usagestats.txt"
echo Collecting currently buffered event log entries...
adb logcat -b events -d -v epoch > "%OUT%\event-log-buffer.txt"
echo Collecting current power service state...
adb shell dumpsys power > "%OUT%\power-state.txt"
echo Collecting boot properties...
adb shell getprop ro.boot.bootreason > "%OUT%\boot-reason.txt"
adb shell getprop sys.boot_completed >> "%OUT%\boot-reason.txt"
echo.
echo Diagnostics saved in %OUT%.
echo These are best-effort diagnostic snapshots, not a guaranteed complete historical log.
pause
exit /b 0

:error
echo ADB command failed. Install Android Platform Tools and enable USB debugging.
pause
exit /b 1
