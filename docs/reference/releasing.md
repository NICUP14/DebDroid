[← Reference](README.md)

# Releasing

## The version is in one place

`DEBDROID_VER` in `debdroid/debdroid_env.sh` is the single source of truth.

Several other files repeat it, and a stale copy is worse than no copy —
`module.prop` drives the module's update check, and `TODO.md` is where the
roadmap is tracked. `make version` checks they all agree, and CI runs it on
every push:

| File | What it holds |
| --- | --- |
| `debdroid/debdroid_env.sh` | `DEBDROID_VER`, the source of truth |
| `module/module.prop` | `version=v<X>` and `versionCode` |
| `module/update.json` | the version the module manager polls for |
| `TODO.md` | the current version, and a section per version |
| `CHANGELOG.md` | a section per version |

`versionCode` is derived rather than chosen: `1.2.6` becomes `10206`. It has to
increase for a module manager to offer an update, and deriving it means it
cannot be forgotten.

## Steps

1. **Bump the version** in `debdroid/debdroid_env.sh`.
2. **Update `module/module.prop`** and `module/update.json` to match.
3. **Add a `CHANGELOG.md` section**, grouped into Added, Fixed and Changed.
   Say what the user-visible effect was, not just what changed — "the overlay
   never mounted, so an advertised feature had never run" is more useful than
   "fixed overlay".
4. **Update `TODO.md`**: mark the version's section as released, tick what
   shipped, and move anything that slipped to the next version.
5. **Run everything**:
   ```sh
   make all
   ```
   That covers syntax, shellcheck, the version agreement, provenance, the
   documentation links, and the host test suite.
6. **Test on a device.** This is the part no amount of CI replaces:
   ```sh
   adb shell su -c 'sh /sdcard/debdroid/tests/device.sh --session'
   ```
   Target at least one armv7 and one arm64 device on different android
   versions. The **oldest-kernel device is the gate for anything filesystem
   related**, since that is the only place a stray feature flag actually shows
   up.
7. **Build the module:**
   ```sh
   sh module/build.sh
   ```
   It refuses to package when `module.prop` and `DEBDROID_VER` disagree.
8. **Tag it**, which makes CI additionally assert the tag matches the tree:
   ```sh
   git tag -a v1.2.6 -m 'DebDroid v1.2.6'
   git push origin v1.2.6
   ```
9. **Publish the release** with the module zip, its checksum, and the changelog
   section as the release notes.

## Refreshing the debian image

The image is prebuilt and published as a release asset, so users install
something ready rather than assembling a filesystem. That only works if it is
kept current: a stale image means every new user's first `apt upgrade` is
enormous.

```sh
sudo sh tools/refresh-image.sh --parts
```

It mounts the image, runs the upgrade inside it, cleans the caches and logs,
and prints the manifest line to paste into `rootfs/manifest`.

Monthly is plenty; after a Debian point release is better. The tool **never
reformats** the image: its feature set is the old-kernel-safe one that a modern
`mke2fs` would not reproduce, so it updates in place and aborts if the features
drifted.

The release workflow re-checks that independently before publishing, since an
image carrying `has_journal`, `metadata_csum` or `64bit` would not mount on the
kernels this project exists for.

## What must be true before tagging

- `make all` passes.
- The device suite passes on at least one real device, with `--session`.
- `provenance/manifest` records every binary, with current checksums.
- `rootfs/manifest` matches the image actually shipped; `make test` checks this.
- No APK is committed, and no `unshare`/`nsenter` flag other than `--mount`.
  CI enforces both.

## Version numbering

`MAJOR.MINOR.PATCH`.

- **Patch** — fixes, and additions that change nothing existing.
- **Minor** — new capability, or a new supported architecture.
- **Major** — a change that breaks an existing install or configuration.

1.2.6 was a patch release despite adding background mode, because nothing
previously working changed behaviour. Reserve minor bumps for when they do.
