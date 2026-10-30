# Changelog

All notable changes to DebDroid are recorded here. Versions follow
`MAJOR.MINOR.PATCH`, and `DEBDROID_VER` in `debdroid/debdroid_env.sh` is the
single source of truth for the installed version.

Entries for 1.2.5 and earlier are reconstructed from the commit history, as the
project did not keep a changelog before 1.2.6.

## 1.2.6

### Fixed

- The user's command no longer loses its quoting on the way to the chroot.
  It crossed three hops unquoted, so `apt install 'foo bar'` split into
  separate arguments, and `*` expanded against the host's current directory
  instead of reaching Debian. The frontend and the manager's first pass now
  pass `"$@"`, and the second pass quotes the command for the single `su -c`
  string that `chroot` runs.
- The root check calls busybox rather than the android toolbox, whose `id` on
  very old devices does not support `-u`.
- `DEBDROID_VER` was still `1.2` while the last release was 1.2.5.

### Added

- `debdroid/debdroid_lib.sh`, holding the helpers every entry point shares:
  `find_busybox`, `require_busybox`, `require_root`, `check_arch`,
  `require_arch`, `check_size` and `shell_quote`. The busybox lookup, the root
  check and the architecture gate were duplicated across three scripts and are
  now defined once.
- `check_arch` falls back to `ro.product.cpu.abilist` for devices that do not
  report `ro.product.cpu.abi`.
- A test suite under `tests/`:
  - `tests/run.sh` runs the host tier, which needs neither root nor a device.
    It copies the product scripts to a temporary directory and rewrites the
    paths baked into them, so the shipped scripts are never modified.
  - `tests/device.sh` checks a real device. By default it only reads: it never
    mounts the image, starts a session or writes to it. With `--session` it
    starts one real session, verifies it from the inside through
    `tests/device_probe.sh`, and then confirms the lock, the mounts, android's
    `/dev` and the image were all left clean.
  - `make check` runs both syntax checkers and shellcheck, `make test` runs the
    host tier, and `make test-device` prints the adb command.
- CI running the checks, the host tests, and guards that fail the build on a
  namespace flag other than `--mount` or on ext4 image creation.

### Changed

- The installer points `BUSYBOX` at the bundled copy, so the shared helpers and
  the colored output work before the binaries are installed.
- The installer also installs the device test suite to
  `/sdcard/debdroid/tests`.
- The resizer takes its size from the first argument rather than joining every
  argument, and validates it with `check_size` instead of spawning `grep`.

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
