# Changelog

All notable changes to DebDroid are recorded here. Versions follow
`MAJOR.MINOR.PATCH`, and `DEBDROID_VER` in `debdroid/debdroid_lib.sh` is the
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
  filesystem, so it failed every time and silently used the bind fallback: an
  advertised feature that had never once run. A private tmpfs was also added
  between the overlay and the bind mount, so sharing android's real `/dev` is
  now a last resort rather than the normal path.
- **`/dev` entries were created in android's real `/dev`.** `/dev/fd`,
  `/dev/std*` and `/dev/loopN` were created before `/dev` was isolated, leaving
  them behind in the running system.
- **Writes into the image could escape through a symlink.** `etc/resolv.conf`,
  `etc/hosts` and `etc/ld.so.preload` were truncated in place, so an image
  shipping `resolv.conf` as an absolute symlink (which systemd-resolved does)
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

- **The installer rejected every device.** `getprop` is part of the android
  toolbox and is not a busybox applet, but it was called as `$BUSYBOX getprop`.
  That writes a usage error to stderr and nothing to stdout, which looks exactly
  like an unset property, so the detected architecture was always empty and the
  installer refused to continue. The host suite could not catch it: its busybox
  stub emulated `getprop` as an applet, so the one thing that was wrong was the
  thing the test faked. The stub no longer provides it, and a check now refuses
  the call shape outright.
- **DNS inside the session was never the device's.** The same mistake in the
  `resolv.conf` loop meant no nameserver was ever read from android, so every
  session silently fell back to the hardcoded default instead of the network's
  own resolver.
- **`debdroid.sh run` always reported success.** It discarded what the session
  returned, so `debdroid.sh run false` exited 0 and a refusal to start was
  indistinguishable from a clean run. Anything scripted on top of it, the boot
  hook included, could not tell that it had failed. `attach` was already
  correct, and `run` now matches it.
- **A foreground run could orphan the background environment.** Starting a
  session while `debdroid.sh start` was running deleted the supervisor's pid
  file before testing whether one was alive, then stopped at the image lock.
  The supervisor stayed running with no pid file, so `stop` could not find it,
  `status` reported it as stopped, and only a reboot cleared it. Both modes now
  check for a live supervisor before touching the file, and say to attach
  instead.
- **Swap never worked.** Two independent causes. `swapon -p` needs a busybox
  built with `CONFIG_FEATURE_SWAPON_PRI` and the bundled one is not, so every
  attempt failed on the flag; there is a fallback now, and the kernel's own
  descending priorities keep the file below zram as intended. Separately, the
  free-space check read the wrong column: busybox `df` wraps a device name
  longer than its column onto its own line, which is what `/data` looks like on
  a real phone, so a device with 17G free was reported as having 0M and every
  swapfile was refused.
- **`command` dropped its arguments**, so the documented
  `DEBDROID_USER=name debdroid.sh command user` could not work at all: the
  session starts through `su -`, which resets the environment, and no argument
  was forwarded in its place. Arguments are passed through now
  (`debdroid.sh command user alice`), and the reference says which variables
  belong in `/sdcard/debdroid/environment` rather than the calling shell.
- **`command user` claimed success after failing to set a password.** Without a
  terminal `passwd` aborts and the account stays locked, which is the safe
  outcome, but the script still printed that the user was ready to use. It now
  says the account is locked and how to set a password.
- `/etc/hosts` read `127.0.0.1 localhost localhost` on any device reporting its
  hostname as `localhost`, which is most of them.
- **The module could not be flashed by any root manager.** Magisk, KernelSU and
  APatch decide a zip is an old-style module purely on whether it contains a
  top-level `install.sh`, and DebDroid's own installer sat there under exactly
  that name. The installer was then *sourced* as the module's install script,
  with only `module.prop` and itself extracted beside it, so it failed its own
  file check and called `exit 1`, which killed the whole flash because it had
  been sourced rather than run. `customize.sh` never ran at all. The installer
  now ships as `debdroid-install.sh`, and the build refuses to produce a zip
  with a top-level `install.sh` so it cannot regress.
- **A fresh module install could not find the bundled busybox.** Root managers
  apply their own default permissions, which set every file to 0644, before
  `customize.sh` runs. The installer tested `[ -x ./bin/busybox ]`, found it not
  executable and fell back to the installed path, which does not exist on a
  fresh device, so the install stopped with "Cannot locate the busybox binary".
  It appeared to work only where an earlier install had left an executable copy
  behind. The installer now restores the execute bit before testing it, which
  also covers a zip extracted by a file manager that drops permissions.
- **A failed flash explained nothing.** `error` and `warn` write to stderr, and
  a root manager's console shows only stdout, so a failure printed one abort
  line with no reason and none of the installer's guidance. The commonest real
  case is a device with no usable network and no bundled image, where the
  installer says exactly where to put the image by hand. The module now merges
  the installer's stderr into the console.
- The module set `service.sh` to 0755 and then ran a recursive permission pass
  that reset it to 0644, so the explicit mode did nothing. Harmless, as Magisk
  runs service scripts through `sh`, but the ordering is fixed.
- **Your configuration flags were discarded by every update.**
  `docs/reference/configuration.md` said to edit the `CONFIG_*` block at the top
  of `debdroid_mgr.sh`, and the installer replaces that file, so following the
  documentation lost the settings on the next update. The page also contradicted
  itself, announcing "two files" above a table of three. The flags now live in
  `/sdcard/debdroid/debdroid.conf`, which **is not shipped at all**: the
  installer has no code path that writes it, so there is nothing to overwrite
  and nothing for a later change to forget to preserve. The defaults stay in the
  manager as code, the conf overrides them, and an update carries the flags out
  of an older manager into the new file once so nobody loses what they had set.
- **The reported version never changed after an update.** `DEBDROID_VER` lived
  in `debdroid_env.sh`, which the installer preserves because the paths in it
  are the user's to change, so the version froze at whatever was first
  installed. The help banner, the uninstall header and
  `self-update --check` all reported it, and the backup directory was named
  after it, so a rollback was filed under the wrong version. It now lives in
  `debdroid_lib.sh`, which is always replaced, and `make version` fails if a
  copy reappears in the preserved file.
- **The README's first example did not work.** `debdroid.sh start` followed by
  `command sshserver` was refused: `command` always took the foreground path,
  so the manager stopped at the image already being in use. Even without a
  background environment it was wrong, because a foreground session shuts the
  environment down when the command exits, killing the server it had just
  started. `command` now runs inside the background environment when there is
  one, exactly as `attach` does, and keeps a session of its own when there is
  not.
- **`self-update --rollback` could restore the wrong version.** It chose its
  backup with `ls | tail -n 1`, which sorts lexically, so 1.2.9 was picked over
  1.2.10. The version to restore is now recorded when the backup is taken, so
  nothing has to be inferred from a directory listing. A rollback also only
  copied over the top, leaving behind any script the newer version had added;
  it now clears the project's own scripts first, and says so and skips that
  step when the backup is too incomplete to replace them. Your `environment`,
  `debdroid.conf`, the debian image and the command directory are never
  touched.
- **A failed bootstrap was reported as a success.** `debinit` pipes its output
  through `tee`, and a pipeline reports its last command, so it almost always
  exited 0. The manager runs it as `sh debinit && <your command>`, so that
  `&&` was holding nothing back: a broken bootstrap carried straight on. The
  status now survives the pipe.
- The swap warning pointed at `debdroid.sh command swap`, which has never
  existed. The command is `debdroid.sh swap create`.
- `debdroid.sh uninstall` left `debdroid_swap.sh` and `debdroid_image.sh`
  behind, as both were missing from the list of scripts it removes.
- Removed an unused downloader from the updater. Nothing called it, and it
  carried a plain-http fallback, so it read as a feature that existed and was
  a trap for whoever wires up online updates later.
- The device test suite reported a held lock as a failure, telling anyone who
  ran it without stopping their environment that a second session could corrupt
  the image. A running session holding that lock is the lock working, so it
  skips with the reason now, as the session checks beside it already did.
- **The slim module could not download the image, so a first install from it
  failed.** That is the zip the module manager points at and the one the
  install guide recommends, and it had never worked on any device. The two HTTP
  clients on a device fail on opposite halves of the releases redirect and each
  succeeds where the other does not: busybox's `wget` cannot complete a
  handshake with the host that issues the redirect, which is why this was never
  about how old the device was, and the host the redirect points at serves a
  certificate from a root younger than the device, which the system `curl`
  cannot validate. The redirect is now resolved with `curl` and the asset
  pulled with busybox `wget`. No certificate bundle was added and no
  verification was turned off: busybox `wget` never validated certificates, so
  it is the same client the download already used, and the image is still
  accepted only if it matches the checksum recorded in `rootfs/manifest`.

### Changed

- **The shipped debian image is current again**, bookworm 12.15 rather than a
  build from 2025-09-02, so a new install no longer starts with a year of
  updates to download. It is also smaller than before, 370 MiB of content
  against 446 MiB, because the refresh purges and cleans more than a point
  release adds, and the download is about 123 MB rather than 146 MB.
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
