#!/bin/sh
# refresh-image.sh: Brings the shipped debian image up to date
#
# MAINTAINER TOOL. Users never run this. It runs on a Linux desktop as root and
# updates the prebuilt image that ships with DebDroid, so a new install starts
# from something current instead of making the user sit through a year's worth
# of "apt upgrade" on first use.
#
# Run it periodically. Monthly is plenty; after a debian point release is
# better.
#
# Usage: sudo sh tools/refresh-image.sh [--parts] [--grow SIZE]
#
#   --parts       Re-split the result into img/debian.img.part-* for git
#   --grow SIZE   Enlarge the image first, if the update needs more room
#
# Requirements: root, qemu-user-static (for a foreign architecture), e2fsprogs
#
# NOTE: The image is updated in place and never reformatted. Its feature set is
#       the old-kernel-safe one (no has_journal, no metadata_csum, no 64bit) and
#       a modern mke2fs would not reproduce it, so there is no path here that
#       creates a new filesystem.

set -e

TOOLS_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=${TOOLS_DIR%/*}
WORK_DIR=${WORK_DIR:-$REPO_DIR/out/image}
IMAGE="$WORK_DIR/debian.img"
MOUNT="$WORK_DIR/mnt"
PART_SIZE=${PART_SIZE:-90M}

RESPLIT=no
GROW=

while [ $# -gt 0 ]; do
    case "$1" in
        --parts) RESPLIT=yes ;;
        --grow)
            shift
            GROW=$1
            if [ -z "$GROW" ]; then
                echo "Error: --grow needs a size, such as 2G." >&2
                exit 1
            fi
            ;;
        *)
            echo "Error: Unknown option: \"$1\"" >&2
            exit 1
            ;;
    esac
    shift
done

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: This must run as root, as it mounts the image." >&2
    exit 1
fi

for tool in e2fsck resize2fs; do
    if ! command -v "$tool" > /dev/null 2>&1; then
        echo "Error: \"$tool\" is required (install e2fsprogs)." >&2
        exit 1
    fi
done

# Records the feature set before touching anything, so it can be compared after
image_features()
{
    dumpe2fs -h "$1" 2>/dev/null |
        sed -n 's/^Filesystem features: *//p' |
        tr ' ' '\n' | sort | tr '\n' ' '
}

echo ":: Assembling the current image"
mkdir -p "$WORK_DIR" "$MOUNT"

if [ -f "$REPO_DIR"/img/debian.img ]; then
    cp "$REPO_DIR"/img/debian.img "$IMAGE"
elif [ -f "$REPO_DIR"/img/debian.img.part-aa ]; then
    cat "$REPO_DIR"/img/debian.img.part-* > "$IMAGE"
else
    echo "Error: No image found under $REPO_DIR/img." >&2
    exit 1
fi

FEATURES_BEFORE=$(image_features "$IMAGE")
echo " -> Features: $FEATURES_BEFORE"
echo " -> Size: $(du -h "$IMAGE" | cut -f 1)"

if [ -n "$GROW" ]; then
    echo ":: Growing the image to $GROW"
    e2fsck -fp "$IMAGE" || true
    resize2fs "$IMAGE" "$GROW"
    truncate -s "$GROW" "$IMAGE"
fi

echo ":: Mounting the image"
mount -o loop "$IMAGE" "$MOUNT"

# Always unmount, even when the update below fails partway
cleanup()
{
    echo ":: Unmounting"
    umount "$MOUNT"/dev 2>/dev/null || true
    umount "$MOUNT"/proc 2>/dev/null || true
    umount "$MOUNT"/sys 2>/dev/null || true
    umount "$MOUNT" 2>/dev/null || true
}
trap cleanup EXIT

# The chroot needs these to run apt, and qemu to run foreign binaries
mount -t proc proc "$MOUNT"/proc
mount --bind /dev "$MOUNT"/dev
mount -t sysfs sys "$MOUNT"/sys

ARCH=$(dumpe2fs -h "$IMAGE" 2>/dev/null > /dev/null && echo arm64)
if [ "$(dpkg --print-architecture 2>/dev/null)" != "$ARCH" ]; then
    if [ ! -x /usr/bin/qemu-aarch64-static ]; then
        echo "Error: qemu-user-static is required to update an arm64 image here." >&2
        exit 1
    fi
    cp /usr/bin/qemu-aarch64-static "$MOUNT"/usr/bin/
fi

# A working resolver, since the image ships these empty on purpose
echo "nameserver 1.1.1.1" > "$MOUNT"/etc/resolv.conf

echo ":: Updating debian"
chroot "$MOUNT" /bin/sh -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get -y upgrade
    apt-get -y dist-upgrade
    apt-get -y autoremove --purge
    apt-get clean
'

echo ":: Cleaning up inside the image"
chroot "$MOUNT" /bin/sh -c '
    rm -rf /var/lib/apt/lists/*
    rm -rf /var/cache/apt/archives/*.deb
    rm -rf /var/log/*.log /var/log/apt /var/log/dpkg.log
    rm -rf /tmp/* /root/.bash_history
'

# NOTE: These two are rewritten at every session start, and shipping them as
#       regular files rather than symlinks keeps that rewrite from being
#       redirected out of the image by an absolute symlink.
rm -f "$MOUNT"/etc/resolv.conf "$MOUNT"/etc/hosts
: > "$MOUNT"/etc/resolv.conf
: > "$MOUNT"/etc/hosts
chmod 644 "$MOUNT"/etc/resolv.conf "$MOUNT"/etc/hosts

rm -f "$MOUNT"/usr/bin/qemu-*-static

# The debian version that came out of the update, for the manifest line below
RELEASE=$(sed -n 's/^VERSION_CODENAME=//p' "$MOUNT"/etc/os-release 2>/dev/null)
[ -n "$RELEASE" ] || RELEASE=unknown

cleanup
trap - EXIT

echo ":: Checking the filesystem"
e2fsck -fp "$IMAGE" || true

# The feature set must not have drifted, as that is what old kernels reject
FEATURES_AFTER=$(image_features "$IMAGE")
if [ "$FEATURES_BEFORE" != "$FEATURES_AFTER" ]; then
    echo "Error: The filesystem features changed during the update." >&2
    echo "    before: $FEATURES_BEFORE" >&2
    echo "    after:  $FEATURES_AFTER" >&2
    echo "    Old kernels may no longer mount this image. Do not ship it." >&2
    exit 1
fi
echo " -> Features unchanged: $FEATURES_AFTER"

case "$FEATURES_AFTER" in
    *has_journal*|*metadata_csum*|*64bit*)
        echo "Error: The image gained a feature old kernels reject." >&2
        exit 1
        ;;
esac

if [ "$RESPLIT" = "yes" ]; then
    echo ":: Re-splitting into parts for git"
    rm -f "$REPO_DIR"/img/debian.img.part-*
    split -b "$PART_SIZE" "$IMAGE" "$REPO_DIR"/img/debian.img.part-
    echo " -> $(ls "$REPO_DIR"/img/debian.img.part-* | wc -l) part(s)"
fi

SHA=$(sha256sum "$IMAGE" | cut -d ' ' -f 1)
SIZE=$(wc -c < "$IMAGE" | tr -d ' ')
TODAY=$(date +%Y-%m-%d)

echo
echo ":: Refreshed $IMAGE"
echo " -> debian: $RELEASE"
echo " -> size:   $(du -h "$IMAGE" | cut -f 1)"
echo " -> sha256: $SHA"
echo
echo "Update this line in rootfs/manifest:"
echo
echo "default-arm64 | IMAGE | arm64 | $RELEASE | $SHA | $SIZE | git:img/debian.img.part-* | features=$(echo "$FEATURES_AFTER" | tr ' ' ',' | sed 's/,$//') | built=$TODAY"
echo
echo "Then: make provenance && make version"
