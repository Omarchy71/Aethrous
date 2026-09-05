# Aethrous

Censorship circumvention with system-wide traffic routing and anti-DPI.

## What is this?

Aethrous combines two powerful projects:

- **[Aether](https://github.com/CluvexStudio/Aether)** - Censorship circumvention client (MASQUE, WireGuard, nested WireGuard)
- **[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)** - Lightweight tun2socks implementation

### How it works

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐     ┌──────────┐
│ Your Apps   │────▶│ TUN Device   │────▶│ hev-socks5-     │────▶│ Aether   │
│             │     │ (tun0)       │     │ tunnel          │     │ SOCKS5   │
└─────────────┘     └──────────────┘     └─────────────────┘     └────┬─────┘
                                                                      │
                                                               ┌──────▼──────┐
                                                               │  Internet   │
                                                               │  (bypass    │
                                                               │  censorship)│
                                                               └─────────────┘
```

1. **Aether** discovers reachable routes and creates encrypted tunnels
2. **Aether** exposes a local SOCKS5 proxy on `127.0.0.1:1819`
3. **hev-socks5-tunnel** routes all system traffic through a TUN device
4. Traffic flows: App → TUN → SOCKS5 → Aether → Internet

## Anti-DPI Features

### Noize Profiles (Obfuscation)

Before the real handshake, Aether sends junk and random packets so the start of the connection doesn't look like a recognizable pattern.

| Profile | Description |
|---------|-------------|
| `balanced` | Default. Good balance between stealth and speed. |
| `aggressive` | Maximum obfuscation. Sends most decoy packets. For strict DPI. |
| `light` | Minimal obfuscation. Fastest option. |
| `off` | No obfuscation. Maximum speed. |

### Scan Modes

| Mode | Description |
|------|-------------|
| `turbo` | Fast, first working endpoint |
| `balanced` | Good balance [default] |
| `thorough` | Deep scan for best ping |
| ` stealth` | Slow scan to avoid detection |
| `ironclad` | End-to-end data validation [most reliable] |

### Recommended Configurations

**For strict DPI networks:**
```bash
./aethrous.sh start --noize aggressive --scan ironclad
```

**For moderate restrictions:**
```bash
./aethrous.sh start --noize balanced --scan balanced
```

**For speed (less restricted):**
```bash
./aethrous.sh start --noize light --scan turbo
```

## Linux Installation

### Prerequisites

```bash
# Debian/Ubuntu
sudo apt install git cmake make gcc g++ curl

# Install Rust (for Aether)
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source ~/.cargo/env
```

### Build

```bash
git clone https://github.com/Omarchy71/Aethrous.git
cd Aethrous
chmod +x build.sh
./build.sh
```

### Run

```bash
# Start with gool + default anti-DPI (recommended)
sudo ./aethrous.sh

# Start with aggressive anti-DPI
sudo ./aethrous.sh start --noize aggressive

# Start with ironclad scan (best validation)
sudo ./aethrous.sh start --scan ironclad

# Start with maximum anti-DPI
sudo ./aethrous.sh start --noize aggressive --scan ironclad

# Check status
./aethrous.sh status

# Show all profiles
./aethrous.sh profiles

# Stop
sudo ./aethrous.sh stop
```

## Android

### Build for Android

1. Install Android Studio and NDK
2. Clone the repository
3. Build Aether for Android:
   ```bash
   cd deps/aether
   cargo build --target aarch64-linux-android --release
   ```
4. Build hev-socks5-tunnel:
   ```bash
   cd deps/hev-socks5-tunnel
   mkdir jni && cp -r ../* jni/
   ndk-build
   ```
5. Build the APK in Android Studio

### Android Features

- One-tap connect/disconnect
- Always-on VPN option
- Split tunneling (exclude apps)
- Kill switch (block traffic when disconnected)

## Configuration

Edit `conf/user.conf` to customize:

```bash
# Protocol
AETHER_PROTOCOL=gool

# Anti-DPI
AETHER_NOIZE=balanced          # or aggressive for strict DPI
AETHER_SCAN=balanced           # or ironclad for best validation

# Network
AETHER_PORT=1819
AETHER_TUN=tun0

# Behavior
AETHER_QUICK_RECONNECT=true
AETHER_AUTO_RECONNECT=true
```

Or use command line:
```bash
./aethrous.sh start --noize aggressive --scan ironclad --port 1080
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AETHER_PROTOCOL` | gool | Protocol: gool, masque, wireguard |
| `AETHER_NOIZE` | balanced | Obfuscation: balanced, aggressive, light, off |
| `AETHER_SCAN` | balanced | Scan: turbo, balanced, thorough, stealth, ironclad |
| `AETHER_PORT` | 1819 | SOCKS5 port |
| `AETHER_TUN` | tun0 | TUN device |
| `AETHER_IP_VERSION` | 4 | IP version: 4, 6, dual |
| `AETHER_KEEPALIVE` | 25 | WireGuard keepalive (seconds) |
| `AETHER_QUICK_RECONNECT` | true | Quick reconnect |
| `AETHER_AUTO_RECONNECT` | true | Auto-reconnect on failure |

## Troubleshooting

### Check if services are running

```bash
./aethrous.sh status
```

### Common issues

1. **Permission denied**: Run with `sudo`
2. **TUN device not found**: Load kernel module: `sudo modprobe tun`
3. **Connects but no data**: Try `--scan ironclad` for end-to-end validation
4. **Keeps disconnecting**: Try `--noize aggressive` for stronger obfuscation

## Architecture

This project combines:

- **Aether** (AGPL-3.0) - Censorship circumvention
- **hev-socks5-tunnel** (MIT) - tun2socks implementation

## License

MIT License

## Credits

- [CluvexStudio](https://github.com/CluvexStudio) - Aether
- [heiher](https://github.com/heiher) - hev-socks5-tunnel
