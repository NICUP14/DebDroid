#!/bin/sh
# verify_applets.sh: Checks that a busybox build provides what DebDroid needs
#
# The whole project is built on busybox applets, so a build configured without
# one of them fails at runtime on someone's phone rather than at build time.
# Run this on every busybox binary before shipping it.
#
# Usage: sh package/verify_applets.sh <path-to-busybox>
#
# The binary has to be runnable here, so either build for the host or register
# qemu-user-static with binfmt first:
#   docker run --rm --privileged multiarch/qemu-user-static --reset -p yes

BUSYBOX=$1

if [ -z "$BUSYBOX" ] || [ ! -f "$BUSYBOX" ]; then
    echo "Usage: sh package/verify_applets.sh <path-to-busybox>" >&2
    exit 1
fi

# Applets the scripts call today, so a missing one is a broken build
REQUIRED='awk cat chmod chroot cp cut dd df du flock getprop grep head hostname
id kill ln ls mkdir mount mountpoint printf readlink rm sed seq sleep stat
sysctl tac tail tar truncate umount uname wc'

# Applets the newer features need
REQUIRED="$REQUIRED nsenter setsid mkswap swapon swapoff sha256sum wget
mke2fs tune2fs fuser losetup unshare"

if ! applets=$("$BUSYBOX" --list 2>/dev/null); then
    echo "Error: Could not run \"$BUSYBOX --list\"." >&2
    echo "    Build it for this host, or register qemu-user-static." >&2
    exit 1
fi

missing=
for applet in $REQUIRED; do
    echo "$applets" | grep -qx "$applet" || missing="$missing $applet"
done

count=$(echo "$applets" | wc -l | tr -d ' ')
echo "busybox provides $count applets"

if [ -n "$missing" ]; then
    echo "Error: This build is missing applets DebDroid needs:" >&2
    for applet in $missing; do
        echo "    $applet" >&2
    done
    exit 1
fi

# ssl_client is what lets wget speak https without a separate helper binary
if echo "$applets" | grep -qx ssl_client; then
    echo "ssl_client: present, so no separate ssl_helper is needed"
else
    echo "ssl_client: absent, so bin/ssl_helper is still required"
    echo "    Enable CONFIG_SSL_CLIENT to drop that dependency."
fi

echo "applets: ok"
