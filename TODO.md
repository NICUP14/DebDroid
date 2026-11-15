# TODO List

Work is tracked against the version it is targeted at. The current version is
the one in `debdroid/debdroid_env.sh`, which is the single source of truth;
CI checks that this file agrees with it.

**Current version: v1.2.6**

See [CHANGELOG.md](CHANGELOG.md) for what each release actually shipped.

## For DebDroid v1.2.6 (released 2026-11-14)

- [X] Implement DebDroid background mode. (start, stop, attach, status)
- [X] Implement a smart configuration system for `debdroid_mgr.sh`
- [X] Report command functionality. (`tests/device.sh` auto-generates what an
      issue report needs)
- [X] Update command functionality. (`debdroid.sh self-update`)
- [X] Uninstall functionality.
- [X] Fix the `/dev` overlay, which had never actually mounted.
- [X] Stop writing to android's real `/dev`.
- [X] Stop writes into the image escaping through symlinks.
- [X] Fix the user command losing its quoting.
- [X] Stop the installer discarding user configuration.
- [X] Mount `/apex` for android 10 and later.
- [X] Wakelock support, so an always-on server stays reachable.
- [X] Swap support, replacing the third-party magisk module.
- [X] Start at boot, via a guarded `service.d` hook.
- [X] Add QoL commands:
  - [X] `command/update`: Environment updater script.
  - [X] `command/user`: Non-root user setup.
  - [X] `command/vncserver`: Easy vnc server management.
  - [X] `command/sshserver`: Easy ssh server management.
  - [X] `debdroid.sh swap`: Swapfile management (android side, not in command/).
- [X] Magisk, KernelSU and APatch module.
- [X] Automated tests and CI, replacing manual checking.
- [X] Record the provenance of every bundled binary; drop the committed APKs.
- [X] Update docs:
  - [X] Split them into guide and reference tiers.
  - [X] Add x11 xfce4 vnc guide.
  - [X] Document `debdroid_mgr.sh` configuration flags.
  - [X] Add preloading and binary mentions to `docs/reference/security.md`.
  - [X] Add randomness library mentions to `docs/reference/patches.md`.
  - [X] Add ReTerminal mention to the install guide.

### Before tagging

Everything above is done. These are what stand between the tree and a
published release, listed here rather than left in someone's head.

- [X] Stop the module zip bundling the image. It was 150 MB, of which the
      scripts and binaries were 4 MB, and the release published the same image
      again as a separate asset. A release now builds two zips: the slim one,
      which downloads the image on first install, and
      `DebDroid-module-offline.zip`, which bundles it for a device with no
      usable network.
- [X] Publish only the version's own changelog section as the release notes,
      rather than the entire file back to 1.0.
- [X] Refresh the debian image. It was built 2025-09-02, so a new user's first
      `apt upgrade` was enormous. Now bookworm 12.15, and smaller than before:
      370 MiB of content against 446 MiB, because `autoremove --purge` and
      `clean` take out more than a point release adds.
- [ ] Run the device suite with `--session` on an armv7 device, and on android
      10 or later, where `/apex` exists and the chroot does not mount it.
      Neither is a release blocker on its own: armv7 ships no binaries yet, and
      `arch_is_built` refuses it rather than failing later.
- [ ] Merge `testing` into `main`, then repoint the README CI badge, which is
      pinned to `?branch=testing` because the workflow only exists there.
      The release workflow now refuses any tag that is not reachable from
      `main`, so this is enforced rather than remembered.
- [X] Bump `actions/checkout` to v5 and pin the runner image. Node 20 was
      deprecated and `ubuntu-latest` began migrating to Ubuntu 26 on
      2026-10-19, so both annotated every run. Now `actions/checkout@v5` on
      `ubuntu-24.04`, pinned so a runner migration cannot change the build
      under us.
- [X] After publishing, verify the assets from a clean machine. Done for
      v1.2.6: both zips and `debian.img.gz` match the published `SHA256SUMS`,
      the gzipped image decompresses to exactly the checksum `rootfs/manifest`
      records, and the slim zip rebuilds byte-for-byte identically to the
      published one from the tagged commit, which is the reproducibility claim
      verified against a real release rather than asserted.

## Known issues

- [X] **The slim module could not fetch the image, so a fresh install from it
      failed.** Fixed in the tree; it needs a release to reach anyone, as
      v1.2.6 shipped with it. Confirmed on android 7.0. Two independent
      causes, both in the transport and neither in DebDroid:
      - The bundled busybox has no `ssl_client` applet and its built-in TLS is
        too limited for the release host, which answers the handshake with
        alert 80 and resets the connection.
      - `/system/bin/curl` exists and is newer, but fails certificate
        verification (exit 60): the device's certificate store predates the
        chain the host serves today.

      The plain-http fallback in `debdroid_image.sh` cannot rescue it either,
      as the release host redirects every http request to https.

      **The offline zip is unaffected and verified working**, and a module
      manager *update* is unaffected too, since an image is already present by
      then. What breaks is a first install from the slim zip, which is the one
      `module/update.json` points at and the one the install guide sends
      people to.

      The fix needed no certificate bundle and disabled nothing. The two
      clients fail on opposite hosts and each succeeds where the other does
      not, so the redirect is resolved with `curl`, which can talk to the host
      that issues it, and the asset is pulled with busybox `wget`, which can
      talk to the host it points at. busybox `wget` never validated
      certificates anyway, so that is the same client the primary path already
      used, and the image is still only accepted if it matches the checksum in
      `rootfs/manifest`.

      Verified end to end: the slim zip now installs on a wiped device,
      downloads the published asset, lands on exactly the manifest checksum,
      and the suite passes 73 on the result.

- [ ] **The session's DNS is never the device's on android 8 or later.**
      `debdroid_mgr.sh` reads the nameservers from the `net.dns1` to `net.dns4`
      properties. Android 8 removed them, so on a modern device the loop finds
      nothing and every session falls back to `CONFIG_ENV_DNSSERVER`, which is
      `1.1.1.1`. The session therefore ignores a VPN or a Private DNS setting,
      and breaks outright on a network that blocks outside resolvers.

      **Deferred past 1.2.6 deliberately.** The android 7 device this was
      found on still has the old properties, so the replacement cannot be
      exercised here, and the fallback does at least resolve. Shipping an
      unverifiable rewrite of the resolver path is what the rest of this
      release argued against.

      The fix is not a one-liner: `dumpsys connectivity` carries the real
      servers but its output is not stable across versions, so it needs a
      tolerant parser, each candidate validated as an address before being
      written, and the current behaviour kept underneath. Worth pairing with
      the android 10 testing above, since that is the same device gap.

Defects that have been seen but not diagnosed. They are listed here rather than
under a target version, because what it takes to fix them is not known yet.

- [ ] **Socket errors inside the environment, unresolved.** Reported as
      recurring, but no symptom has been captured, so there is nothing here to
      act on yet and no guess should be written down in its place. It is not
      the `inet` group case: that one is understood, handled automatically by
      `command/user.sh`, and covered by `docs/guide/problems.md`.

      **Deliberately deferred past 1.2.6.** Releasing on it was weighed and
      declined: with no symptom there is nothing to fix, and holding the
      release would have kept a year-old image and a module that could not be
      flashed in front of users for longer. Not mentioned in the release notes
      either, as "socket errors may occur, cause unknown" tells a user nothing
      they can act on. Characterise it first, then fix it in the version it
      lands in.

      What to record the next time it happens, as it decides both the cause and
      whether it blocks a release:

      - the exact message, and which program printed it
      - the command that triggered it, and whether the session came from
        `debdroid.sh run` or from `attach`
      - whether it happens as root or only as a user created by
        `command/user.sh`, and the output of `id` for that user
      - whether it is a listening socket (a server failing to bind, and on
        which port) or an outgoing connection
      - whether it reproduces for `ping`, `apt`, `ssh` and a plain
        `getent hosts`, which separates a routing or DNS fault from a
        permission one
      - `cat /proc/net/tcp | head` and `ip addr` from inside the session

      Once the symptom is known: fix it under the version it lands in, and if
      users can hit it, add a row to the table in `docs/guide/problems.md`.

## Planned for v1.3

Multi-arch is the theme: the plumbing landed in 1.2.6, so this is mostly
producing and publishing artifacts.

- [ ] Multi-arch support:
  - [ ] Build the armhf binaries. This is the one that matters most, as the
        2012-2015 devices are largely 32-bit.
  - [ ] Build the amd64 binaries. Worth doing early for a second reason: it
        makes the test suite runnable in CI against the real binaries rather
        than host substitutes.
  - [ ] Build the i386 binaries.
  - [ ] Flip `arch_is_built` on for each as its artifacts land.
- [ ] Self-build busybox with `CONFIG_SSL_CLIENT`, which empties tier C of the
      provenance manifest and drops the separate `ssl_helper` binary.
- [ ] Sign the releases, and verify the signature on the device with a pinned
      key. `signify` or `minisign` is a few hundred lines of Ed25519 and
      cross-compiles with the same NDK pipeline as e2fsprogs, so this is small
      work rather than distant work. It closes the one gap
      `docs/reference/integrity.md` is explicit about: checksums held in this
      repository are not signatures.
      Note that debian's own key does not cover the tarball - debian signs the
      archive, which debootstrap already verifies at build time, so what ships
      afterwards needs our signature, not theirs.
- [ ] Add a statically-compiled `strace` binary for issue reporting.
- [ ] Rescue shell functionality for DebDroid.
- [ ] Tier 2 tests: run the real manager against a scratch ext2 image on a
      Linux host with root.

## Planned for v1.4

Distribution is the theme: get the repository down to scripts and recipes.

- [ ] Publish the rootfs tarballs and fill in `rootfs/manifest`.
- [ ] Implement the on-device bootstrap that downloads a tarball, verifies it
      and creates the image with `mkfs.ext2`, with an offline path for when the
      servers are unreachable.
      **This must stay invisible to users.** Installation is one tap or three
      terminal lines today, and moving the filesystem online must not turn it
      into anything more: the installer picks the right tarball, downloads it,
      verifies it and builds the image without asking anything. Nobody
      installing DebDroid should ever meet debootstrap or a manifest.
- [ ] Move the image and binaries out of git to release assets.
      Note this only stops future bloat; reclaiming what is already in history
      needs a rewrite, which breaks existing clones and forks, so it is a
      decision to take deliberately at a release boundary. That cost grows with
      every image refresh: `.git` is 328 MB as of the 12.15 refresh, against
      170 MB before it, and the working tree carries another 1.1 GB in `img/`.
- [ ] A one-line bootstrap installer, for people who would rather paste one
      command than use a file manager. A third option alongside the module and
      the zip, not a replacement for either.

## Planned for future versions of DebDroid

- [ ] A thin terminal APK, so DebDroid has an installable icon. The groundwork
      is background mode, which is done: the app would attach to a running
      session.
- [ ] Benchmarks against a Pi Zero 2 and a Pi 3, published. The comparison
      table in the README makes claims that measurements would settle.
- [ ] More recipes: Nextcloud, Home Assistant, a media server.
- [ ] GPU acceleration.

## Not doing

- [ ] ~~Reorganize and refactor preload libraries:~~
  - ~~Implement linker fix in `libexec.so`.~~
  - ~~Move syscall logic from `librandom.so` and link into `libsyscall.so`.~~

## Constraints that are not open questions

Two limits look like things to fix and are not. Both are documented, and CI
fails the build on either being violated:

- **Only the mount namespace.** Android devices support no other, established
  by testing across a range of them. See
  [session lifecycle](docs/reference/session-lifecycle.md#why-only-a-mount-namespace).
- **The image stays ext2.** Old kernels reject the features a modern `mke2fs`
  enables by default. See
  [architecture](docs/reference/architecture.md#why-ext2).
