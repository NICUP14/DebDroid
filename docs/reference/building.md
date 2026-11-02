[← Docs](../README.md)

# Building the Bundled Binaries

Everything here runs on a Linux desktop, not on a device. Recording how is what
moves binaries out of tier C in [the manifest](../../provenance/manifest).

```sh
sh package/build_all.sh              # every architecture
sh package/build_all.sh arm64        # just one
```

## The one mistake to avoid

The bundled binaries target **two different C libraries**, and confusing them
produces files that fail in confusing ways.

| Component | Runs on | Build with | Against |
| --- | --- | --- | --- |
| `busybox` | the android side | Android NDK | bionic |
| `e2fsck`, `resize2fs` | the android side | Android NDK | bionic |
| `librandom.so`, `libarc4random.so` | **inside debian** | a debian cross-toolchain | **glibc** |

The preload libraries are loaded by debian's linker through
`/etc/ld.so.preload`. NDK output cannot be loaded there at all. This is easy to
get wrong by analogy with the other two, so it is worth checking `file` output
before shipping: the libraries should **not** say "for Android".

## Architecture mapping

| Debian arch | Android abi | NDK triple | Debian cross-compiler |
| --- | --- | --- | --- |
| `arm64` | `arm64-v8a` | `aarch64-linux-android` | `aarch64-linux-gnu-gcc` |
| `armhf` | `armeabi-v7a` | `arm-linux-androideabi` | `arm-linux-gnueabihf-gcc` |
| `amd64` | `x86_64` | `x86_64-linux-android` | `x86_64-linux-gnu-gcc` |
| `i386` | `x86` | `i686-linux-android` | `i686-linux-gnu-gcc` |

API level 21 (Android 5.0) is the floor.

## busybox

Build it statically, because the android toolbox on old devices cannot be
relied on for anything.

```sh
cd busybox-<version>
make ARCH=<ndk_arch> CROSS_COMPILE=<ndk_triple>- defconfig
# then enable the options below
make ARCH=<ndk_arch> CROSS_COMPILE=<ndk_triple>- -j$(nproc)
```

### Options that matter

| Option | Why |
| --- | --- |
| `CONFIG_STATIC` | The device's libc cannot be relied on |
| `CONFIG_SSL_CLIENT` | Lets `wget` speak https **without a separate helper binary** |
| `CONFIG_MKE2FS`, `CONFIG_TUNE2FS` | On-device image creation |
| `CONFIG_NSENTER`, `CONFIG_SETSID` | Background mode |
| `CONFIG_START_STOP_DAEMON` | Service management |
| `CONFIG_MKSWAP`, `CONFIG_SWAPON`, `CONFIG_SWAPOFF` | Swap support |
| `CONFIG_FLOCK`, `CONFIG_UNSHARE`, `CONFIG_MOUNTPOINT` | The session lock and namespace |

Enabling `CONFIG_SSL_CLIENT` is what removes the need for the separately
sourced `ssl_helper` binary, which is the last thing keeping it in tier C.

### Verify the build

```sh
sh package/verify_applets.sh out/arm64/busybox
```

This checks the build against the applets the scripts actually call, so a
misconfigured build fails here rather than at runtime on someone's phone. The
whole project leans on that applet table.

To run a cross-built binary on the build machine, register qemu first:

```sh
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
```

### Note on busybox's own tools

busybox provides `mke2fs` and `tune2fs`, but **no `e2fsck` and no
`resize2fs`**. Its `fsck` applet is only a front-end wrapper, so those two
binaries cannot be dropped.

## e2fsprogs

Follow [package/bin/e2fsprogs/Compile.md](../../package/bin/e2fsprogs/Compile.md),
which pins the upstream commit and the NDK version. For another architecture,
override the target before configuring:

```sh
export TARGET=arm-linux-androideabi
export API=21
```

## The preload libraries

```sh
cd package/lib/librandom
make CC=arm-linux-gnueabihf-gcc
```

On debian, the cross-compilers come from `gcc-<triple>` packages. Check the
result does not mention android:

```sh
file out/librandom.so
# ELF ... ARM aarch64 ... dynamically linked     <- correct
# ELF ... for Android ...                        <- wrong toolchain
```

## Recording the result

Every built binary goes in the manifest with its checksum, then:

```sh
make provenance
```

CI runs the same check, and fails on a binary nobody wrote down.

## Reproducibility

The goal is that anyone can rebuild the exact shipped set. `e2fsck` and
`resize2fs` already meet that bar through the pinned commit and NDK version;
busybox is being moved to the same footing.

Where a build is reproducible, two people building from the same inputs should
get identical checksums. Where it is not, the recipe and the version are at
least recorded, which is the next best thing.
