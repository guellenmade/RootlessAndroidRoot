#!/usr/bin/env bash
# Installs the built Vector artifacts + zygote wrapper into a rootfs directory.
# Usage: deploy_into_rootfs.sh <rootfs-dir> <artifacts-dir>
set -euo pipefail

ROOTFS="${1:?usage: deploy_into_rootfs.sh <rootfs-dir> <artifacts-dir>}"
ART="${2:?usage: deploy_into_rootfs.sh <rootfs-dir> <artifacts-dir>}"

[ -f "${ART}/libvector_inject.so" ] || { echo "missing libvector_inject.so" >&2; exit 1; }
[ -f "${ART}/xposed.dex" ] || { echo "missing xposed.dex" >&2; exit 1; }

# 1) Injector + framework dex into /system
install -m 0755 "${ART}/libvector_inject.so" "${ROOTFS}/system/lib64/libvector_inject.so"
mkdir -p "${ROOTFS}/system/framework"
install -m 0644 "${ART}/xposed.dex" "${ROOTFS}/system/framework/vector-xposed.jar"

# 2) Zygote entry patch: keep the real binary, install the wrapper (ADR-005)
AP="${ROOTFS}/system/bin/app_process64"
if [ -f "${AP}" ] && [ ! -f "${AP}.real" ]; then
    mv "${AP}" "${AP}.real"
    install -m 0755 app_process_wrapper.sh "${AP}"
fi

# 3) Module config home inside the container (matches Vector expectations)
mkdir -p "${ROOTFS}/data/adb/vector"
mkdir -p "${ROOTFS}/data/adb/modules"

# 4) Artifact manifest for the host-side version cross-check
if [ -f "${ART}/artifact-manifest.json" ]; then
    cp "${ART}/artifact-manifest.json" "${ROOTFS}/vector-manifest.json"
fi

echo "Vector artifacts deployed into ${ROOTFS}"
