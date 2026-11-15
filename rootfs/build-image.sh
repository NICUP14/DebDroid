#!/bin/sh
# build-image.sh: Builds the debian image DebDroid ships
#
# MAINTAINER TOOL. Users never run this. It runs on a Linux machine as root
# (WSL works) and produces img/debian.img.part-*, the image the installer
# assembles and the release publishes as debian.img.gz.
#
# The image used to descend from an AnLinux rootfs, which is unpacked without
# root and so lost every file's owner and mode on the way: /usr, /etc and /root
# were writable by everyone, /etc/shadow readable by everyone, and su had lost
# its setuid bit. This builds it from debootstrap instead, as rootfs/build.sh
# does, so every mode is the one debian's own packages set.
#
# Usage: sudo sh rootfs/build-image.sh [release] [arch] [size]
#        sudo sh rootfs/build-image.sh bookworm arm64 1G
#
# Requirements: debootstrap, qemu-user-static and binfmt support (for a
# foreign architecture), e2fsprogs 1.43 or later (for mke2fs -d), tar

set -e

RELEASE=${1:-bookworm}
ARCH=${2:-arm64}
SIZE=${3:-1G}
MIRROR=${MIRROR:-http://deb.debian.org/debian}

ROOTFS_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=${ROOTFS_DIR%/*}
BUILD_DIR=${BUILD_DIR:-$REPO_DIR/out/rootfs}
TARGET="$BUILD_DIR/debian-$RELEASE-$ARCH"
IMAGE="$BUILD_DIR/debian.img"

# The packages the previous image carried, so the rebuilt one does what it did
# NOTE: Read off that image's dpkg status, libraries left out as apt pulls them
#       in. It is AnLinux's default selection, kept rather than trimmed, as
#       people's setups may lean on any of it.
PACKAGES=${PACKAGES:-"busybox-static ca-certificates dbus dbus-user-session \
desktop-file-utils dmsetup dosfstools e2fsprogs eject exfatprogs fdisk fuse3 \
gdisk gnupg gpg-wks-client gpg-wks-server gpgsm gvfs gvfs-daemons krb5-locales \
lsof ntfs-3g openssl parted pinentry-curses polkitd procps psmisc \
shared-mime-info systemd systemd-sysv tzdata udev udisks2 util-linux-extra \
wget xdg-user-dirs"}

# The old-kernel-safe feature set, exactly as rootfs/manifest records it
# NOTE: ext2 and nothing newer: no has_journal, no metadata_csum, no 64bit, no
#       orphan_file. Old phone kernels refuse to mount an image with any of
#       them. "-O none" first, so mke2fs.conf on the build machine cannot add
#       a default behind this list's back.
FEATURES=dir_index,ext_attr,filetype,large_file,resize_inode,sparse_super

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: This must run as root, as debootstrap creates device nodes." >&2
    exit 1
fi

for tool in debootstrap mke2fs dumpe2fs e2fsck split sha256sum; do
    if ! command -v "$tool" > /dev/null 2>&1; then
        echo "Error: \"$tool\" is required." >&2
        exit 1
    fi
done

# Builds the base rootfs with the recipe rootfs/build.sh keeps
# NOTE: It leaves the tree in $TARGET beside its tarball, which is what the
#       image is filled from.
echo ":: Building the base rootfs"
BUILD_DIR=$BUILD_DIR MIRROR=$MIRROR sh "$ROOTFS_DIR"/build.sh "$RELEASE" "$ARCH"

cleanup()
{
    umount "$TARGET"/proc 2> /dev/null || true
    umount "$TARGET"/sys 2> /dev/null || true
    umount "$TARGET"/dev/pts 2> /dev/null || true
    umount "$TARGET"/dev 2> /dev/null || true
}
trap cleanup EXIT

echo ":: Installing the packages the previous image carried"
if [ "$(dpkg --print-architecture 2>/dev/null)" != "$ARCH" ]; then
    case "$ARCH" in
        arm64) cp /usr/bin/qemu-aarch64-static "$TARGET"/usr/bin/ ;;
        armhf|armel) cp /usr/bin/qemu-arm-static "$TARGET"/usr/bin/ ;;
    esac
fi
mount -t proc proc "$TARGET"/proc
mount -t sysfs sys "$TARGET"/sys
mount --bind /dev "$TARGET"/dev
mount --bind /dev/pts "$TARGET"/dev/pts
# The build machine's own resolver, as a fixed public one may be unreachable
# from it (WSL behind a vpn is the case that hit)
# NOTE: rm first, as the tree may carry resolv.conf as a symlink
rm -f "$TARGET"/etc/resolv.conf
if [ -s /etc/resolv.conf ]; then
    cat /etc/resolv.conf > "$TARGET"/etc/resolv.conf
else
    echo "nameserver 1.1.1.1" > "$TARGET"/etc/resolv.conf
fi

# NOTE: --no-install-recommends, as the previous image did not carry the
#       recommends of these either; the size budget is the image's.
# NOTE: --error-on=any, as apt-get update otherwise succeeds with an index it
#       could not fetch, and a missing bookworm-security would ship an image
#       without its security updates.
# shellcheck disable=SC2086
chroot "$TARGET" /bin/sh -c "
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get update --error-on=any
    apt-get -y --no-install-recommends install $PACKAGES
    apt-get -y upgrade
    apt-get clean
"
cleanup

# The two files the manager rewrites at every start ship empty and regular, as
# rootfs/build.sh leaves them
rm -f "$TARGET"/etc/resolv.conf "$TARGET"/etc/hosts
: > "$TARGET"/etc/resolv.conf
: > "$TARGET"/etc/hosts
chmod 644 "$TARGET"/etc/resolv.conf "$TARGET"/etc/hosts
echo "debdroid" > "$TARGET"/etc/hostname
rm -rf "$TARGET"/var/lib/apt/lists/* "$TARGET"/var/cache/apt/archives/*.deb \
    "$TARGET"/var/log/*.log "$TARGET"/var/log/apt "$TARGET"/tmp/*
rm -f "$TARGET"/usr/bin/qemu-*-static

echo ":: Creating the ext2 image ($SIZE)"
rm -f "$IMAGE"
# NOTE: -d fills the filesystem from the tree, keeping every owner and mode,
#       which is the whole point of building it this way.
mke2fs -q -t ext2 -O none -O "$FEATURES" -b 4096 -L debian -d "$TARGET" "$IMAGE" "$SIZE"
e2fsck -fn "$IMAGE" > /dev/null

# Checked rather than trusted, as a stray default here makes an image that old
# kernels refuse to mount
GOT=$(dumpe2fs -h "$IMAGE" 2> /dev/null | sed -n 's/^Filesystem features: *//p' |
    tr ' ' '\n' | sort | tr '\n' ',' | sed 's/,$//')
WANT=$(echo "$FEATURES" | tr ',' '\n' | sort | tr '\n' ',' | sed 's/,$//')
if [ "$GOT" != "$WANT" ]; then
    echo "Error: The image has features \"$GOT\", expected \"$WANT\"." >&2
    exit 1
fi

echo ":: Splitting it into img/"
# NOTE: 90 MiB pieces keep each file under GitHub's 100 MB limit
rm -f "$REPO_DIR"/img/debian.img.part-*
split -b 90M -a 2 "$IMAGE" "$REPO_DIR"/img/debian.img.part-

BYTES=$(stat -c %s "$IMAGE")
SHA=$(sha256sum "$IMAGE" | cut -d ' ' -f 1)
DATE=$(date -u +%Y-%m-%d)
echo
echo ":: Built $IMAGE"
echo
echo "Replace the IMAGE line in rootfs/manifest with:"
echo
echo "default-$ARCH | IMAGE | $ARCH | $RELEASE | $SHA | $BYTES | git:img/debian.img.part-* | features=$FEATURES | built=$DATE"
