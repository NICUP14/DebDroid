# Changelog

All notable changes to DebDroid are recorded here. Versions follow
`MAJOR.MINOR.PATCH`, and `DEBDROID_VER` in `debdroid/debdroid_env.sh` is the
single source of truth for the installed version.

Entries for 1.2.5 and earlier are reconstructed from the commit history, as the
project did not keep a changelog before 1.2.6.

## 1.2.6

### Added

- **Background mode.** `debdroid.sh start` leaves Debian running after the
  terminal closes, `attach` opens a session in it, `stop` shuts it down and
  `status` reports what is running. Attaching skips the filesystem check, the
  mounts and the bootstrap, so it is near-instant compared to `run`.
  This is what the project's headline claim needed: before this, exiting a
  session killed `sshd` and any VNC server with it.
- **Wakelock support** (`CONFIG_ENV_WAKELOCK`), without which the device deep
  sleeps and an always-on server stops answering.
- **Managed swap** (`CONFIG_ENV_SWAP` plus `debdroid.sh swap`), replacing the
  third-party Magisk module the troubleshooting guide used to point at.
- **A start-at-boot hook** (`boot/debdroid.service.sh`) for Magisk, KernelSU
  and APatch, guarded by a sentinel so a failed attempt stands down rather than
  repeating itself on a device that may not survive it.
- **Service helpers**: `command/user.sh`, `command/sshserver.sh` and
  `command/vncserver.sh`. The VNC helper disables the XFCE4 power manager,
  which is what caused the out-of-memory reboots.
- **`debdroid.sh self-update`** and **`debdroid.sh uninstall`**, both of which
  were missing entirely.
- **A Magisk, KernelSU and APatch module** under `module/`, installing DebDroid
  in one tap and setting up start-at-boot.
- **A test suite** with three tiers: a host suite needing no device, unit tests
  loaded out of the product scripts, and `tests/device.sh`, which checks a real
  device and only reads by default.
- **Provenance records** for every bundled binary, in `provenance/manifest` and
  `THIRD_PARTY.md`, verified by CI.
- **Architecture plumbing** for `armhf`, `amd64` and `i386`, with build recipes
  in `package/`. Only `arm64` binaries ship so far, and the scripts now say so
  clearly instead of failing later.
- **CI**, running the checks, the host tests, the provenance verification, the
  documentation checks, and guards on the two design constraints.

### Fixed

- **The `/dev` overlay never mounted.** `upperdir` was on a tmpfs while
  `workdir` was on the ext2 image, and the kernel requires both on the same
  filesystem, so it failed every time and silently used the bind fallback — an
  advertised feature that had never once run. A private tmpfs was also added
  between the overlay and the bind mount, so sharing android's real `/dev` is
  now a last resort rather than the normal path.
- **`/dev` entries were created in android's real `/dev`.** `/dev/fd`,
  `/dev/std*` and `/dev/loopN` were created before `/dev` was isolated, leaving
  them behind in the running system.
- **Writes into the image could escape through a symlink.** `etc/resolv.conf`,
  `etc/hosts` and `etc/ld.so.preload` were truncated in place, so an image
  shipping `resolv.conf` as an absolute symlink — which systemd-resolved does —
  had the write land on android's real `/etc`.
- **The user's command lost its quoting.** It crossed three hops unquoted, so
  `apt install 'foo bar'` split into two arguments and `*` expanded against the
  host's current directory.
- **The installer discarded user configuration**, overwriting `environment` and
  `debdroid_env.sh` on every update. They are kept now, with the new versions
  saved alongside as `.new`.
- The root check calls busybox, as the android toolbox `id` on very old devices
  does not support `-u`.
- `DEBDROID_VER` was still `1.2` while the last release was 1.2.5.
- Assembling the image from its parts is checked, as a partial image only fails
  later during the filesystem check.

### Changed

- **The documentation is split into three tiers**: the README as the pitch,
  `docs/guide/` written for people who just want it working, and
  `docs/reference/` where the technical detail belongs. `Security.md` and
  `Patches.md` were nine-line stubs and now have real content. Troubleshooting
  is organised by the message you are looking at. The old paths keep redirect
  stubs.
- **The bundled APKs were removed** in favour of F-Droid links. One of them,
  the keyboard, could not be vouched for: it carried build metadata postdating
  its last upstream release and a generic signing certificate, and a keyboard
  sees every keystroke on the device. See `THIRD_PARTY.md`.
- `/apex` is bind-mounted on android 10 and later, which is what lets android's
  own tools run inside the chroot.
- Added `debdroid_lib.sh`, holding the helpers every entry point shares. The
  busybox lookup, the root check and the architecture gate had been duplicated
  across three scripts.
- Added `licenses/` and `NOTICE`, as the redistributed GPL binaries carry an
  obligation to offer their source.

## 1.2.5

### Fixed

- Image corruption caused by a session exiting while `dbus-daemon` kept the
  image mounted, by an `e2fsck` exit code of 1 being treated as a failure, and
  by nothing preventing a second session from mounting the image again.
- Preload libraries were unreadable for non-root users inside the chroot, and
  directories created under a 077 umask were not traversable.

### Changed

- Failures are categorized as warnings or errors, and the chroot session starts
  with `umask 022`.

## 1.2

- Reworked robustness: the private mount namespace check reads
  `/proc/self/ns/mnt`, sessions take a lock, `e2fsck` runs before mounting, and
  the resizer takes an absolute size rather than a relative one.
- Added the feature-based configuration mechanism and the image resizer.
- Split the README into `docs/`.

## 1.1

- Hardened the randomness libraries, reworked the output, and added the resize,
  patch and command functionality.

## 1.0

- First release: a Debian userland on rooted arm64 Android devices.

> [!IMPORTANT]
> 1.0 and 1.1 contain known security bugs and missing functionality. Update to
> the latest version; the update keeps the existing debian image.
