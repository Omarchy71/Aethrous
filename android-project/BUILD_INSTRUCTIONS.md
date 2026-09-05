# Building Aethrous Android APK

## Prerequisites

1. **JDK 17** or later
2. **Android SDK** with:
   - Android SDK Platform 34
   - Android SDK Build-Tools 34.0.0
   - Android SDK Platform-Tools

## Quick Build (Linux/macOS)

```bash
# Install Android SDK (if not installed)
# Ubuntu/Debian:
sudo apt install android-sdk

# Or download from: https://developer.android.com/studio#command-line-tools-only

# Set environment
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# Install required SDK components
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# Build APK
./gradlew assembleDebug
```

## Build with Android Studio

1. Open Android Studio
2. File -> Open -> Select `android-project` folder
3. Wait for Gradle sync
4. Build -> Build Bundle(s) / APK(s) -> Build APK(s)

## Output

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Install on Device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```
