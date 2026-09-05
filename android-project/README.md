# Aethrous Android

Censorship circumvention VPN for Android.

## Build Instructions

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- Android NDK
- JDK 17

### Build Steps

1. Open Android Studio
2. File -> Open -> Select `android-project` folder
3. Wait for Gradle sync
4. Build -> Build Bundle(s) / APK(s) -> Build APK(s)

### Build from Command Line

```bash
cd android-project

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

APK will be at: `app/build/outputs/apk/`

### Native Libraries

Place pre-built native libraries in:
```
app/src/main/jniLibs/
  arm64-v8a/
    libaether.so
    libhev-socks5-tunnel.so
  armeabi-v7a/
    libaether.so
    libhev-socks5-tunnel.so
  x86_64/
    libaether.so
    libhev-socks5-tunnel.so
```

## Features

- One-tap connect/disconnect
- Gool protocol (nested WireGuard) by default
- Anti-DPI with noize profiles
- System-wide traffic routing
- Always-on VPN option
- Kill switch

## Permissions

- INTERNET: Network access
- FOREGROUND_SERVICE: VPN service
- RECEIVE_BOOT_COMPLETED: Auto-start option
