[← Docs](../README.md)

# Releasing

## When to release

Not every fix is a release. Pushing commits publishes nothing, and a release
happens only when someone creates a version tag, so this is a decision rather
than a consequence.

**These earn a release:**

- a capability a user can see and use
- a fix for something that breaks, loses data, or risks the device
- a security fix
- a refreshed debian image

**These do not.** They land on `main` and ride along with the next release:

- refactors and internal cleanups
- documentation changes
- tests and CI work

A release is not free, which is the reason for the distinction:

- `module/update.json` makes every user's Magisk, KernelSU or APatch app prompt
  them to update. Releasing per bugfix nags every user for every fix.
- `versionCode` can only go up, so a version spent is spent.
- Each release publishes roughly 300 MB of assets.
- A published release is awkward to retract once anyone has installed it.

**Releases come from `main`.** The workflow resolves the tagged commit and
refuses to publish unless it is reachable from `main`, so a tag pushed from a
working branch fails fast and uploads nothing. Merge first, then tag.

## The version is in one place

`DEBDROID_VER` in `debdroid/debdroid_env.sh` is the single source of truth.

Several other files repeat it, and a stale copy is worse than no copy:
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
   Say what the user-visible effect was, not just what changed. "The overlay
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
7. **Build the modules:**
   ```sh
   sh module/build.sh out/DebDroid-module.zip
   sh module/build.sh --with-image out/DebDroid-module-offline.zip
   ```
   Either refuses to package when `module.prop` and `DEBDROID_VER` disagree.
   The release workflow runs both, so this is a local check rather than a step
   whose output is published.
8. **Tag it**, which makes CI additionally assert the tag matches the tree:
   ```sh
   git tag -a v1.2.6 -m 'DebDroid v1.2.6'
   git push origin v1.2.6
   ```
9. **The workflow publishes the release.** It extracts the version's own
   section from `CHANGELOG.md` as the release notes, so the notes are only as
   good as that section, and it uploads the four assets below with their
   checksums.

## What a release publishes

| Asset | Size | For |
| --- | --- | --- |
| `DebDroid-module.zip` | ~4 MB | The normal path. Downloads the image on first install. |
| `DebDroid-module-offline.zip` | ~150 MB | Installing on a device with no usable network. |
| `debian.img.gz` | ~146 MB | What the slim module fetches, and a manual download. |
| `SHA256SUMS` | | Verifying any of the above. |

The two zips come from the same `module/build.sh`; `--with-image` is the only
difference. The image is **opt-in** rather than included when `img/` happens to
exist, because `img/` always exists in a checkout: the split parts are tracked
in git, so presence is not a signal. `module/update.json` points at the slim
zip, which is correct, as a module-manager update always finds an image already
on the device and never downloads one.

Neither variant needs an installer change. `install.sh` already decides what to
do from whether an image is present: it keeps one that is already on the
device, assembles one shipped beside the scripts, and otherwise downloads one.

### The zips are reproducible

Building the same tree twice produces the same bytes, so anyone can rebuild a
published zip and compare it against `SHA256SUMS` rather than taking the
maintainer's word for what is inside it.

Three things make that true, all in `module/build.sh`: every staged file gets
one fixed timestamp, the entry list is sorted so order does not depend on
`readdir`, and `zip -X` drops the uid, gid and high-precision timestamps that
would otherwise differ per machine.

The timestamp comes from `SOURCE_DATE_EPOCH` when it is set, and otherwise from
the commit date, which ties a zip to what it was built from rather than to when
it ran. To check a published zip:

```sh
git checkout v1.2.6
sh module/build.sh out/DebDroid-module.zip
sha256sum out/DebDroid-module.zip    # compare against the release's SHA256SUMS
```

This is a weaker guarantee than a signature and does not replace one: it proves
the zip matches the tree, not that the tree is the one you meant to trust. See
[integrity](integrity.md).

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
- `CHANGELOG.md` has a section for the version, since the workflow fails rather
  than publish empty release notes.
- Both module zips build, and the slim one is a few megabytes rather than a
  hundred and fifty.
- The device suite passes on at least one real device, with `--session`.
- `provenance/manifest` records every binary, with current checksums.
- `rootfs/manifest` matches the image actually shipped; `make test` checks this.
- No APK is committed, and no `unshare`/`nsenter` flag other than `--mount`.
  CI enforces both.

## Version numbering

`MAJOR.MINOR.PATCH`.

- **Patch**: fixes, and additions that change nothing existing.
- **Minor**: new capability, or a new supported architecture.
- **Major**: a change that breaks an existing install or configuration.

1.2.6 was a patch release despite adding background mode, because nothing
previously working changed behaviour. Reserve minor bumps for when they do.
