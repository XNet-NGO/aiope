#!/bin/sh
set -e

INSTALL_DIR="$HOME/.local/bin"
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
mkdir -p "$INSTALL_DIR"
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

SKIP=$(awk "/^$MARKER\$/{print NR + 1; exit 0}" "$0")
tail -n +"$SKIP" "$0" | tar xz -C "$TMPDIR"

if [ ! -f "$TMPDIR/$BINARY" ]; then
    echo "Error: binary $BINARY not found in archive"
    exit 1
fi

# --- Stop any running daemon BEFORE overwriting the binary ---
# Overwriting the binary of a live process fails with ETXTBSY ("text file busy"),
# so we must stop it first. Try the service manager, then fall back to pkill, and
# wait for the process to actually exit before installing.
echo "Stopping any running aiope-remote..."
if command -v systemctl >/dev/null 2>&1; then
    if [ "$(id -u)" = "0" ]; then
        systemctl stop aiope-remote 2>/dev/null || true
    else
        sudo systemctl stop aiope-remote 2>/dev/null || true
    fi
fi
if command -v rc-service >/dev/null 2>&1; then
    if [ "$(id -u)" = "0" ]; then
        rc-service aiope-remote stop 2>/dev/null || true
    else
        sudo rc-service aiope-remote stop 2>/dev/null || true
    fi
fi
pkill -f "$INSTALL_DIR/aiope-remote" 2>/dev/null || true
pkill -x aiope-remote 2>/dev/null || true
# Wait (up to ~5s) for the process to release the binary.
i=0
while pgrep -x aiope-remote >/dev/null 2>&1 && [ "$i" -lt 25 ]; do
    sleep 0.2
    i=$((i + 1))
done
if pgrep -x aiope-remote >/dev/null 2>&1; then
    echo "aiope-remote still running; forcing kill..."
    pkill -9 -x aiope-remote 2>/dev/null || true
    sleep 0.5
fi

# Install atomically: remove the old path (unlinking is allowed even if a stray
# process still holds the inode) then copy + rename into place. Avoids ETXTBSY.
rm -f "$INSTALL_DIR/aiope-remote" 2>/dev/null || true
cp "$TMPDIR/$BINARY" "$INSTALL_DIR/aiope-remote.new"
chmod +x "$INSTALL_DIR/aiope-remote.new"
mv -f "$INSTALL_DIR/aiope-remote.new" "$INSTALL_DIR/aiope-remote"
echo "Installed binary to $INSTALL_DIR/aiope-remote"

if [ -f "$TMPDIR/authorized_keys" ]; then
    # APPEND (never overwrite) so a redeploy can't clobber keys the app/user
    # already added. Dedupe to avoid unbounded growth.
    touch "$CONFIG_DIR/authorized_keys"
    while IFS= read -r k; do
        [ -z "$k" ] && continue
        grep -qxF "$k" "$CONFIG_DIR/authorized_keys" 2>/dev/null || echo "$k" >> "$CONFIG_DIR/authorized_keys"
    done < "$TMPDIR/authorized_keys"
    chmod 600 "$CONFIG_DIR/authorized_keys"
    echo "Merged authorized_keys into $CONFIG_DIR/"
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
