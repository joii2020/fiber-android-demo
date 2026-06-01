@echo off
setlocal

set ADB=C:\Users\Joii\AppData\Local\Android\Sdk\platform-tools\adb.exe
set FIBER_DIR=/home/joii/code/fiber-dev/fiber
set WSL_NDK_HOME=/home/joii/tools/android_ndk/android-ndk-r27d

wsl.exe -d Debian -- sh -lc "cd %FIBER_DIR% && ANDROID_NDK_HOME=%WSL_NDK_HOME% RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384' make android-so"
if errorlevel 1 exit /b %errorlevel%

if not exist app\src\main\jniLibs\arm64-v8a mkdir app\src\main\jniLibs\arm64-v8a
wsl.exe -d Debian -- cp %FIBER_DIR%/target/android/arm64-v8a/libfiber_ffi.so /mnt/c/Users/Joii/AndroidStudioProjects/FiberDemo/app/src/main/jniLibs/arm64-v8a/libfiber_ffi.so
if errorlevel 1 exit /b %errorlevel%

call .\gradlew.bat clean assembleDebug
if errorlevel 1 exit /b %errorlevel%

"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk
if errorlevel 1 exit /b %errorlevel%

"%ADB%" shell am force-stop com.example.fiberdemo
"%ADB%" shell am start -n com.example.fiberdemo/.MainActivity
