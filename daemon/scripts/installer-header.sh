#!/bin/sh
set -e

INSTALL_DIR="/usr/local/bin"
CONFIG_DIR="$HOME/.aiope"
PORT="${AIOPE_PORT:-2222}"
MARKER="__ARCHIVE_BELOW__"

echo "================================"
echo "  aiope-remote installer"
echo "================================"

ARCH=$(uname -m)
case "$ARCH" in
    x86_64)  BINARY="aiope-remote-linux-amd64" ;;
    aarch64) BINARY="aiope-remote-linux-arm64" ;;
    armv7l)  BINARY="aiope-remote-linux-arm" ;;
    *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac

echo "Detected: $ARCH -> $BINARY"

mkdir -p "$CONFIG_DIR"
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

SKIP=$(awk "/^$MARKER\$/{print NR + 1; exit 0}" "$0")
tail -n +"$SKIP" "$0" | tar xz -C "$TMPDIR"

if [ ! -f "$TMPDIR/$BINARY" ]; then
    echo "Error: binary $BINARY not found in archive"
    exit 1
fi

cp "$TMPDIR/$BINARY" "$INSTALL_DIR/aiope-remote"
chmod +x "$INSTALL_DIR/aiope-remote"
echo "Installed binary to $INSTALL_DIR/aiope-remote"

if [ -f "$TMPDIR/authorized_keys" ]; then
    cp "$TMPDIR/authorized_keys" "$CONFIG_DIR/authorized_keys"
    chmod 600 "$CONFIG_DIR/authorized_keys"
    echo "Installed authorized_keys to $CONFIG_DIR/"
fi

VER=$("$INSTALL_DIR/aiope-remote" --version 2>/dev/null || echo "unknown")
echo "Version: $VER"

if command -v systemctl >/dev/null 2>&1; then
    echo "Detected systemd"
    cat > /tmp/aiope-remote.service << SVCEOF
[Unit]
Description=AIOPE Remote
After=network.target

[Service]
ExecStart=$INSTALL_DIR/aiope-remote
Restart=always
User=$(whoami)
Environment=AIOPE_PORT=$PORT
Environment=AIOPE_CONFIG_DIR=$CONFIG_DIR

[Install]
WantedBy=multi-user.target
SVCEOF

    if [ "$(id -u)" = "0" ]; then
        mv /tmp/aiope-remote.service /etc/systemd/system/
        systemctl daemon-reload
        systemctl enable --now aiope-remote
    else
        sudo mv /tmp/aiope-remote.service /etc/systemd/system/ 2>/dev/null && \
        sudo systemctl daemon-reload && \
        sudo systemctl enable --now aiope-remote || {
            echo "Could not install systemd service (no sudo). Starting manually."
            pkill -f aiope-remote 2>/dev/null || true
            nohup "$INSTALL_DIR/aiope-remote" > "$CONFIG_DIR/daemon.log" 2>&1 &
            echo $! > "$CONFIG_DIR/daemon.pid"
        }
    fi
elif command -v rc-service >/dev/null 2>&1; then
    echo "Detected OpenRC"
    cat > /tmp/aiope-remote << RCEOF
#!/sbin/openrc-run
name="aiope-remote"
command="$INSTALL_DIR/aiope-remote"
command_background=true
pidfile="/run/aiope-remote.pid"
RCEOF
    if [ "$(id -u)" = "0" ]; then
        mv /tmp/aiope-remote /etc/init.d/aiope-remote
        chmod +x /etc/init.d/aiope-remote
        rc-update add aiope-remote default
        rc-service aiope-remote start
    else
        sudo mv /tmp/aiope-remote /etc/init.d/aiope-remote 2>/dev/null && \
        sudo chmod +x /etc/init.d/aiope-remote && \
        sudo rc-update add aiope-remote default && \
        sudo rc-service aiope-remote start || {
            echo "Could not install OpenRC service. Starting manually."
            pkill -f aiope-remote 2>/dev/null || true
            nohup "$INSTALL_DIR/aiope-remote" > "$CONFIG_DIR/daemon.log" 2>&1 &
            echo $! > "$CONFIG_DIR/daemon.pid"
        }
    fi
else
    echo "No init system detected. Starting manually."
    pkill -f aiope-remote 2>/dev/null || true
    nohup "$INSTALL_DIR/aiope-remote" > "$CONFIG_DIR/daemon.log" 2>&1 &
    echo $! > "$CONFIG_DIR/daemon.pid"
fi

sleep 1

# Open port for daemon
if command -v ufw >/dev/null 2>&1; then
    if [ "$(id -u)" = "0" ]; then
        ufw allow "$PORT"/tcp >/dev/null 2>&1 && echo "ufw: allowed port $PORT"
    else
        sudo ufw allow "$PORT"/tcp >/dev/null 2>&1 && echo "ufw: allowed port $PORT"
    fi
elif command -v iptables >/dev/null 2>&1; then
    if [ "$(id -u)" = "0" ]; then
        iptables -C INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null || \
        iptables -I INPUT -p tcp --dport "$PORT" -j ACCEPT && echo "iptables: allowed port $PORT"
    else
        sudo iptables -C INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null || \
        sudo iptables -I INPUT -p tcp --dport "$PORT" -j ACCEPT && echo "iptables: allowed port $PORT"
    fi
fi

if pgrep -f aiope-remote >/dev/null 2>&1; then
    PID=$(pgrep -f "aiope-remote" | head -1)
    echo ""
    echo "================================"
    echo "  aiope-remote is running"
    echo "  Port: $PORT"
    echo "  PID:  $PID"
    echo "  Arch: $ARCH"
    echo "================================"
else
    echo "Warning: daemon may not have started. Check $CONFIG_DIR/daemon.log"
    exit 1
fi

rm -f "$0"
exit 0

__ARCHIVE_BELOW__
