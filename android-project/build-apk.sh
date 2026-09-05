#!/bin/bash
set -e

# =============================================================================
# Aethrous Android APK Builder
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

echo "=== Aethrous Android APK Builder ==="
echo ""

# Check for required tools
check_tools() {
    local missing=()
    
    if ! command -v java &>/dev/null; then
        missing+=("java (JDK 17)")
    fi
    
    if ! command -v gradle &>/dev/null && [ ! -f "$SCRIPT_DIR/gradlew" ]; then
        missing+=("gradle")
    fi
    
    if [ ${#missing[@]} -gt 0 ]; then
        echo "Missing required tools:"
        for tool in "${missing[@]}"; do
            echo "  - $tool"
        done
        echo ""
        echo "Install JDK 17:"
        echo "  sudo apt install openjdk-17-jdk"
        echo ""
        echo "Install Gradle:"
        echo "  sudo apt install gradle"
        exit 1
    fi
}

# Setup Android SDK if not present
setup_android_sdk() {
    if [ ! -d "$ANDROID_HOME" ]; then
        echo ">>> Android SDK not found at $ANDROID_HOME"
        echo ">>> Please install Android SDK or set ANDROID_HOME"
        echo ""
        echo "Install Android SDK:"
        echo "  1. Download Android Studio from https://developer.android.com/studio"
        echo "  2. Or install command-line tools:"
        echo "     mkdir -p ~/Android/Sdk/cmdline-tools"
        echo "     cd ~/Android/Sdk/cmdline-tools"
        echo "     wget https://dl.google.com/android/repository/commandlinetools-linux-latest.zip"
        echo "     unzip commandlinetools-linux-latest.zip"
        echo "     mv cmdline-tools latest"
        echo ""
        exit 1
    fi
    
    echo "Android SDK: $ANDROID_HOME"
    
    # Check for build tools
    if [ ! -d "$ANDROID_HOME/build-tools" ]; then
        echo ">>> Installing build tools..."
        yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "build-tools;34.0.0"
    fi
    
    # Check for platform
    if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
        echo ">>> Installing Android 34 platform..."
        yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34"
    fi
    
    # Check for NDK
    if [ ! -d "$ANDROID_HOME/ndk" ]; then
        echo ">>> Installing NDK..."
        yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;26.1.10909125"
    fi
}

# Create gradle wrapper if not exists
create_gradle_wrapper() {
    if [ ! -f "$SCRIPT_DIR/gradlew" ]; then
        echo ">>> Creating Gradle wrapper..."
        cd "$SCRIPT_DIR"
        gradle wrapper --gradle-version 8.9
    fi
    chmod +x "$SCRIPT_DIR/gradlew"
}

# Build APK
build_apk() {
    echo ">>> Building APK..."
    cd "$SCRIPT_DIR"
    
    # Clean previous builds
    ./gradlew clean
    
    # Build debug APK
    echo ">>> Building debug APK..."
    ./gradlew assembleDebug
    
    # Build release APK
    echo ">>> Building release APK..."
    ./gradlew assembleRelease
    
    echo ""
    echo "=== Build Complete ==="
    echo ""
    echo "Debug APK: app/build/outputs/apk/debug/app-debug.apk"
    echo "Release APK: app/build/outputs/apk/release/app-release-unsigned.apk"
    echo ""
    echo "To sign the release APK:"
    echo "  jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \\"
    echo "    -keystore release-key.jks \\"
    echo "    app/build/outputs/apk/release/app-release-unsigned.apk alias_name"
    echo ""
}

# Main
check_tools
setup_android_sdk
create_gradle_wrapper
build_apk
