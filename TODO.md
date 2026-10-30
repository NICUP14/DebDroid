# TODO List

Work is tracked against the version it is targeted at. The current version is
the one in `debdroid/debdroid_env.sh`, which is the single source of truth;
CI checks that this file agrees with it.

**Current version: v1.2.6**

See [CHANGELOG.md](CHANGELOG.md) for what each release actually shipped.

## For DebDroid v1.2.6 (released)

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
      Note this only stops future bloat; reclaiming the 170 MB already in
      history needs a rewrite, which breaks existing clones and forks, so it is
      a decision to take deliberately at a release boundary.
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
