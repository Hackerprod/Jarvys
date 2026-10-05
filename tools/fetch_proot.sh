#!/usr/bin/env bash
set -euo pipefail

# Rebuild the checked-in arm64 PRoot payload from pinned Termux package artifacts.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/full/jniLibs/arm64-v8a"
BASE="https://packages.termux.dev/apt/termux-main"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$DEST"

while read -r digest filename; do
    [[ -n "${digest:-}" && "$digest" != \#* ]] || continue
    package="${filename##*/}"
    curl -fL --retry 3 "$BASE/pool/$filename" -o "$WORK/$package"
    printf '%s  %s\n' "$digest" "$WORK/$package" | sha256sum --check --status
    mkdir "$WORK/${package%.deb}"
    (cd "$WORK/${package%.deb}" && ar x "$WORK/$package" && tar -xJf data.tar.xz)
done < "$ROOT/tools/proot_checksums.txt"

PROOT="$WORK/proot_5.1.107.96_aarch64/data/data/com.termux/files/usr"
TALLOC="$WORK/libtalloc_2.5.0_aarch64/data/data/com.termux/files/usr/lib"
SHMEM="$WORK/libandroid-shmem_0.7_aarch64/data/data/com.termux/files/usr/lib"
install -m 755 "$PROOT/bin/proot" "$DEST/libproot_exec.so"
install -m 755 "$PROOT/libexec/proot/loader" "$DEST/libproot_loader.so"
install -m 755 "$TALLOC/libtalloc.so.2.5.0" "$DEST/libtalloc.so"
install -m 755 "$SHMEM/libandroid-shmem.so" "$DEST/libandroid-shmem.so"

for binary in "$DEST"/*.so; do
    readelf -h "$binary" | grep -q 'AArch64' || { echo "Not arm64 ELF: $binary" >&2; exit 1; }
done
printf 'Installed pinned PRoot payload in %s\n' "$DEST"
