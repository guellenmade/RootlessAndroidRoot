#!/usr/bin/env bash
# Builds an Android 13 (LineageOS 20 / API 33) rootfs tarball for one ABI
# from the official Waydroid OTA images (GPL/Apache LineageOS-based, VANILLA
# = no GMS; redistribution allowed).
# Usage: build_rootfs.sh <workdir> <abi>   (abi: arm64-v8a | x86_64)
#
# Sources (pinned; verified via the waydroid/OTA manifests):
#   system: lineage-20.0-20260927-VANILLA-waydroid_<arch>-system.zip
#   vendor: MAINLINE vendor image from the same OTA channel
# Extraction is userspace-only (no root, no loop mounts):
#   sparse -> unsparsed; ext4 -> debugfs rdump; erofs -> fsck.erofs --extract
set -euo pipefail

WORKDIR="${1:?usage: build_rootfs.sh <workdir> <abi>}"
ABI="${2:?usage: build_rootfs.sh <workdir> <abi>}"

case "${ABI}" in
arm64-v8a) WD_ARCH="arm64" ;;
x86_64) WD_ARCH="x86_64" ;;
*)
    echo "unsupported ABI for rootfs images: ${ABI}" >&2
    exit 1
    ;;
esac

# Pinned OTA entries (see AGENT.md ADR-010; update together with sha256s).
SYSTEM_ZIP_URL="https://sourceforge.net/projects/waydroid/files/images/system/lineage/waydroid_${WD_ARCH}/lineage-20.0-20260927-VANILLA-waydroid_${WD_ARCH}-system.zip/download"
VENDOR_ZIP_URL="https://sourceforge.net/projects/waydroid/files/images/vendor/waydroid_${WD_ARCH}/mainline/lineage-20.0-20260927-MAINLINE-waydroid_${WD_ARCH}-vendor.zip/download"

mkdir -p "${WORKDIR}"
cd "${WORKDIR}"

fetch() {
    # SourceForge /download URLs redirect to a mirror; -L follows.
    curl -fL --retry 5 --retry-delay 10 -o "$2" "$1"
}

# ---- 1) download + unzip ----
if [ ! -f system.img ]; then
    fetch "${SYSTEM_ZIP_URL}" system.zip
    unzip -o system.zip
fi
if [ ! -f vendor.img ]; then
    fetch "${VENDOR_ZIP_URL}" vendor.zip
    unzip -o vendor.zip 'images/*' 2>/dev/null || unzip -o vendor.zip
    # vendor zips sometimes nest under images/
    [ -f vendor.img ] || [ -f images/vendor.img ] && mv -v images/vendor.img vendor.img 2>/dev/null || true
fi
[ -f system.img ] || { echo "system.img missing after unzip" >&2; exit 1; }
[ -f vendor.img ] || { echo "vendor.img missing after unzip" >&2; exit 1; }

# ---- 2) unsparse if needed (pure-python, no host tools) ----
unsparse() {
    local src="$1" dst="$2"
    python3 - "${src}" "${dst}" <<'PYEOF'
import sys, struct
src, dst = sys.argv[1], sys.argv[2]
with open(src, "rb") as f:
    magic = f.read(4)
    if magic != b"\x3a\xff\x26\xed":
        f.seek(0)
        with open(dst, "wb") as o:
            while True:
                chunk = f.read(1 << 24)
                if not chunk:
                    break
                o.write(chunk)
        sys.exit(0)
    hdr = f.read(28)
    _, _, _, _, blk_sz = struct.unpack("<IHHHQI", magic + hdr[:18])
    with open(dst, "wb") as o:
        while True:
            ch = f.read(12)
            if not ch or len(ch) < 12:
                break
            ctype, cnum, clen = struct.unpack("<HHI", ch)
            data = f.read(clen)
            if ctype == 0xCAC1:
                for _ in range(cnum):
                    o.write(data[:blk_sz])
                    data = data[blk_sz:]
            elif ctype == 0xCAC2:
                fill = data[:4] * blk_sz
                for _ in range(cnum):
                    o.write(fill)
            elif ctype == 0xCAC3:
                o.write(b"\x00" * (cnum * blk_sz))
PYEOF
}

fs_type() {
    python3 - "$1" <<'PYEOF'
import sys
with open(sys.argv[1], "rb") as f:
    f.seek(1024)
    sb = f.read(64)
    if sb[56:58] == b"\x53\xef":
        print("ext4"); sys.exit(0)
    if sb[0:4] == b"\xe0\xf5\xe1\xe2":
        print("erofs"); sys.exit(0)
print("unknown")
PYEOF
}

extract() {
    local img="$1" out="$2"
    rm -rf "${out}"
    mkdir -p "${out}"
    case "$(fs_type "${img}")" in
    ext4)
        debugfs -R "rdump / ${out}" "${img}" 2>"${out}.debugfs.log" || {
            echo "debugfs rdump failed for ${img}:" >&2
            tail -20 "${out}.debugfs.log" >&2
            exit 1
        }
        ;;
    erofs)
        fsck.erofs --extract="${out}" "${img}"
        ;;
    *)
        echo "unrecognized image format: ${img}" >&2
        exit 1
        ;;
    esac
}

unsparse system.img system-raw.img
mv system-raw.img system.img
unsparse vendor.img vendor-raw.img
mv vendor-raw.img vendor.img

# ---- 3) extract into one rootfs ----
extract system.img "${WORKDIR}/rootfs-system"
extract vendor.img "${WORKDIR}/rootfs-vendor"

ROOTFS="${WORKDIR}/rootfs"
rm -rf "${ROOTFS}"
mkdir -p "${ROOTFS}"
# system-as-root: the system image is the container root (/), and /system
# is a symlink to '.' inside it. Merge vendor under /vendor.
cp -a "${WORKDIR}/rootfs-system/." "${ROOTFS}/"
rm -rf "${ROOTFS}/vendor" 2>/dev/null || true
cp -a "${WORKDIR}/rootfs-vendor/." "${ROOTFS}/vendor/"

# Sanity: the container runtime needs these paths.
[ -e "${ROOTFS}/system/bin/sh" ] || [ -e "${ROOTFS}/bin/sh" ] || { echo "no /system/bin/sh in rootfs" >&2; exit 1; }
[ -e "${ROOTFS}/system/bin/app_process64" ] || [ -e "${ROOTFS}/bin/app_process64" ] || { echo "no app_process64 in rootfs" >&2; exit 1; }

# ---- 4) package ----
OUT="${WORKDIR}/out"
mkdir -p "${OUT}"
TARBALL="${OUT}/rootfs-aosp-13-${ABI}.tar.xz"
tar -C "${ROOTFS}" --owner=0 --group=0 -cJf "${TARBALL}" .
(cd "${OUT}" && sha256sum "rootfs-aosp-13-${ABI}.tar.xz" > "rootfs-aosp-13-${ABI}.tar.xz.sha256")
du -h "${TARBALL}"
cat "${OUT}"/*.sha256
echo "built ${TARBALL}"
