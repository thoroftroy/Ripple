#!/bin/sh
# Builds the Linux release tarball: dist/ripple-linux-x86_64-<version>.tar.gz
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../core" && cargo build --release -p ripple -q
VER="$("$HERE/../core/target/release/ripple" version | cut -d' ' -f2)"
OUT="$HERE/dist/ripple-$VER"
rm -rf "$OUT"; mkdir -p "$OUT/bin"
cp "$HERE/../core/target/release/ripple" "$OUT/bin/"
cp "$HERE/install.sh" "$HERE/ripple_terminal.py" "$HERE/ripple.png" "$OUT/"
cp "$HERE/README.md" "$OUT/" 2>/dev/null || true
tar -C "$HERE/dist" -czf "$HERE/dist/ripple-linux-x86_64-$VER.tar.gz" "ripple-$VER"
echo "$HERE/dist/ripple-linux-x86_64-$VER.tar.gz"
