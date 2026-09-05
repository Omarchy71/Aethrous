#!/bin/bash
set -e

# =============================================================================
# Aethrous Release Builder
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
RELEASE_DIR="$SCRIPT_DIR/release"
VERSION="${1:-1.0.0}"

echo "=== Aethrous Release Builder ==="
echo "Version: $VERSION"
echo ""

# Create release directory
mkdir -p "$RELEASE_DIR/linux-x64"

# Copy files for release
echo ">>> Packaging Linux release..."
cp "$BUILD_DIR/aether" "$RELEASE_DIR/linux-x64/" 2>/dev/null || true
cp "$BUILD_DIR/hev-socks5-tunnel" "$RELEASE_DIR/linux-x64/" 2>/dev/null || true
cp "$SCRIPT_DIR/aethrous.sh" "$RELEASE_DIR/linux-x64/"
cp "$SCRIPT_DIR/build.sh" "$RELEASE_DIR/linux-x64/"
cp "$SCRIPT_DIR/README.md" "$RELEASE_DIR/linux-x64/"
cp -r "$SCRIPT_DIR/conf" "$RELEASE_DIR/linux-x64/"

chmod +x "$RELEASE_DIR/linux-x64/"*.sh

# Create install script
cat > "$RELEASE_DIR/linux-x64/install.sh" <<'INSTALLEOF'
#!/bin/bash
set -e

INSTALL_DIR="${1:-/opt/aethrous}"

echo "Installing Aethrous to $INSTALL_DIR..."

sudo mkdir -p "$INSTALL_DIR"
sudo cp -r . "$INSTALL_DIR/"
sudo chmod +x "$INSTALL_DIR/aethrous.sh"
sudo chmod +x "$INSTALL_DIR/build.sh"

# Create symlink
sudo ln -sf "$INSTALL_DIR/aethrous.sh" /usr/local/bin/aethrous

echo ""
echo "Aethrous installed successfully!"
echo ""
echo "Usage:"
echo "  sudo aethrous              # Start with defaults"
echo "  sudo aethrous start        # Same as above"
echo "  aethrous status            # Check status"
echo "  sudo aethrous stop         # Stop"
echo "  aethrous profiles          # Show anti-DPI profiles"
echo ""
INSTALLEOF
chmod +x "$RELEASE_DIR/linux-x64/install.sh"

# Create tarball
echo ">>> Creating tarball..."
cd "$RELEASE_DIR"
tar -czf "aethrous-${VERSION}-linux-x64.tar.gz" linux-x64/

echo ""
echo "=== Release Created ==="
echo "Location: $RELEASE_DIR/aethrous-${VERSION}-linux-x64.tar.gz"
echo ""
ls -lh "$RELEASE_DIR/"*.tar.gz
