#!/bin/bash
set -e

# =============================================================================
# Aethrous - Censorship circumvention with system-wide routing
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN_DIR="${BIN_DIR:-$SCRIPT_DIR/build}"
CONF_DIR="${CONF_DIR:-$SCRIPT_DIR/conf}"
DATA_DIR="${DATA_DIR:-$SCRIPT_DIR/data}"
LOG_DIR="$SCRIPT_DIR/logs"

AETHER_BIN="$BIN_DIR/aether"
TUNNEL_BIN="$BIN_DIR/hev-socks5-tunnel"
USER_CONF="$CONF_DIR/user.conf"
TUNNEL_CONF="$CONF_DIR/tunnel.yml"
PID_FILE="$DATA_DIR/aethrous.pid"
STATE_FILE="$DATA_DIR/state"
RECONNECT_DELAY=5
MAX_RECONNECT_ATTEMPTS=0

AETHER_PID=""
TUNNEL_PID=""

# =============================================================================
# Default Configuration - Optimized for gool anti-DPI
# =============================================================================

# Protocol: gool | masque | wireguard
PROTOCOL="gool"

# Scan mode: turbo | balanced | thorough | stealth | ironclad
# - ironclad: End-to-end data validation (best for strict DPI)
# - thorough: Deep scan for best ping
# - balanced: Good balance (default)
# - stealth: Slow scan to avoid detection
# - turbo: Fast, first match
SCAN_MODE="balanced"

# Noize profile for WireGuard/gool: balanced | aggressive | light | off
# - balanced: Default, good for most restricted networks
# - aggressive: Maximum obfuscation for strict DPI
# - light: Minimal obfuscation
# - off: No obfuscation
NOIZE_PROFILE="balanced"

# SOCKS5 proxy port
SOCKS_PORT=1819

# TUN device settings
TUN_NAME="tun0"
TUN_IP="198.18.0.1"
TUN_IPV6="fc00::1"

# Routing
SOCKS_MARK=438

# WireGuard/gool keepalive (seconds)
WG_KEEPALIVE=25

# Quick reconnect to last known-good gateway
QUICK_RECONNECT=true

# IP version: 4 | 6 | dual
IP_VERSION="4"

# Behavior
AUTO_RECONNECT=true
VERBOSE=false
LOG_LEVEL="info"
DAEMON_MODE=false

# =============================================================================
# Functions
# =============================================================================

usage() {
    cat <<EOF
Aethrous - Censorship circumvention with system-wide routing

Usage: $(basename "$0") [command] [options]

Commands:
    start               Start services (auto-connects by default)
    stop                Stop all services
    status              Show status of services
    restart             Restart all services
    enable              Enable auto-start on boot
    disable             Disable auto-start on boot
    scan                Run Aether endpoint scan only
    config              Edit user configuration
    logs                View recent logs
    profiles            Show available anti-DPI profiles

Options:
    -g, --gool          Use nested WireGuard (gool) [DEFAULT]
    -m, --masque        Use MASQUE protocol
    -w, --wireguard     Use WireGuard protocol
    -s, --scan MODE     Scan mode: turbo|balanced|thorough|stealth|ironclad
    -n, --noize PROFILE Obfuscation: balanced|aggressive|light|off
    -p, --port PORT     Local SOCKS5 port (default: 1819)
    -t, --tun NAME      TUN device name (default: tun0)
    --no-reconnect      Disable auto-reconnect
    --no-quick-reconnect Always scan fresh
    -d, --daemon        Run in background (daemon mode)
    -v, --verbose       Verbose logging
    -h, --help          Show this help

Anti-DPI Profiles (gool/WireGuard):
    balanced            Default - good for most restricted networks
    aggressive          Maximum obfuscation for strict DPI
    light               Minimal obfuscation, faster
    off                 No obfuscation

Scan Modes:
    turbo               Fast, first match
    balanced            Good balance [default]
    thorough            Deep scan for best ping
    stealth             Slow scan to avoid detection
    ironclad            End-to-end data validation [most reliable]

Examples:
    $(basename "$0") start                              # Defaults (gool + balanced)
    $(basename "$0") start --noize aggressive           # Strict DPI bypass
    $(basename "$0") start --scan ironclad              # Best endpoint validation
    $(basename "$0") start --noize aggressive --scan ironclad  # Maximum bypass
    $(basename "$0") start --wireguard --noize light    # Fast, minimal obfuscation
    $(basename "$0") profiles                           # Show all profiles
EOF
}

show_profiles() {
    cat <<EOF

  Aethrous Anti-DPI Profiles
  ==========================

  GOOL/WireGuard Noize Profiles:
  ─────────────────────────────
  balanced     Default. Good balance between stealth and speed.
               Use for most restricted networks.

  aggressive   Maximum obfuscation. Sends most decoy packets.
               Use for very strict DPI networks.

  light        Minimal obfuscation. Fastest option.
               Use for less restricted networks.

  off          No obfuscation. Maximum speed.
               Use for open networks only.


  Scan Modes:
  ──────────
  turbo        Fast. First working endpoint.
               Use when speed matters most.

  balanced     Good balance. [DEFAULT]
               Use for most situations.

  thorough     Deep scan. Best ping selection.
               Use for better performance.

  stealth      Slow scan. Less noise.
               Use for detection-sensitive networks.

  ironclad     End-to-end validation. [MOST RELIABLE]
               Verifies real traffic passing, not just handshakes.
               Use when other modes connect but no data flows.

EOF
}

log() {
    local level="$1"
    shift
    local msg="$*"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    
    case "$LOG_LEVEL" in
        debug) echo "[$timestamp] [$level] $msg" ;;
        info)
            if [[ "$level" != "DEBUG" ]]; then
                echo "[$timestamp] [$level] $msg"
            fi
            ;;
        warn)
            if [[ "$level" == "WARN" || "$level" == "ERROR" ]]; then
                echo "[$timestamp] [$level] $msg" >&2
            fi
            ;;
        error)
            if [[ "$level" == "ERROR" ]]; then
                echo "[$timestamp] [$level] $msg" >&2
            fi
            ;;
    esac
}

log_info()  { log "INFO" "$@"; }
log_warn()  { log "WARN" "$@"; }
log_error() { log "ERROR" "$@"; }
log_debug() { log "DEBUG" "$@"; }

cleanup() {
    echo ""
    log_info "Shutting down..."
    save_state "stopped"
    stop_services
    rm -f "$PID_FILE"
    exit 0
}

save_state() {
    echo "$1" > "$STATE_FILE"
}

load_state() {
    if [ -f "$STATE_FILE" ]; then
        cat "$STATE_FILE"
    else
        echo "stopped"
    fi
}

save_pid() {
    mkdir -p "$DATA_DIR"
    echo "$$" > "$PID_FILE"
}

is_running() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

stop_services() {
    log_info "Stopping services..."
    
    if [ -n "$TUNNEL_PID" ] && kill -0 "$TUNNEL_PID" 2>/dev/null; then
        log_debug "Stopping hev-socks5-tunnel (PID: $TUNNEL_PID)..."
        kill "$TUNNEL_PID" 2>/dev/null || true
        wait "$TUNNEL_PID" 2>/dev/null || true
    fi
    
    if [ -n "$AETHER_PID" ] && kill -0 "$AETHER_PID" 2>/dev/null; then
        log_debug "Stopping Aether (PID: $AETHER_PID)..."
        kill "$AETHER_PID" 2>/dev/null || true
        wait "$AETHER_PID" 2>/dev/null || true
    fi
    
    pkill -f "hev-socks5-tunnel" 2>/dev/null || true
    pkill -f "aether" 2>/dev/null || true
    
    cleanup_routing
    
    save_state "stopped"
    log_info "All services stopped"
}

cleanup_routing() {
    local tun_name="${TUN_NAME}"
    local mark="${SOCKS_MARK}"
    
    log_debug "Cleaning up routing rules..."
    
    ip rule del fwmark "$mark" lookup main pref 10 2>/dev/null || true
    ip -6 rule del fwmark "$mark" lookup main pref 10 2>/dev/null || true
    
    ip route del default dev "$tun_name" table 20 2>/dev/null || true
    ip rule del lookup 20 pref 20 2>/dev/null || true
    ip -6 route del default dev "$tun_name" table 20 2>/dev/null || true
    ip -6 rule del lookup 20 pref 20 2>/dev/null || true
    
    log_debug "Routing cleanup done"
}

check_binaries() {
    if [ ! -f "$AETHER_BIN" ]; then
        log_error "Aether binary not found at $AETHER_BIN"
        log_error "Run build.sh first"
        exit 1
    fi
    if [ ! -f "$TUNNEL_BIN" ]; then
        log_error "hev-socks5-tunnel binary not found at $TUNNEL_BIN"
        log_error "Run build.sh first"
        exit 1
    fi
}

load_user_config() {
    if [ -f "$USER_CONF" ]; then
        log_debug "Loading config from $USER_CONF"
        set -a
        source "$USER_CONF"
        set +a
        
        [ -n "$AETHER_PROTOCOL" ] && PROTOCOL="$AETHER_PROTOCOL"
        [ -n "$AETHER_SCAN" ] && SCAN_MODE="$AETHER_SCAN"
        [ -n "$AETHER_NOIZE" ] && NOIZE_PROFILE="$AETHER_NOIZE"
        [ -n "$AETHER_PORT" ] && SOCKS_PORT="$AETHER_PORT"
        [ -n "$AETHER_TUN" ] && TUN_NAME="$AETHER_TUN"
        [ -n "$AETHER_TUN_IP" ] && TUN_IP="$AETHER_TUN_IP"
        [ -n "$AETHER_TUN_IPV6" ] && TUN_IPV6="$AETHER_TUN_IPV6"
        [ -n "$AETHER_MARK" ] && SOCKS_MARK="$AETHER_MARK"
        [ -n "$AETHER_KEEPALIVE" ] && WG_KEEPALIVE="$AETHER_KEEPALIVE"
        [ -n "$AETHER_QUICK_RECONNECT" ] && QUICK_RECONNECT="$AETHER_QUICK_RECONNECT"
        [ -n "$AETHER_IP_VERSION" ] && IP_VERSION="$AETHER_IP_VERSION"
        [ -n "$AETHER_AUTO_RECONNECT" ] && AUTO_RECONNECT="$AETHER_AUTO_RECONNECT"
        [ -n "$AETHER_LOG_LEVEL" ] && LOG_LEVEL="$AETHER_LOG_LEVEL"
    fi
}

generate_tunnel_config() {
    mkdir -p "$CONF_DIR"
    cat > "$TUNNEL_CONF" <<EOF
tunnel:
  name: $TUN_NAME
  mtu: 8500
  multi-queue: false
  ipv4: $TUN_IP
  ipv6: '$TUN_IPV6'
  icmp: 'reply'

socks5:
  address: 127.0.0.1
  port: $SOCKS_PORT
  udp: 'udp'
  mark: $SOCKS_MARK

misc:
  task-stack-size: 86016
  tcp-buffer-size: 65536
  udp-recv-buffer-size: 524288
  udp-copy-buffer-nums: 10
  max-session-count: 0
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  log-level: $LOG_LEVEL
EOF
    log_debug "Generated tunnel config: $TUNNEL_CONF"
}

setup_routing() {
    log_info "Setting up routing rules..."
    
    sysctl -w net.ipv4.conf.all.rp_filter=0 2>/dev/null || true
    sysctl -w "net.ipv4.conf.$TUN_NAME.rp_filter=0" 2>/dev/null || true
    
    ip rule add fwmark "$SOCKS_MARK" lookup main pref 10 2>/dev/null || true
    ip -6 rule add fwmark "$SOCKS_MARK" lookup main pref 10 2>/dev/null || true
    
    ip route add default dev "$TUN_NAME" table 20 2>/dev/null || true
    ip rule add lookup 20 pref 20 2>/dev/null || true
    ip -6 route add default dev "$TUN_NAME" table 20 2>/dev/null || true
    ip -6 rule add lookup 20 pref 20 2>/dev/null || true
    
    log_info "Routing configured"
}

wait_for_socks5() {
    local max_wait=45
    local waited=0
    
    log_info "Waiting for SOCKS5 proxy on port $SOCKS_PORT..."
    
    while [ $waited -lt $max_wait ]; do
        if ss -tlnp 2>/dev/null | grep -q ":$SOCKS_PORT "; then
            log_info "SOCKS5 proxy is ready"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    
    log_error "Timeout waiting for SOCKS5 proxy"
    return 1
}

start_aether() {
    log_info "Starting Aether..."
    log_info "  Protocol:   $PROTOCOL"
    log_info "  Scan:       $SCAN_MODE"
    log_info "  Noize:      $NOIZE_PROFILE"
    log_info "  IP version: $IP_VERSION"
    
    # Export environment variables for Aether
    export AETHER_PROTOCOL="$PROTOCOL"
    export AETHER_SCAN="$SCAN_MODE"
    export AETHER_NOIZE="$NOIZE_PROFILE"
    export AETHER_SOCKS="127.0.0.1:$SOCKS_PORT"
    export AETHER_IP="$IP_VERSION"
    export AETHER_WG_KEEPALIVE="$WG_KEEPALIVE"
    
    # Quick reconnect setting
    if [ "$QUICK_RECONNECT" = "true" ]; then
        export AETHER_QUICK_RECONNECT=1
    else
        export AETHER_QUICK_RECONNECT=0
    fi
    
    # Extra arguments from config
    if [ -n "${AETHER_EXTRA_ARGS:-}" ]; then
        log_debug "Extra args: $AETHER_EXTRA_ARGS"
    fi
    
    log_debug "Starting Aether binary..."
    
    # Start Aether in background
    "$AETHER_BIN" &
    AETHER_PID=$!
    
    # Wait for SOCKS5 proxy
    if ! wait_for_socks5; then
        log_error "Failed to start Aether"
        return 1
    fi
    
    log_info "Aether started (PID: $AETHER_PID)"
    return 0
}

start_tunnel() {
    log_info "Starting hev-socks5-tunnel..."
    
    local cmd="$TUNNEL_BIN $TUNNEL_CONF"
    
    log_debug "Running: $cmd"
    
    $cmd &
    TUNNEL_PID=$!
    
    sleep 2
    
    if kill -0 "$TUNNEL_PID" 2>/dev/null; then
        log_info "hev-socks5-tunnel started (PID: $TUNNEL_PID)"
        return 0
    else
        log_error "hev-socks5-tunnel failed to start"
        return 1
    fi
}

start_services() {
    check_binaries
    load_user_config
    
    mkdir -p "$LOG_DIR" "$DATA_DIR"
    
    save_pid
    
    generate_tunnel_config
    
    if ! start_aether; then
        log_error "Failed to start Aether"
        rm -f "$PID_FILE"
        exit 1
    fi
    
    if ! start_tunnel; then
        log_error "Failed to start tunnel"
        kill "$AETHER_PID" 2>/dev/null || true
        rm -f "$PID_FILE"
        exit 1
    fi
    
    setup_routing
    
    save_state "running"
    
    log_info "============================================"
    log_info "  Aethrous is running"
    log_info "  Protocol:   $PROTOCOL"
    log_info "  Noize:      $NOIZE_PROFILE"
    log_info "  Scan:       $SCAN_MODE"
    log_info "  SOCKS5:     127.0.0.1:$SOCKS_PORT"
    log_info "  TUN:        $TUN_NAME ($TUN_IP)"
    log_info "============================================"
    log_info "Press Ctrl+C to stop"
    
    monitor_services
}

monitor_services() {
    local reconnect_count=0
    
    while true; do
        if ! kill -0 "$AETHER_PID" 2>/dev/null; then
            log_warn "Aether process died"
            
            if [ "$AUTO_RECONNECT" = "true" ]; then
                reconnect_count=$((reconnect_count + 1))
                
                if [ "$MAX_RECONNECT_ATTEMPTS" -gt 0 ] && [ $reconnect_count -gt $MAX_RECONNECT_ATTEMPTS ]; then
                    log_error "Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)"
                    break
                fi
                
                log_info "Auto-reconnect attempt $reconnect_count (delay: ${RECONNECT_DELAY}s)..."
                stop_services
                sleep "$RECONNECT_DELAY"
                
                log_info "Reconnecting..."
                generate_tunnel_config
                
                if start_aether && start_tunnel; then
                    setup_routing
                    save_state "running"
                    log_info "Reconnected successfully"
                    reconnect_count=0
                else
                    log_error "Reconnect failed"
                fi
            else
                log_error "Auto-reconnect disabled, stopping..."
                break
            fi
        fi
        
        if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
            log_warn "hev-socks5-tunnel process died"
            
            if [ "$AUTO_RECONNECT" = "true" ]; then
                reconnect_count=$((reconnect_count + 1))
                
                if [ "$MAX_RECONNECT_ATTEMPTS" -gt 0 ] && [ $reconnect_count -gt $MAX_RECONNECT_ATTEMPTS ]; then
                    log_error "Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)"
                    break
                fi
                
                log_info "Restarting tunnel..."
                
                if start_tunnel; then
                    log_info "Tunnel restarted"
                    reconnect_count=0
                else
                    log_error "Failed to restart tunnel, full reconnect..."
                    stop_services
                    sleep "$RECONNECT_DELAY"
                    
                    generate_tunnel_config
                    if start_aether && start_tunnel; then
                        setup_routing
                        save_state "running"
                        log_info "Full reconnect successful"
                        reconnect_count=0
                    fi
                fi
            else
                log_error "Auto-reconnect disabled, stopping..."
                break
            fi
        fi
        
        sleep 5
    done
    
    save_state "stopped"
    rm -f "$PID_FILE"
}

show_status() {
    load_user_config
    
    echo ""
    echo "  Aethrous Status"
    echo "  ================"
    echo ""
    
    if is_running; then
        echo "  Service:    RUNNING (PID: $(cat "$PID_FILE"))"
    else
        echo "  Service:    STOPPED"
    fi
    
    if pgrep -f "aether" >/dev/null 2>&1; then
        echo "  Aether:     RUNNING (PID: $(pgrep -f aether | head -1))"
    else
        echo "  Aether:     STOPPED"
    fi
    
    if pgrep -f "hev-socks5-tunnel" >/dev/null 2>&1; then
        echo "  Tunnel:     RUNNING (PID: $(pgrep -f hev-socks5-tunnel | head -1))"
    else
        echo "  Tunnel:     STOPPED"
    fi
    
    if ss -tlnp 2>/dev/null | grep -q ":$SOCKS_PORT "; then
        echo "  SOCKS5:     LISTENING on 127.0.0.1:$SOCKS_PORT"
    else
        echo "  SOCKS5:     NOT LISTENING"
    fi
    
    if ip link show "$TUN_NAME" >/dev/null 2>&1; then
        echo "  TUN:        UP ($TUN_NAME)"
    else
        echo "  TUN:        DOWN"
    fi
    
    echo ""
    echo "  Anti-DPI Configuration"
    echo "  ======================"
    echo ""
    echo "  Protocol:       $PROTOCOL"
    echo "  Noize profile:  $NOIZE_PROFILE"
    echo "  Scan mode:      $SCAN_MODE"
    echo "  IP version:     $IP_VERSION"
    echo "  Quick reconnect: $QUICK_RECONNECT"
    echo "  Auto-reconnect:  $AUTO_RECONNECT"
    echo "  Config file:     $USER_CONF"
    echo ""
}

enable_autostart() {
    log_info "Enabling auto-start on boot..."
    
    if command -v systemctl >/dev/null 2>&1; then
        cat > /tmp/aethrous.service <<EOF
[Unit]
Description=Aethrous - Censorship Circumvention
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$SCRIPT_DIR/aethrous.sh start --daemon
ExecStop=$SCRIPT_DIR/aethrous.sh stop
Restart=on-failure
RestartSec=10
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
        
        sudo mv /tmp/aethrous.service /etc/systemd/system/
        sudo systemctl daemon-reload
        sudo systemctl enable aethrous
        
        log_info "Enabled with systemd"
    else
        (crontab -l 2>/dev/null; echo "@reboot $SCRIPT_DIR/aethrous.sh start --daemon") | crontab -
        log_info "Enabled with crontab"
    fi
    
    log_info "Auto-start enabled"
}

disable_autostart() {
    log_info "Disabling auto-start on boot..."
    
    if command -v systemctl >/dev/null 2>&1; then
        sudo systemctl disable aethrous 2>/dev/null || true
        sudo rm -f /etc/systemd/system/aethrous.service
        sudo systemctl daemon-reload
    else
        crontab -l 2>/dev/null | grep -v "aethrous" | crontab -
    fi
    
    log_info "Auto-start disabled"
}

show_logs() {
    if [ -f "$LOG_DIR/aethrous.log" ]; then
        tail -n 50 "$LOG_DIR/aethrous.log"
    else
        echo "No logs found"
    fi
}

# =============================================================================
# Main
# =============================================================================

COMMAND=""

while [ $# -gt 0 ]; do
    case "$1" in
        start|stop|status|restart|enable|disable|scan|config|logs|profiles)
            COMMAND="$1"
            ;;
        -g|--gool)
            PROTOCOL="gool"
            ;;
        -m|--masque)
            PROTOCOL="masque"
            ;;
        -w|--wireguard)
            PROTOCOL="wireguard"
            ;;
        -s|--scan)
            shift
            SCAN_MODE="$1"
            ;;
        -n|--noize)
            shift
            NOIZE_PROFILE="$1"
            ;;
        -p|--port)
            shift
            SOCKS_PORT="$1"
            ;;
        -t|--tun)
            shift
            TUN_NAME="$1"
            ;;
        --no-reconnect)
            AUTO_RECONNECT=false
            ;;
        --no-quick-reconnect)
            QUICK_RECONNECT=false
            ;;
        -d|--daemon)
            DAEMON_MODE=true
            ;;
        -v|--verbose)
            VERBOSE=true
            LOG_LEVEL="debug"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
    shift
done

if [ -z "$COMMAND" ]; then
    COMMAND="start"
fi

load_user_config

case "$COMMAND" in
    start)
        if [ "$DAEMON_MODE" = "true" ]; then
            nohup "$0" start > "$LOG_DIR/aethrous.log" 2>&1 &
            log_info "Started in daemon mode (PID: $!)"
            exit 0
        fi
        
        trap cleanup SIGINT SIGTERM
        save_pid
        start_services
        ;;
    stop)
        if is_running; then
            kill "$(cat "$PID_FILE")" 2>/dev/null || true
            rm -f "$PID_FILE"
            log_info "Stop signal sent"
        else
            stop_services
        fi
        ;;
    status)
        show_status
        ;;
    restart)
        if is_running; then
            kill "$(cat "$PID_FILE")" 2>/dev/null || true
            rm -f "$PID_FILE"
            sleep 2
        else
            stop_services
            sleep 1
        fi
        
        if [ "$DAEMON_MODE" = "true" ]; then
            nohup "$0" start > "$LOG_DIR/aethrous.log" 2>&1 &
            log_info "Restarted in daemon mode (PID: $!)"
            exit 0
        fi
        
        trap cleanup SIGINT SIGTERM
        save_pid
        start_services
        ;;
    enable)
        enable_autostart
        ;;
    disable)
        disable_autostart
        ;;
    scan)
        check_binaries
        load_user_config
        export AETHER_SCAN="$SCAN_MODE"
        export AETHER_NOIZE="$NOIZE_PROFILE"
        log_info "Running Aether scan..."
        $AETHER_BIN
        ;;
    config)
        mkdir -p "$CONF_DIR"
        if [ ! -f "$USER_CONF" ]; then
            cat > "$USER_CONF" <<'EOF'
# Aethrous Configuration
# =======================
# Uncomment and modify values as needed.
# Changes require restart to take effect.

# Protocol: gool | masque | wireguard
# Default: gool (nested WireGuard - best for censorship bypass)
# AETHER_PROTOCOL=gool

# Scan mode: turbo | balanced | thorough | stealth | ironclad
# Default: balanced
# - turbo:      Fast, first working endpoint
# - balanced:   Good balance [default]
# - thorough:   Deep scan for best ping
# - stealth:    Slow scan to avoid detection
# - ironclad:   End-to-end data validation [most reliable]
# AETHER_SCAN=balanced

# Noize profile (obfuscation): balanced | aggressive | light | off
# Default: balanced
# - balanced:    Good for most restricted networks
# - aggressive:  Maximum obfuscation for strict DPI
# - light:       Minimal obfuscation
# - off:         No obfuscation
# AETHER_NOIZE=balanced

# SOCKS5 proxy port
# Default: 1819
# AETHER_PORT=1819

# TUN device name
# Default: tun0
# AETHER_TUN=tun0

# TUN IPv4 address
# Default: 198.18.0.1
# AETHER_TUN_IP=198.18.0.1

# TUN IPv6 address
# Default: fc00::1
# AETHER_TUN_IPV6=fc00::1

# SOCKS mark for routing bypass (decimal or hex)
# Default: 438
# AETHER_MARK=438

# WireGuard keepalive (seconds)
# Default: 25
# AETHER_KEEPALIVE=25

# Quick reconnect to last known-good gateway
# Default: true
# AETHER_QUICK_RECONNECT=true

# IP version for scanning: 4 | 6 | dual
# Default: 4
# AETHER_IP_VERSION=4

# Auto-reconnect on failure
# Default: true
# AETHER_AUTO_RECONNECT=true

# Log level: debug | info | warn | error
# Default: info
# AETHER_LOG_LEVEL=info

# Extra Aether arguments (advanced)
# AETHER_EXTRA_ARGS=""
EOF
        fi
        ${EDITOR:-nano} "$USER_CONF"
        log_info "Config saved. Restart to apply changes."
        ;;
    logs)
        show_logs
        ;;
    profiles)
        show_profiles
        ;;
    *)
        usage
        exit 1
        ;;
esac
