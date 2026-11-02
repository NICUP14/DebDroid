# Notes for Agents

Dense orientation for an agent or new contributor changing this repository.
Read the two constraints first. Violating either produces code that looks
correct, passes review, and breaks on real hardware.

For user-facing documentation see [docs/guide/](docs/guide/); for design detail
see [docs/reference/](docs/reference/). This file is about *working on* the
project.

---

## 1. Two constraints that are not negotiable

### Only the mount namespace

Android devices support no other namespace. This was established by testing
across a range of them, not assumed.

**Do not** add `--pid`, `--net`, `--user`, `--uts`, `--ipc` or `--all` to any
`unshare` or `nsenter` call. CI fails the build if you do.

Consequences that look like bugs and are not:

- `signal_environment` in `debdroid/debdroid_mgr.sh` scans `/proc/*/ns/mnt`
  to find session processes. That is a PID-namespace workaround and the only
  option available.
- The background supervisor is **not PID 1** of anything. It cannot reap
  orphans. Do not reach for `unshare --pid --fork` to "fix" this.
- Sessions share Android's process list and network stack. By design.

### The image stays ext2

Old phone kernels reject the features a modern `mke2fs` enables by default
(`metadata_csum`, `64bit`, `orphan_file`, `has_journal`). An image carrying any
of them will not mount on the devices this project exists for.

- busybox's `mkfs.ext2` is ext2-only, so on-device creation is safe by
  construction. Keep it that way.
- CI fails on ext4 image creation.
- `make provenance` checks the shipped image's feature set.
- If crash safety ever comes up, the minimal step is ext3 (`has_journal` only)
  via the standalone `tune2fs`, and it needs measuring first.

---

## 2. What this is

~2000 lines of POSIX `sh` that run a real Debian glibc userland on rooted
Android 5+ devices. A chroot inside a private mount namespace, with an
inherited-fd lock, preloaded compatibility libraries, and bundled userspace
tools because the Android toolbox on old devices lacks nearly everything.

| Directory | Holds |
| --- | --- |
| `debdroid/` | The scripts installed to `/sdcard/debdroid` |
| `bin/` `lib/` | Bundled binaries and the preloaded libraries |
| `command/` | Command scripts run inside (or for) the environment |
| `boot/` | Start-at-boot hook |
| `module/` | Magisk / KernelSU / APatch module |
| `tests/` | All three test tiers |
| `tools/` | Maintenance and verification scripts |
| `package/` | Build recipes for the bundled binaries |
| `rootfs/` | **Maintainer only.** Image/tarball build and manifest |
| `provenance/` | Manifest recording every binary |

On device, two locations: `/sdcard/debdroid` (scripts, image) and
`/data/local/debdroid` (binaries, mount point, lock, pid, log, swapfile).

---

## 3. Task → files

| Changing | Touch |
| --- | --- |
| A user-facing command | `debdroid/debdroid.sh` (dispatch + help), `docs/reference/cli.md` |
| Session startup or teardown | `debdroid/debdroid_mgr.sh` → `start_environment` / `stop_environment` |
| Mounts | `debdroid_mgr.sh` → `mount_dev`, `populate_dev`, `start_environment`; `docs/reference/mounts.md` |
| Background mode | `debdroid_mgr.sh` → `supervise_environment`, `attach_environment`, `stop_background`, `report_status`, `read_supervisor` |
| A shared helper | `debdroid/debdroid_lib.sh`, plus `tests/test_lib.sh` |
| Output wording or colour | `debdroid/debdroid_ui.sh`, `docs/reference/output.md` |
| A config flag | `debdroid_mgr.sh` header block **and** `docs/reference/configuration.md` |
| A path or env variable | `debdroid/debdroid_env.sh`, `docs/reference/configuration.md` |
| In-chroot bootstrap | `bin/debinit`, `docs/reference/patches.md` |
| Something inside Debian | `command/*.sh` (these run in the chroot) |
| Something on the Android side | `debdroid.sh swap` is the example; note the difference |
| Install or update flow | `install.sh`, `debdroid/debdroid_update.sh`, `debdroid/debdroid_image.sh` |
| Uninstall | `debdroid/debdroid_uninstall.sh` |
| A bundled binary | `provenance/manifest`, `THIRD_PARTY.md`, `package/build_all.sh`, `docs/reference/building.md` |
| The shipped image | `tools/refresh-image.sh`, `rootfs/manifest` |
| Release artifacts | `module/build.sh`, `.github/workflows/release.yml`, `docs/reference/releasing.md` |
| Architecture support | `debdroid_lib.sh` → `check_arch` / `arch_is_built`; `package/build_all.sh` |
| Anything versioned | See §7, the version lives in five files |

### Adding a command

1. `debdroid/debdroid.sh`: add an `elif` branch and a help entry.
2. If it needs the manager, set `DEBDROIDMGR_MODE` and export it; the manager
   reads its mode from the environment so its positional arguments stay
   backward compatible.
3. Document in `docs/reference/cli.md`.
4. If users will run it, add a guide page under `docs/guide/` and link it from
   `docs/README.md`, which is the one index page.
5. Add tests. Prefer covering the refusals.

---

## 4. Conventions

### Shell

- **POSIX `sh` only.** No bashisms. Target is whatever `/system/bin/sh` is,
  which on old devices is mksh or worse. Never install another shell to test;
  the maintainer has rejected that.
- **Every external tool goes through `$BUSYBOX`.** `$BUSYBOX awk`,
  `$BUSYBOX grep`, `$BUSYBOX mountpoint`. The Android toolbox lacks most
  utilities and the ones it has differ. Use shell built-ins and parameter
  expansion where possible (`${f##*/}`, not `basename`).
- Exception: scripts in `command/` that run **inside** Debian may use normal
  tools, since Debian has them. `bin/debinit` does.
- Style: a `# Comment` above each block, `name()` with `{` on the next line,
  4-space indent, `# NOTE:` for anything non-obvious.
- Quote expansions. The one accepted bare pattern is `$BUSYBOX` as a command
  word, though quoting it is preferred in new code.

### Messages

- `section "..."` → `:: header`; `step "..."` → ` -> line`.
- `warn msg [hint...]` → purple, environment survives.
- `error msg [hint...]` → red, caller then exits.
- Values in quotes, sentences end with a period, the image is always "the
  debian image". Give the user a next action where one exists.

### Commits

One line, past tense, changes separated by `;`, ending with a period. Add a
`- ` bullet body for anything substantial, explaining the *why*, especially the
user-visible effect. Commit per feature. Only commit when asked.

---

## 5. Gotchas that will bite you

**`debdroid_env.sh` overwrites `BUSYBOX` unconditionally.** It sets the install
path. `install.sh` re-points it at `./bin/busybox` afterwards because the
installed one does not exist yet on a fresh install.

**`debdroid_mgr.sh` sources no environment file.** Invoked directly, `BUSYBOX`
must already be set or findable. Tests must pass it explicitly.

**The supervisor's blocking loop is deliberate:**

```sh
while :; do $BUSYBOX sleep 60 & wait; done
```

A POSIX shell runs no trap while a foreground command executes, so a plain
`sleep 60` would delay `stop` by up to a minute. Do not "simplify" this.

**The lock fd uses a per-command redirection** (`cmd 9> file`), never a bare
`exec 9>`. POSIX leaves it unspecified whether fds above 2 opened by a bare
`exec` reach other programs.

**The pid file stores a namespace id alongside the pid.** Pids get reused, so a
stale file could name a live unrelated process. `read_supervisor` revalidates
the pair before anything signals it.

**Writes into the image must unlink first.** Use `write_env_file`. A Debian
image can ship `/etc/resolv.conf` as an absolute symlink, and truncating
through it writes to Android's real `/etc`.

**`/dev` entries are created after the mount, never before.** Creating them
first put them in Android's real `/dev`. `populate_dev` works from
`$dev_root`, never a bare `/dev/...`.

**Overlay `upperdir` and `workdir` must be on one filesystem**, and `upper`
must be `0755`: the overlay takes `/dev`'s mode from it, and `0700` hides
`/dev` from non-root users. Getting this wrong is why the overlay silently
never mounted for a year.

**The preload libraries target glibc, not Android.** They load inside the
chroot via `ld.so.preload`. Build them with a Debian cross-toolchain
(`aarch64-linux-gnu-gcc`), **not** the NDK. NDK output cannot be preloaded
there at all. This is the easiest mistake to make by analogy with e2fsprogs.

**busybox has no `e2fsck` and no `resize2fs`.** Its `fsck` applet is only a
front-end wrapper. Those two binaries cannot be dropped.

**`for x in a b 2>/dev/null` is a syntax error.** Redirection is not allowed in
a `for` list. An unmatched glob stays literal; filter with `[ -f "$x" ]`.

**Do not name a variable or manifest key `done`.** `$(probe done)` parses as a
shell keyword inside a command substitution.

**The module zip does not bundle the image unless asked.** `img/` is always
present in a checkout, since the split parts are tracked in git, so keying the
decision off its presence silently produces a 150 MB zip of which 4 MB is the
project. `module/build.sh` takes `--with-image`, and a release builds two:
`DebDroid-module.zip`, which downloads the image through
`debdroid/debdroid_image.sh` and is what `module/update.json` points at, and
`DebDroid-module-offline.zip`, which bundles it for a device with no usable
network. Neither needs an installer change: `install.sh` already branches on
whether an image is present.

---

## 6. Testing

```sh
make check        # sh -n, bash --posix -n, dash -n, shellcheck
make test         # host suite, no root, no device, ~2s
make provenance   # binaries and the image against their manifests
make docs         # links, redirect stubs, guide vocabulary
make version      # every file naming a version agrees
make all          # all of the above
```

### How the host harness works

`tests/run.sh` copies the product scripts to a temp dir and rewrites the paths
baked into them, so the shipped scripts are never modified. It installs a
busybox stub that forwards applets to host tools and fakes `getprop` and `id`.

Functions are pulled out of the product scripts and `eval`'d:

```sh
eval "$(sed -n '/^read_supervisor()/,/^}/p' "$TEST_SD"/debdroid_mgr.sh)"
```

This requires the `name()` / `{`-on-next-line style. Keep it.

Three things that will trip you:

- **`FAKE_ABI`, `FAKE_ABILIST`, `FAKE_UID` must be exported.** The stub is a
  separate process.
- **Functions that call `step`/`error` need `debdroid_ui.sh` sourced.**
- **shellcheck cannot see through `eval`**, so test files carry a per-file
  `# shellcheck disable=` with a comment naming what consumes the variable.

### Device tier

```sh
adb shell su -c 'sh /sdcard/debdroid/tests/device.sh [--session]'
```

Read-only by default: it never mounts the image, starts a session or writes.
`--session` runs the full lifecycle and verifies cleanup. `device_probe.sh`
runs inside and reports over `DEVTEST:key=value` lines.

The probe must never write to `/dev`. On a bind fallback that lands in
Android's real `/dev`, the exact bug the suite checks for.

### What CI enforces

Namespace flags, ext4 creation, provenance, license texts, no committed APKs,
doc links, guide vocabulary, version agreement. Expect it to catch you.

**shellcheck is not installed on the maintainer's machine**, so `make check`
skips it locally and CI is the real gate. Account for that: prefer fixing a
finding over suppressing it, and when suppressing, say why in a comment.

---

## 7. Versions live in five files

`DEBDROID_VER` in `debdroid/debdroid_env.sh` is the single source of truth.
`make version` checks the rest and CI runs it.

| File | Holds |
| --- | --- |
| `debdroid/debdroid_env.sh` | `DEBDROID_VER`, the truth |
| `module/module.prop` | `version=v<X>` and `versionCode` (derived: 1.2.6 → 10206) |
| `module/update.json` | the version the module manager polls |
| `TODO.md` | `**Current version: vX**` plus a section per version |
| `CHANGELOG.md` | a `## X` section |

See `docs/reference/releasing.md`.

---

## 8. Do not

- Change `mount -r /system` to a bind mount. It has been observed to break the
  running system.
- Add a namespace, or create an ext4 image. See §1.
- Put `rm -rf` near an unvalidated path. Both `debdroid_uninstall.sh`
  (`safe_remove`) and `module/customize.sh` (`${MODPATH:?}`) guard this because
  everything here runs as root.
- Commit an APK, or add a binary without a `provenance/manifest` entry.
- Make installation harder. It is one tap or three terminal lines, and the
  audience is mostly novices. Users never run `debootstrap`, never read a
  manifest, never build a filesystem.
- Document a workaround in a guide when you could remove the need for it. The
  `inet` group is the example: `command/user.sh` handles it, so the guides no
  longer explain it.
- Introduce *chroot*, *namespace*, *overlayfs*, *loop device* or *lock file*
  into `docs/guide/`. CI greps for these.

---

## 9. Current state

v1.2.6 on the `testing` branch. arm64 binaries only; the other architectures
have plumbing and build recipes but no artifacts. No release is published yet,
so `rootfs/manifest` tarball entries are commented placeholders that fail
closed.

A tag runs `.github/workflows/release.yml`, which publishes four assets: the
two module zips, `debian.img.gz`, and `SHA256SUMS`. **The tagged commit has to
be reachable from `main`** or the workflow refuses and publishes nothing, so a
tag on this branch does not release. Nothing in CI exercises that workflow, as
it only fires on a `v*` tag, so changes to it are verified by running their
shell by hand. What still blocks a tag is the `Before tagging` list in
[TODO.md](TODO.md), and `docs/reference/releasing.md` says which changes earn a
release at all.

**Nothing in this repository has been verified on real hardware.** Every
device-dependent claim should be treated as unverified until `tests/device.sh`
has run on a phone. Say so when reporting results.

Open work is in [TODO.md](TODO.md), ordered by target version.
