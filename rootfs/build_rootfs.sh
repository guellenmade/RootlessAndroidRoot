#!/usr/bin/env bash
# Builds an Android rootfs tarball for one ABI from the Apache-2.0 AOSP
# emulator system image (pure AOSP, no GMS; redistributable).
# Usage: build_rootfs.sh <workdir> <abi>   (abi: arm64-v8a | x86_64)
#
# Source: sdkmanager "system-images;android-33;default;<abi>"
# Extraction is done with userspace tools only (no root, no loop mounts):
#   - sparse images are unsparsed first (python fallback if simg2img absent)
#   - ext4:  debugfs rdump (e2fsprogs)
#   - erofs: fsck.erofs --extract (erofs-utils)
set -euo pipefail

WORKDIR="${1:?usage: build_rootfs.sh <workdir> <abi>}"
ABI="${2:?usage: build_rootfs.sh <workdir> <abi>}"
API="33"

case "${ABI}" in
arm64-v8a|x86_64) ;;
*)
    echo "unsupported ABI for rootfs images: ${ABI} (emulator images exist for arm64-v8a/x86_64 only)" >&2
    exit 1
    ;;
esac

mkdir -p "${WORKDIR}"
cd "${WORKDIR}"

SDKMGR="${ANDROID_HOME:?ANDROID_HOME must be set}/cmdline-tools/latest/bin/sdkmanager"
IMGDIR="${ANDROID_HOME}/system-images/android-${API}/default/${ABI}"

if [ ! -f "${IMGDIR}/system.img" ]; then
    yes | "${SDKMGR}" --licenses >/dev/null 2>&1 || true
    "${SDKMGR}" --verbose "system-images;android-${API};default;${ABI}"
    ls -la "${ANDROID_HOME}/system-images/android-${API}/default/" || true
fi

SYSTEM_IMG="${IMGDIR}/system.img"
[ -f "${SYSTEM_IMG}" ] || { echo "system.img not found after sdkmanager install" >&2; exit 1; }
echo "source image:"; ls -la "${IMGDIR}"

# ---- 1) unsparse if needed ----
RAW="${WORKDIR}/system-raw.img"
python3 - "${SYSTEM_IMG}" "${RAW}" <<'PYEOF'
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
    # android sparse image
    hdr = f.read(28)
    _, _, file_sz, _, blk_sz = struct.unpack("<IHHHQI", magic + hdr[:18])
    with open(dst, "wb") as o:
        while True:
            ch = f.read(12)
            if not ch or len(ch) < 12:
                break
            ctype, cnum, clen = struct.unpack("<HHI", ch)
            data = f.read(clen)
            if ctype == 0xCAC1:  # raw
                for _ in range(cnum):
                    o.write(data[:blk_sz])
                    data = data[blk_sz:]
            elif ctype == 0xCAC2:  # fill
                fill = data[:4] * blk_sz
                for _ in range(cnum):
                    o.write(fill)
            elif ctype == 0xCAC3:  # don't care
                o.write(b"\x00" * (cnum * blk_sz))
            # 0xCAC4 (crc) skipped
print("unsparsed ok", file=sys.stderr)
PYEOF

# ---- 2) detect filesystem and extract ----
ROOTFS="${WORKDIR}/rootfs"
rm -rf "${ROOTFS}"
mkdir -p "${ROOTFS}"

FS_TYPE="$(python3 - "${RAW}" <<'PYEOF'
import sys
with open(sys.argv[1], "rb") as f:
    f.seek(1024)
    sb = f.read(64)
    if sb[56:58] == b"\x53\xef":
        print("ext4")
        sys.exit(0)
    if sb[0:4] == b"\xe0\xf5\xe1\xe2":
        print("erofs")
        sys.exit(0)
print("unknown")
PYEOF
)"
echo "filesystem type: ${FS_TYPE}"

case "${FS_TYPE}" in
ext4)
    debugfs -R "rdump / ${ROOTFS}" "${RAW}" 2>"${WORKDIR}/debugfs.log" || {
        echo "debugfs rdump failed:" >&2
        tail -20 "${WORKDIR}/debugfs.log" >&2
        exit 1
    }
    ;;
erofs)
    command -v fsck.erofs >/dev/null || {
        sudo apt-get update -qq
        sudo apt-get install -y -qq erofs-utils
    }
    fsck.erofs --extract="${ROOTFS}" "${RAW}"
    ;;
*)
    echo "unrecognized image format; cannot extract without root" >&2
    exit 1
    ;;
esac

# The tarball root must contain /system (start_container.sh runs
# /system/bin/sh; entry.sh needs /system/bin/app_process64).
[ -d "${ROOTFS}/system/bin" ] || { echo "extraction did not produce /system/bin" >&2; exit 1; }

# ---- 3) package ----
OUT="${WORKDIR}/out"
mkdir -p "${OUT}"
TARBALL="${OUT}/rootfs-aosp-13-${ABI}.tar.xz"
tar -C "${ROOTFS}" --owner=0 --group=0 -cJf "${TARBALL}" .
(cd "${OUT}" && sha256sum "rootfs-aosp-13-${ABI}.tar.xz" > "rootfs-aosp-13-${ABI}.tar.xz.sha256")
du -h "${TARBALL}"
cat "${OUT}"/*.sha256
echo "built ${TARBALL}"
