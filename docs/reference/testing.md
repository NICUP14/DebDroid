[← Docs](../README.md)

# Testing

Three tiers, each catching a different class of problem. The constraint shaping
all of this is that device access is scarce, so as much as possible is
verifiable without one.

## Tier 1: host, no root, no device

Runs on any Linux machine, in a couple of seconds.

```sh
make check    # sh -n, bash --posix -n, dash -n, shellcheck
make test     # the unit and integration suite
make all      # both, plus the provenance check
```

### How it avoids touching the product

`tests/run.sh` copies the scripts into a temporary directory and rewrites the
paths baked into them, so the shipped scripts are never modified. It also
installs a stub `busybox` that forwards applets to the host's tools and fakes
the android-only ones (`getprop`, `id`), with `FAKE_ABI`, `FAKE_ABILIST` and
`FAKE_UID` letting a test change what they report.

Functions are loaded out of the product scripts with
`sed -n '/^name()/,/^}/p'` and `eval`, so the tests exercise the shipped code
rather than a copy of it.

### What it covers

| File | Covers |
| --- | --- |
| `test_lib.sh` | `shell_quote` round-trips, the `check_size` grammar, abi mapping, busybox lookup |
| `test_frontend.sh` | the user command surviving every hop, command-name validation |
| `test_mounts.sh` | `environment_mounts`, including a sibling path that must not be unmounted |
| `test_envfiles.sh` | the config files written into the image, including symlink escapes |
| `test_background.sh` | the supervisor pid file, including a live pid in the wrong namespace |
| `test_lifecycle.sh` | the uninstaller's path guards, the updater's verification |

Run one file with `sh tests/run.sh lib`.

## Tier 2: host, with root

Not yet implemented. It would run the real manager against a scratch ext2 image
on a Linux machine, asserting no leftover mounts, a second session refused, a
leftover daemon killed, and the host's `/dev` unchanged.

Building the x86_64 binaries would let this run against the real bundled
binaries rather than host substitutes, which is a large part of why that
architecture is worth shipping.

## Tier 3: on a device

```sh
adb shell su -c 'sh /sdcard/debdroid/tests/device.sh'
```

`tests/device.sh` is the only tier that can confirm the things a build machine
cannot: whether the kernel supports ext2 and overlayfs, whether loop devices
exist, whether wakelocks and swap work, and whether `/apex` is present.

**It only reads by default.** It never mounts the image, starts a session or
writes to it, so it is safe on a device holding data.

```sh
# Also start one real session and verify it end to end
adb shell su -c 'sh /sdcard/debdroid/tests/device.sh --session'
```

With `--session` it starts a session, runs `tests/device_probe.sh` inside it,
then starts a background environment, attaches, leaves a daemon running across
sessions, stops it, and checks that the lock, the mounts, android's `/dev` and
the image were all left clean.

### What the probe checks from inside

`tests/device_probe.sh` runs in the debian userland and reports back over
marker lines. It checks the umask, which `/dev` strategy was taken, the mounts,
the preload libraries and their readability, DNS, the `inet` group, and that
the arguments it was given survived three hops and a `su -c` string intact.

The probe never writes to `/dev`: on a bind fallback that would land in
android's real `/dev`, which is the exact bug the suite is checking for.

## CI

Every push runs tier 1, the provenance check, and two guards:

- **No namespace but `--mount`.** Android devices support no other, and the
  mount-only design reads like a limitation to anyone changing the code cold.
- **No ext4 image creation.** A modern `mke2fs` enables features old phone
  kernels refuse to mount.

Tagged builds additionally assert `DEBDROID_VER` matches the tag.

## Adding a test

Add `tests/test_<name>.sh`, source `assert.sh`, and use `t_ok`, `t_no`,
`t_eq`, `t_true`, `t_false`, `t_mode` and `t_summary`. The runner picks it up
automatically and treats a non-zero exit as failure.

Prefer testing the refusals over the happy path. The bugs that have mattered in
this project were all cases where something should have declined to act and
did not.
