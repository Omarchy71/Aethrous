# Aether Tunnel

Censorship circumvention with system-wide traffic routing.

## What is this?

Aether Tunnel combines two powerful projects:

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

## Features

- Automatic endpoint discovery with DPI bypass
- MASQUE (HTTP/3 & HTTP/2), WireGuard, nested WireGuard support
- Traffic obfuscation and protocol mimicry
- System-wide routing (not just app-level proxy)
- IPv4 and IPv6 dual stack
- UDP support with Fullcone NAT
- ICMP ping replies
- Automatic reconnection

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
git clone https://github.com/yourusername/aether-tunnel.git
cd aether-tunnel
chmod +x build.sh
./build.sh
```

### Run

```bash
# Start with MASQUE protocol (default)
sudo ./aether-tunnel.sh start

# Start with WireGuard protocol
sudo ./aether-tunnel.sh start --wireguard

# Start with nested WireGuard (gool)
sudo ./aether-tunnel.sh start --gool

# Check status
./aether-tunnel.sh status

# Stop
sudo ./aether-tunnel.sh stop
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

Edit `conf/tunnel.yml` to customize:

```yaml
tunnel:
  name: tun0
  mtu: 8500
  ipv4: 198.18.0.1
  icmp: 'reply'

socks5:
  address: 127.0.0.1
  port: 1819
  udp: 'udp'
  mark: 438
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AETHER_PROTOCOL` | masque | Protocol: masque, wireguard, gool |
| `AETHER_SCAN` | balanced | Scan mode: quick, balanced, turbo |
| `SOCKS_PORT` | 1819 | Local SOCKS5 port |
| `TUN_NAME` | tun0 | TUN device name |
| `TUN_IP` | 198.18.0.1 | TUN IPv4 address |

## Troubleshooting

### Check if services are running

```bash
./aether-tunnel.sh status
```

### View logs

```bash
# Aether logs
journalctl -u aether-tunnel

# Or run in verbose mode
sudo ./aether-tunnel.sh start --verbose
```

### Common issues

1. **Permission denied**: Run with `sudo`
2. **TUN device not found**: Load kernel module: `sudo modprobe tun`
3. **Port already in use**: Change port in config or stop other services

## Architecture

This project is a wrapper that combines:

- **Aether** (AGPL-3.0) - Censorship circumvention
- **hev-socks5-tunnel** (MIT) - tun2socks implementation

## License

MIT License - See LICENSE file for details.

## Credits

- [CluvexStudio](https://github.com/CluvexStudio) - Aether
- [heiher](https://github.com/heiher) - hev-socks5-tunnel
