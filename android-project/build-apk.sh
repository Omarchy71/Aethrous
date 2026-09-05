#!/bin/bash
set -e

echo "=== Aethrous Android APK Builder ==="
echo ""

# Check for Java
if ! command -v java &>/dev/null; then
    echo "Error: Java not found"
    echo "Install JDK 17:"
    echo "  Ubuntu/Debian: sudo apt install openjdk-17-jdk"
    echo "  Arch: sudo pacman -S jdk17-openjdk"
    echo "  macOS: brew install openjdk@17"
    exit 1
fi

echo "Java: $(java -version 2>&1 | head -1)"

# Check for ANDROID_HOME
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    else
        echo ""
        echo "Error: Android SDK not found"
        echo ""
        echo "Install Android SDK:"
        echo "  1. Download Android Studio: https://developer.android.com/studio"
        echo "  2. Or install command-line tools:"
        echo "     https://developer.android.com/studio#command-line-tools-only"
        echo ""
        echo "Then set ANDROID_HOME:"
        echo "  export ANDROID_HOME=\$HOME/Android/Sdk"
        exit 1
    fi
fi

echo "Android SDK: $ANDROID_HOME"
echo ""

# Navigate to script directory
cd "$(dirname "$0")"

# Make gradlew executable
chmod +x gradlew 2>/dev/null || true

# Create gradle wrapper if not exists
if [ ! -f "gradlew" ]; then
    echo "Creating Gradle wrapper..."
    if command -v gradle &>/dev/null; then
        gradle wrapper --gradle-version 8.9
    else
        echo "Error: Gradle not found"
        echo "Install Gradle:"
        echo "  Ubuntu/Debian: sudo apt install gradle"
        echo "  Arch: sudo pacman -S gradle"
        echo "  macOS: brew install gradle"
        exit 1
    fi
fi

# Build
echo "Building APK..."
echo ""

# Clean
./gradlew clean

# Build debug APK (doesn't require signing)
echo "Building debug APK..."
./gradlew assembleDebug

echo ""
echo "=== Build Complete ==="
echo ""
echo "Debug APK: app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To install on device:"
echo "  adb install app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To build release APK (requires signing):"
echo "  ./gradlew assembleRelease"
echo ""
