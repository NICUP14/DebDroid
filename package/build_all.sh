#!/bin/sh
# build_all.sh: Builds every bundled binary for every supported architecture
#
# Run this on a Linux desktop, not on a device. It produces the per-architecture
# bundles that releases ship, and recording how is what moves busybox and the
# preload libraries out of tier C in provenance/manifest.
#
# Usage: sh package/build_all.sh [arch...]       (default: every architecture)
#
# Requirements:
#   - Android NDK r27d, for the android-side static binaries
#   - Debian cross-toolchains, for the preload libraries
#   - autoconf, make, git, unzip, wget
#
# IMPORTANT: The two halves target different C libraries, and confusing them is
# the easiest mistake to make here.
#
#   Android side (busybox, e2fsck, resize2fs): built with the NDK against
#   bionic, as they run on the android side of DebDroid.
#
#   Preload libraries (librandom.so, libarc4random.so): built with a DEBIAN
#   cross-toolchain against glibc, because they are loaded inside the debian
#   chroot through /etc/ld.so.preload. Building these with the NDK produces
#   objects the debian linker cannot load at all.

set -e

PACKAGE_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=${PACKAGE_DIR%/*}
BUILD_DIR=${BUILD_DIR:-$REPO_DIR/out}

NDK_VERSION=${NDK_VERSION:-android-ndk-r27d}
NDK_API=${NDK_API:-21}
BUSYBOX_VERSION=${BUSYBOX_VERSION:-1.36.1}
E2FSPROGS_COMMIT=8045c66384370e8539576c9cdc073674737c3ffc

if [ $# -gt 0 ]; then
    ARCHES=$*
else
    ARCHES='arm64 armhf amd64 i386'
fi

# Maps a debian architecture onto the toolchain triples both halves need
# Sets "ndk_triple", "ndk_arch" and "glibc_cc"
arch_toolchains()
{
    case "$1" in
        arm64)
            ndk_triple=aarch64-linux-android
            ndk_arch=arm64
            glibc_cc=aarch64-linux-gnu-gcc
            ;;
        armhf)
            # NOTE: The android triple keeps the "eabi" suffix while the clang
            #       target prefix is armv7a-linux-androideabi, which is a
            #       long-standing NDK inconsistency.
            ndk_triple=arm-linux-androideabi
            ndk_arch=arm
            glibc_cc=arm-linux-gnueabihf-gcc
            ;;
        amd64)
            ndk_triple=x86_64-linux-android
            ndk_arch=x86_64
            glibc_cc=x86_64-linux-gnu-gcc
            ;;
        i386)
            ndk_triple=i686-linux-android
            ndk_arch=x86
            glibc_cc=i686-linux-gnu-gcc
            ;;
        *)
            echo "Error: Unknown architecture: $1" >&2
            return 1
            ;;
    esac
}

# Builds busybox for one architecture, with its own TLS client enabled
# NOTE: Enabling ssl_client is what makes the separately sourced ssl_helper
#       binary unnecessary, which empties the rest of tier C.
build_busybox()
{
    echo ":: busybox for $1"
    target="$BUILD_DIR/$1"
    mkdir -p "$target"

    if [ ! -d "$BUILD_DIR"/busybox-"$BUSYBOX_VERSION" ]; then
        echo " -> Fetching busybox $BUSYBOX_VERSION"
        wget -q -O "$BUILD_DIR"/busybox.tar.bz2 \
            "https://busybox.net/downloads/busybox-$BUSYBOX_VERSION.tar.bz2"
        tar -xjf "$BUILD_DIR"/busybox.tar.bz2 -C "$BUILD_DIR"
    fi

    cat <<EOF
 -> Configure and build it with:

      cd $BUILD_DIR/busybox-$BUSYBOX_VERSION
      make ARCH=$ndk_arch CROSS_COMPILE=$ndk_triple- defconfig
      # Then enable, in this order of importance:
      #   CONFIG_STATIC=y          the device toolbox cannot be relied on
      #   CONFIG_SSL_CLIENT=y      replaces the bundled ssl_helper
      #   CONFIG_FEATURE_TLS_SHA1=y
      #   CONFIG_MKE2FS=y CONFIG_TUNE2FS=y
      #   CONFIG_NSENTER=y CONFIG_SETSID=y CONFIG_START_STOP_DAEMON=y
      #   CONFIG_MKSWAP=y CONFIG_SWAPON=y CONFIG_SWAPOFF=y
      #   CONFIG_FLOCK=y CONFIG_UNSHARE=y CONFIG_MOUNTPOINT=y
      make ARCH=$ndk_arch CROSS_COMPILE=$ndk_triple- -j\$(nproc)
      cp busybox $target/busybox

 -> Then verify the applet table, which the scripts depend on:

      sh $PACKAGE_DIR/verify_applets.sh $target/busybox
EOF
}

# Builds e2fsck and resize2fs for one architecture
build_e2fsprogs()
{
    echo ":: e2fsprogs for $1"
    cat <<EOF
 -> Follow package/bin/e2fsprogs/Compile.md, which pins upstream to
    commit $E2FSPROGS_COMMIT and NDK $NDK_VERSION,
    overriding the target triple with:

      export TARGET=$ndk_triple
      export API=$NDK_API

 -> Copy the results to $BUILD_DIR/$1/
EOF
}

# Builds the preload libraries for one architecture
build_preload()
{
    echo ":: preload libraries for $1"
    target="$BUILD_DIR/$1"
    mkdir -p "$target"

    if ! command -v "$glibc_cc" > /dev/null 2>&1; then
        echo " -> Skipping: $glibc_cc is not installed"
        echo "    On debian: apt install gcc-\${triple} for this architecture"
        return 0
    fi

    for library in librandom libarc4random; do
        echo " -> Building $library.so with $glibc_cc"
        ( cd "$PACKAGE_DIR"/lib/"$library" &&
          make CC="$glibc_cc" BUILD_DIR="$target/$library-build" )
        cp "$target/$library-build/$library".so "$target"/
        rm -rf "$target/$library-build"
    done
}

mkdir -p "$BUILD_DIR"

# shellcheck disable=SC2086 # intentional word splitting over the arch list
for arch in $ARCHES; do
    echo
    echo "================ $arch ================"
    arch_toolchains "$arch"
    build_preload "$arch"
    build_busybox "$arch"
    build_e2fsprogs "$arch"
done

echo
echo "Artifacts land in $BUILD_DIR/<arch>/"
echo "Record each one in provenance/manifest, then run: make provenance"
