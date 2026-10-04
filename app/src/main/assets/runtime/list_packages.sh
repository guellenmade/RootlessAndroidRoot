#!/system/bin/sh
# Lists installed packages inside the container with label markers.
# Output: <packageName>\t<versionName>\t<firstInstallTime>
set -eu
/system/bin/pm list packages -f 2>/dev/null | sed 's/package://' | sort
