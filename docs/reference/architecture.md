[← Docs](../README.md)

# Architecture

DebDroid sits between android and debian, assembling a filesystem view,
injecting compatibility fixes, shipping its own userspace tools and
bootstrapping the environment before handing over a session.

```txt
Android
 ↓
DebDroid runtime
  • BusyBox and e2fsprogs
  • Compatibility libraries (preloaded)
  • Bootstrap script
  • Filesystem and namespace manager
 ↓
Debian
```

## Why this is not just a chroot script

A chroot script runs `chroot`. DebDroid does that too, but the work around it
is what makes a real debian userland function on android:

- **Mount namespace isolation**, so the mounts are invisible to android and
  disappear with the session.
- **A lock that cannot go stale**, held through an inherited file descriptor, so
  two sessions cannot mount the image at once and corrupt it.
- **Compatibility libraries** preloaded into every debian process, because
  android's kernel does not provide what glibc expects. See
  [patches](patches.md).
- **Bundled userspace tools**, because the android toolbox on old devices lacks
  most of what the scripts need.
- **A bootstrap** that fixes the things debian cannot know about android, such
  as the group membership android requires before any socket works.

It is a compatibility and management layer, not a wrapper around one command.

## Layout

### On the device, under `/sdcard/debdroid`

```txt
debdroid.sh            Frontend: the commands users run
debdroid_mgr.sh        Manager: namespaces, locking, mounts, teardown
debdroid_resize.sh     Image resizer
debdroid_update.sh     Runtime self-update
debdroid_uninstall.sh  Uninstaller
debdroid_ui.sh         Output helpers shared by all of them
debdroid_lib.sh        Shared functions shared by all of them
debdroid_env.sh        Paths and variables
environment            Variables exported inside the session
command/               Command scripts
tests/                 The device test suite
boot/                  The start-at-boot hook
img/debian.img         The debian filesystem
```

### On the device, under `/data/local/debdroid`

```txt
bin/                   busybox, e2fsck, resize2fs, debinit
lib/                   The preloaded compatibility libraries
mnt/                   Where the image is mounted
mnt.lock               The session lock
mnt.pid                The background supervisor's pid and namespace
mnt.log                Background startup output
swapfile               Created on request by debdroid_swap.sh
backup/                The previous version, for self-update --rollback
```

Two locations because `/sdcard` is large but cannot hold executables with the
permissions they need, while `/data/local` can.

### In the repository

```txt
debdroid/     The scripts installed to /sdcard/debdroid
bin/ lib/     The bundled binaries and libraries
command/      Command scripts
boot/         The start-at-boot hook
module/       The magisk, kernelsu and apatch module
tests/        All three test tiers
tools/        Maintenance scripts, such as the provenance verifier
package/      Build recipes for the bundled binaries
provenance/   The manifest recording every binary
licenses/     License texts for the redistributed binaries
docs/         This documentation
```

## How a session runs

Summarised in [session lifecycle](session-lifecycle.md), which also records the
design decisions that should not be undone.

## Inside the chroot

The session starts a **clean login shell** that inherits nothing from android.
That is deliberate: android's environment is full of variables that confuse
debian software. Anything needed inside goes in `environment`, which the
bootstrap sources.

`/debdroid/bin` is on the path, so the bundled tools are available inside
debian too. `/debdroid/lib` is registered in `/etc/ld.so.preload`, so the
compatibility libraries load into every process.

`umask 022` is set explicitly at the start of the chroot command, because
android root shells commonly run with `umask 077`, which would make every file
debian creates private and break packages that expect otherwise.

## Why ext2

**The image is ext2 deliberately, and should stay that way.**

Old phone kernels reject many ext4 feature flags, and a modern `mke2fs` enables
several by default (`metadata_csum`, `64bit`, `orphan_file` and others),
producing an image the target devices cannot mount at all.

This is partly enforced by construction: busybox's `mkfs.ext2` applet can only
produce ext2, so on-device image creation cannot emit a modern ext4 even by
accident. The risk lives wherever a modern tool might get involved, which is
why CI fails the build on any ext4 image creation.

If ext4 were ever offered it would need the standalone `mke2fs` with an
explicitly pinned, minimal feature set and every unsupported feature negated,
never the tool's defaults. The bundled `e2fsck` would also have to understand
whatever the image carries.

If the goal is crash safety rather than features, the minimal step is ext3:
ext2 plus `has_journal` and nothing else. That needs the standalone `tune2fs`,
as busybox's applet is too limited, and it is worth measuring first since a
journal costs writes on slow eMMC.

## Why the architecture gate is two-level

`check_arch` maps an android abi onto its debian architecture name.
`arch_is_built` then reports whether binaries for it actually ship.

Keeping those separate means a 32-bit ARM device gets told "recognised, not
built yet" rather than failing later with `cannot execute binary file`, which
is a far worse way to find out.
