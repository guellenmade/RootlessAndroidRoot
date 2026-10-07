#!/system/bin/sh
# Install an APK inside the container via session-based install.
# Executed INSIDE the container. Argument: absolute path of the staged APK.
set -eu
APK="$1"

SESSION="$(/system/bin/pm install-create -r 2>/dev/null | grep -oE '\[[0-9]+\]' | tr -d '[]')"
/system/bin/pm install-write -S base.apk "${SESSION}" "${APK}"
/system/bin/pm install-commit "${SESSION}"
/system/bin/rm -f "${APK}"
