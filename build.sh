#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"
DEPS_DIR="$PROJECT_DIR/deps"

echo "=== Aether Tunnel Build Script ==="
echo ""

# Create directories
mkdir -p "$BUILD_DIR" "$DEPS_DIR"

# Check dependencies
check_deps() {
    local missing=()
    for cmd in git cmake make gcc g++ cargo; do
        if ! command -v "$cmd" &>/dev/null; then
            missing+=("$cmd")
        fi
    done
    
    if [ ${#missing[@]} -gt 0 ]; then
        echo "Missing dependencies: ${missing[*]}"
        echo "Please install them before continuing."
        exit 1
    fi
}

check_deps

# Clone or update hev-socks5-tunnel
echo ">>> Setting up hev-socks5-tunnel..."
if [ -d "$DEPS_DIR/hev-socks5-tunnel" ]; then
    echo "    Updating existing repo..."
    cd "$DEPS_DIR/hev-socks5-tunnel"
    git pull
else
    echo "    Cloning repo..."
    git clone --recursive https://github.com/heiher/hev-socks5-tunnel "$DEPS_DIR/hev-socks5-tunnel"
fi

# Build hev-socks5-tunnel
echo ">>> Building hev-socks5-tunnel..."
cd "$DEPS_DIR/hev-socks5-tunnel"
make clean || true
make -j$(nproc)
echo "    Built: $DEPS_DIR/hev-socks5-tunnel/bin/hev-socks5-tunnel"

# Clone or update Aether
echo ""
echo ">>> Setting up Aether..."
if [ -d "$DEPS_DIR/aether" ]; then
    echo "    Updating existing repo..."
    cd "$DEPS_DIR/aether"
    git pull
else
    echo "    Cloning repo..."
    git clone https://github.com/CluvexStudio/Aether "$DEPS_DIR/aether"
fi

# Clone quiche alongside aether
echo ">>> Setting up Quiche (Aether dependency)..."
if [ -d "$DEPS_DIR/quiche" ]; then
    echo "    Updating existing repo..."
    cd "$DEPS_DIR/quiche"
    git pull
else
    echo "    Cloning repo..."
    git clone --recursive https://github.com/cloudflare/quiche "$DEPS_DIR/quiche"
fi

# Build Aether
echo ">>> Building Aether..."
cd "$DEPS_DIR/aether"
cargo build --release
echo "    Built: $DEPS_DIR/aether/target/release/aether"

# Copy binaries to build directory
echo ""
echo ">>> Copying binaries..."
cp "$DEPS_DIR/hev-socks5-tunnel/bin/hev-socks5-tunnel" "$BUILD_DIR/"
cp "$DEPS_DIR/aether/target/release/aether" "$BUILD_DIR/"

# Copy config
cp "$PROJECT_DIR/conf/"* "$BUILD_DIR/" 2>/dev/null || true

echo ""
echo "=== Build Complete ==="
echo "Binaries in: $BUILD_DIR/"
ls -la "$BUILD_DIR/"
