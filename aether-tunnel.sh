#!/bin/bash
set -e

# =============================================================================
# Aether Tunnel - Censorship circumvention with system-wide routing
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
PID_FILE="$DATA_DIR/aether-tunnel.pid"
STATE_FILE="$DATA_DIR/state"
RECONNECT_DELAY=5
MAX_RECONNECT_ATTEMPTS=0  # 0 = unlimited

AETHER_PID=""
TUNNEL_PID=""

# =============================================================================
# Default Configuration
# =============================================================================

# Protocol: gool | masque | wireguard
PROTOCOL="gool"

# Scan mode: quick | balanced | turbo
SCAN_MODE="balanced"

# SOCKS5 proxy port
SOCKS_PORT=1819

# TUN device settings
TUN_NAME="tun0"
TUN_IP="198.18.0.1"
TUN_IPV6="fc00::1"

# Routing
SOCKS_MARK=438

# Behavior
AUTO_RECONNECT=true
AUTO_CONNECT=false
VERBOSE=false
LOG_LEVEL="info"
DAEMON_MODE=false

# =============================================================================
# Functions
# =============================================================================

usage() {
    cat <<EOF
Aether Tunnel - Censorship circumvention with system-wide routing

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

Options:
    -g, --gool          Use nested WireGuard (gool) [DEFAULT]
    -m, --masque        Use MASQUE protocol
    -w, --wireguard     Use WireGuard protocol
    -s, --scan MODE     Scan mode: quick|balanced|turbo (default: balanced)
    -p, --port PORT     Local SOCKS5 port (default: 1819)
    -t, --tun NAME      TUN device name (default: tun0)
    -n, --no-reconnect  Disable auto-reconnect
    -d, --daemon        Run in background (daemon mode)
    -v, --verbose       Verbose logging
    -h, --help          Show this help

Configuration:
    Config file: $USER_CONF

    To customize settings, edit the config file or use environment variables:
        AETHER_PROTOCOL=gool
        AETHER_SCAN=balanced
        AETHER_PORT=1819
        AETHER_TUN=tun0
        AETHER_AUTO_RECONNECT=true

Examples:
    $(basename "$0") start                           # Start with defaults (gool)
    $(basename "$0") start --masque --scan turbo     # Use MASQUE, fast scan
    $(basename "$0") start --daemon                  # Run in background
    $(basename "$0") start --no-reconnect            # Disable auto-reconnect
    $(basename "$0") stop
    $(basename "$0") status
    $(basename "$0") enable                          # Start on boot
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
    
    # Kill any remaining processes
    pkill -f "hev-socks5-tunnel" 2>/dev/null || true
    pkill -f "aether" 2>/dev/null || true
    
    # Cleanup routing
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
        # Source user config (environment variables)
        set -a
        source "$USER_CONF"
        set +a
        
        # Apply config values if set
        [ -n "$AETHER_PROTOCOL" ] && PROTOCOL="$AETHER_PROTOCOL"
        [ -n "$AETHER_SCAN" ] && SCAN_MODE="$AETHER_SCAN"
        [ -n "$AETHER_PORT" ] && SOCKS_PORT="$AETHER_PORT"
        [ -n "$AETHER_TUN" ] && TUN_NAME="$AETHER_TUN"
        [ -n "$AETHER_TUN_IP" ] && TUN_IP="$AETHER_TUN_IP"
        [ -n "$AETHER_TUN_IPV6" ] && TUN_IPV6="$AETHER_TUN_IPV6"
        [ -n "$AETHER_MARK" ] && SOCKS_MARK="$AETHER_MARK"
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
  log-level: $LOG_LEVEL
EOF
    log_debug "Generated tunnel config: $TUNNEL_CONF"
}

setup_routing() {
    log_info "Setting up routing rules..."
    
    # Disable reverse path filter
    sysctl -w net.ipv4.conf.all.rp_filter=0 2>/dev/null || true
    sysctl -w "net.ipv4.conf.$TUN_NAME.rp_filter=0" 2>/dev/null || true
    
    # Bypass upstream socks5 server
    ip rule add fwmark "$SOCKS_MARK" lookup main pref 10 2>/dev/null || true
    ip -6 rule add fwmark "$SOCKS_MARK" lookup main pref 10 2>/dev/null || true
    
    # Route others through tunnel
    ip route add default dev "$TUN_NAME" table 20 2>/dev/null || true
    ip rule add lookup 20 pref 20 2>/dev/null || true
    ip -6 route add default dev "$TUN_NAME" table 20 2>/dev/null || true
    ip -6 rule add lookup 20 pref 20 2>/dev/null || true
    
    log_info "Routing configured"
}

wait_for_socks5() {
    local max_wait=30
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
    log_info "Starting Aether (protocol: $PROTOCOL, scan: $SCAN_MODE)..."
    
    local cmd="$AETHER_BIN"
    
    case "$PROTOCOL" in
        masque)     cmd="$cmd --masque" ;;
        wireguard)  cmd="$cmd --wireguard" ;;
        gool)       cmd="$cmd --gool" ;;
        *)          log_error "Unknown protocol: $PROTOCOL"; return 1 ;;
    esac
    
    cmd="$cmd --scan $SCAN_MODE"
    
    if [ -n "${AETHER_EXTRA_ARGS:-}" ]; then
        cmd="$cmd $AETHER_EXTRA_ARGS"
    fi
    
    log_debug "Running: $cmd"
    
    # Start Aether in background
    $cmd &
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
    
    # Generate tunnel config
    generate_tunnel_config
    
    # Start Aether
    if ! start_aether; then
        log_error "Failed to start Aether"
        rm -f "$PID_FILE"
        exit 1
    fi
    
    # Start tunnel
    if ! start_tunnel; then
        log_error "Failed to start tunnel"
        kill "$AETHER_PID" 2>/dev/null || true
        rm -f "$PID_FILE"
        exit 1
    fi
    
    # Setup routing
    setup_routing
    
    save_state "running"
    
    log_info "=========================================="
    log_info "  Aether Tunnel is running"
    log_info "  Protocol: $PROTOCOL"
    log_info "  SOCKS5:   127.0.0.1:$SOCKS_PORT"
    log_info "  TUN:      $TUN_NAME ($TUN_IP)"
    log_info "=========================================="
    log_info "Press Ctrl+C to stop"
    
    # Monitor processes
    monitor_services
}

monitor_services() {
    local reconnect_count=0
    
    while true; do
        # Check if Aether is still running
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
        
        # Check if tunnel is still running
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
    
    # If we exited the loop, clean up
    save_state "stopped"
    rm -f "$PID_FILE"
}

show_status() {
    load_user_config
    
    echo ""
    echo "  Aether Tunnel Status"
    echo "  ===================="
    echo ""
    
    # Check main process
    if is_running; then
        echo "  Service:    RUNNING (PID: $(cat "$PID_FILE"))"
    else
        echo "  Service:    STOPPED"
    fi
    
    # Check Aether
    if pgrep -f "aether" >/dev/null 2>&1; then
        echo "  Aether:     RUNNING (PID: $(pgrep -f aether | head -1))"
    else
        echo "  Aether:     STOPPED"
    fi
    
    # Check tunnel
    if pgrep -f "hev-socks5-tunnel" >/dev/null 2>&1; then
        echo "  Tunnel:     RUNNING (PID: $(pgrep -f hev-socks5-tunnel | head -1))"
    else
        echo "  Tunnel:     STOPPED"
    fi
    
    # Check SOCKS5 port
    if ss -tlnp 2>/dev/null | grep -q ":$SOCKS_PORT "; then
        echo "  SOCKS5:     LISTENING on 127.0.0.1:$SOCKS_PORT"
    else
        echo "  SOCKS5:     NOT LISTENING"
    fi
    
    # Check TUN device
    if ip link show "$TUN_NAME" >/dev/null 2>&1; then
        echo "  TUN:        UP ($TUN_NAME)"
    else
        echo "  TUN:        DOWN"
    fi
    
    echo ""
    echo "  Configuration"
    echo "  ============="
    echo ""
    echo "  Protocol:       $PROTOCOL"
    echo "  Scan mode:      $SCAN_MODE"
    echo "  Auto-reconnect: $AUTO_RECONNECT"
    echo "  Config file:    $USER_CONF"
    echo ""
}

enable_autostart() {
    log_info "Enabling auto-start on boot..."
    
    # Detect init system
    if command -v systemctl >/dev/null 2>&1; then
        # systemd
        cat > /tmp/aether-tunnel.service <<EOF
[Unit]
Description=Aether Tunnel - Censorship Circumvention
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$SCRIPT_DIR/aether-tunnel.sh start --daemon
ExecStop=$SCRIPT_DIR/aether-tunnel.sh stop
Restart=on-failure
RestartSec=10
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
        
        sudo mv /tmp/aether-tunnel.service /etc/systemd/system/
        sudo systemctl daemon-reload
        sudo systemctl enable aether-tunnel
        
        log_info "Enabled with systemd"
    else
        # Try to add to crontab
        (crontab -l 2>/dev/null; echo "@reboot $SCRIPT_DIR/aether-tunnel.sh start --daemon") | crontab -
        log_info "Enabled with crontab"
    fi
    
    log_info "Auto-start enabled"
}

disable_autostart() {
    log_info "Disabling auto-start on boot..."
    
    if command -v systemctl >/dev/null 2>&1; then
        sudo systemctl disable aether-tunnel 2>/dev/null || true
        sudo rm -f /etc/systemd/system/aether-tunnel.service
        sudo systemctl daemon-reload
    else
        crontab -l 2>/dev/null | grep -v "aether-tunnel" | crontab -
    fi
    
    log_info "Auto-start disabled"
}

show_logs() {
    if [ -f "$LOG_DIR/aether-tunnel.log" ]; then
        tail -n 50 "$LOG_DIR/aether-tunnel.log"
    else
        echo "No logs found"
    fi
}

# =============================================================================
# Main
# =============================================================================

# Parse arguments
COMMAND=""

while [ $# -gt 0 ]; do
    case "$1" in
        start|stop|status|restart|enable|disable|scan|config|logs)
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
        -p|--port)
            shift
            SOCKS_PORT="$1"
            ;;
        -t|--tun)
            shift
            TUN_NAME="$1"
            ;;
        -n|--no-reconnect)
            AUTO_RECONNECT=false
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

# Default command
if [ -z "$COMMAND" ]; then
    COMMAND="start"
fi

# Load config file
load_user_config

# Execute command
case "$COMMAND" in
    start)
        if [ "$DAEMON_MODE" = "true" ]; then
            # Run in background
            nohup "$0" start > "$LOG_DIR/aether-tunnel.log" 2>&1 &
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
            nohup "$0" start > "$LOG_DIR/aether-tunnel.log" 2>&1 &
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
        log_info "Running Aether scan..."
        $AETHER_BIN --scan "$SCAN_MODE" --scan-only
        ;;
    config)
        mkdir -p "$CONF_DIR"
        if [ ! -f "$USER_CONF" ]; then
            cat > "$USER_CONF" <<'EOF'
# Aether Tunnel Configuration
# Uncomment and modify values as needed

# Protocol: gool | masque | wireguard
# AETHER_PROTOCOL=gool

# Scan mode: quick | balanced | turbo
# AETHER_SCAN=balanced

# SOCKS5 proxy port
# AETHER_PORT=1819

# TUN device name
# AETHER_TUN=tun0

# TUN IPv4 address
# AETHER_TUN_IP=198.18.0.1

# TUN IPv6 address
# AETHER_TUN_IPV6=fc00::1

# SOCKS mark for routing bypass
# AETHER_MARK=438

# Auto-reconnect on failure: true | false
# AETHER_AUTO_RECONNECT=true

# Log level: debug | info | warn | error
# AETHER_LOG_LEVEL=info

# Extra Aether arguments
# AETHER_EXTRA_ARGS=""
EOF
        fi
        ${EDITOR:-nano} "$USER_CONF"
        log_info "Config saved. Restart to apply changes."
        ;;
    logs)
        show_logs
        ;;
    *)
        usage
        exit 1
        ;;
esac
