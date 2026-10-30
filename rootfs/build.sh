#!/bin/sh
# build.sh: Builds a debian rootfs tarball for DebDroid
#
# MAINTAINER TOOL. Users never run this. It runs on a Linux desktop as root and
# produces the tarballs that get published to the releases page; a device then
# downloads and verifies one automatically during installation.
#
# Building the tarballs here rather than linking someone else's is what makes
# the manifest trustworthy: the checksums describe something reproducible from a
# recorded recipe, and debootstrap verifies every package against debian's
# signed archive while assembling it.
#
# Usage: sudo sh rootfs/build.sh [release] [arch]
#        sudo sh rootfs/build.sh bookworm arm64
#
# Requirements: debootstrap, qemu-user-static (for a foreign architecture), tar

set -e

RELEASE=${1:-bookworm}
ARCH=${2:-arm64}
MIRROR=${MIRROR:-http://deb.debian.org/debian}

ROOTFS_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=${ROOTFS_DIR%/*}
BUILD_DIR=${BUILD_DIR:-$REPO_DIR/out/rootfs}
TARGET="$BUILD_DIR/debian-$RELEASE-$ARCH"
TARBALL="$BUILD_DIR/debian-$RELEASE-$ARCH.tar.gz"

# Packages a usable debian needs but debootstrap's minimal set omits
INCLUDE=${INCLUDE:-ca-certificates,locales,sudo,nano,less,procps,iproute2,iputils-ping,dbus-x11}

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: This must run as root, as debootstrap creates device nodes." >&2
    exit 1
fi

for tool in debootstrap tar; do
    if ! command -v "$tool" > /dev/null 2>&1; then
        echo "Error: \"$tool\" is required." >&2
        exit 1
    fi
done

# A foreign architecture needs qemu to run the second stage
case "$ARCH" in
    arm64|armhf|armel)
        if [ "$(dpkg --print-architecture 2>/dev/null)" != "$ARCH" ] &&
                ! command -v qemu-aarch64-static > /dev/null 2>&1; then
            echo "Error: qemu-user-static is required to build $ARCH here." >&2
            exit 1
        fi
        ;;
esac

echo ":: Building a $RELEASE rootfs for $ARCH"

rm -rf "$TARGET"
mkdir -p "$TARGET" "$BUILD_DIR"

echo " -> Stage 1: unpacking the base system"
debootstrap --arch="$ARCH" --foreign --variant=minbase \
    --include="$INCLUDE" "$RELEASE" "$TARGET" "$MIRROR"

# The second stage runs the target's own binaries, so it needs the emulator
if [ "$(dpkg --print-architecture 2>/dev/null)" != "$ARCH" ]; then
    echo " -> Copying the qemu interpreter in"
    case "$ARCH" in
        arm64) cp /usr/bin/qemu-aarch64-static "$TARGET"/usr/bin/ ;;
        armhf|armel) cp /usr/bin/qemu-arm-static "$TARGET"/usr/bin/ ;;
    esac
fi

echo " -> Stage 2: configuring the base system"
chroot "$TARGET" /debootstrap/debootstrap --second-stage

echo " -> Applying the DebDroid fixes"

# The inet group is what android checks before allowing any socket, so it has
# to exist before the first session rather than being created on the fly
chroot "$TARGET" sh -c 'getent group inet > /dev/null 2>&1 || groupadd -g 3003 inet'
chroot "$TARGET" sh -c 'id _apt > /dev/null 2>&1 && usermod -g inet _apt' || true

# NOTE: These are the two files the manager rewrites at every session start.
#       Shipping them as regular files rather than symlinks means the rewrite
#       cannot be redirected out of the image by an absolute symlink.
rm -f "$TARGET"/etc/resolv.conf "$TARGET"/etc/hosts
: > "$TARGET"/etc/resolv.conf
: > "$TARGET"/etc/hosts
chmod 644 "$TARGET"/etc/resolv.conf "$TARGET"/etc/hosts

# The directories the services DebDroid starts expect to find
mkdir -p "$TARGET"/run/sshd "$TARGET"/run/dbus "$TARGET"/debdroid/bin \
    "$TARGET"/debdroid/lib
chmod 755 "$TARGET"/run/sshd "$TARGET"/run/dbus "$TARGET"/debdroid

cat > "$TARGET"/etc/apt/sources.list <<EOF
deb $MIRROR $RELEASE main contrib non-free non-free-firmware
deb $MIRROR ${RELEASE}-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security ${RELEASE}-security main contrib non-free non-free-firmware
EOF

echo " -> Cleaning up"
chroot "$TARGET" apt-get clean
rm -rf "$TARGET"/var/lib/apt/lists/* "$TARGET"/var/cache/apt/archives/*.deb \
       "$TARGET"/debootstrap
rm -f "$TARGET"/usr/bin/qemu-*-static

echo " -> Creating the tarball"
rm -f "$TARBALL"
tar -czf "$TARBALL" -C "$TARGET" .

SIZE=$(du -sb "$TARGET" | cut -f 1)
SHA=$(sha256sum "$TARBALL" | cut -d ' ' -f 1)

echo
echo ":: Built $TARBALL"
echo
echo "Add this line to rootfs/manifest:"
echo
echo "$RELEASE-$ARCH | TARBALL | $ARCH | $RELEASE | $SHA | $SIZE | <url>"
echo
