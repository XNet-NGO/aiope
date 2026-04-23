#!/bin/bash
set -e

VERSION="${1:-0.1.0}"
GOBIN="${GOBIN:-go}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DIST_DIR="$PROJECT_DIR/dist"
PAYLOAD_DIR="$DIST_DIR/payload"
PUBKEY_FILE="${PUBKEY_FILE:-$HOME/.aiope/authorized_keys}"

echo "=== aiope-remote build ==="
echo "Version: $VERSION"

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR" "$PAYLOAD_DIR"

for platform in linux/amd64 linux/arm64 linux/arm; do
    os="${platform%/*}"
    arch="${platform#*/}"
    output="$PAYLOAD_DIR/aiope-remote-${os}-${arch}"
    echo "Building $platform..."
    CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" \
        $GOBIN build -ldflags="-s -w -X main.Version=$VERSION" \
        -o "$output" "$PROJECT_DIR"
    size=$(ls -lh "$output" | awk "{print \$5}")
    echo "  -> $(basename $output): $size"
done

if [ -f "$PUBKEY_FILE" ]; then
    cp "$PUBKEY_FILE" "$PAYLOAD_DIR/authorized_keys"
    echo "Included authorized_keys"
else
    echo "Warning: No authorized_keys at $PUBKEY_FILE"
fi

echo "Packing installer..."
tar czf "$DIST_DIR/payload.tar.gz" -C "$PAYLOAD_DIR" .

cat "$PROJECT_DIR/scripts/installer-header.sh" "$DIST_DIR/payload.tar.gz" > "$DIST_DIR/aiope-remote-installer.sh"
chmod +x "$DIST_DIR/aiope-remote-installer.sh"

SIZE=$(du -h "$DIST_DIR/aiope-remote-installer.sh" | cut -f1)
echo ""
echo "=== Done ==="
echo "Installer: $DIST_DIR/aiope-remote-installer.sh ($SIZE)"
