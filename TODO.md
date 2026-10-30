# TODO List

Progress against the roadmap. See [CHANGELOG.md](CHANGELOG.md) for what each
release actually shipped.

## Done in v1.2.6

- [X] Implement DebDroid background mode. (start, stop, attach, status)
- [X] Implement a smart configuration system for `debdroid_mgr.sh`
- [X] Rescue and diagnostics: `tests/device.sh` reports what a bug needs.
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
- [X] QoL commands: `user`, `sshserver`, `vncserver`, `swap`.
- [X] Magisk, KernelSU and APatch module.
- [X] Automated tests and CI, replacing manual checking.
- [X] Record the provenance of every bundled binary; drop the committed APKs.
- [X] Update docs:
  - [X] Split them into guide and reference tiers.
  - [X] Add the x11 xfce4 vnc guide.
  - [X] Document the `debdroid_mgr.sh` configuration flags.
  - [X] Add preloading and binary mentions to the security page.
  - [X] Add the randomness libraries to the patches page.

## Next

- [ ] Build the armhf, amd64 and i386 binaries. The scripts and recipes are in
      place; only the artifacts are missing. `amd64` is worth doing early, as it
      makes the test suite runnable in CI against the real binaries.
- [ ] Self-build busybox with `CONFIG_SSL_CLIENT`, which empties tier C of the
      provenance manifest and drops the separate `ssl_helper` binary.
- [ ] Publish the rootfs tarballs and fill in `rootfs/manifest`, then implement
      the on-device bootstrap that creates the image with `mkfs.ext2`.
- [ ] Move the image and binaries out of git to release assets. Note this only
      stops future bloat; reclaiming the 170 MB already in history needs a
      rewrite, which breaks existing clones.
- [ ] Tier 2 tests: run the real manager against a scratch ext2 image on a
      Linux host with root.
- [ ] A one-line bootstrap installer.

## Later

- [ ] A signature verifier (`signify` or `minisign`), built from source, so the
      update path is protected by a signature rather than only by checksums
      held in the repository. See `docs/reference/integrity.md`, which is
      explicit about this gap.
- [ ] A thin terminal APK, so DebDroid has an installable icon. The groundwork
      is background mode, which is done: the app would attach to a running
      session.
- [ ] Benchmarks against a Pi Zero 2 and a Pi 3, published. The comparison
      table in the README makes claims that measurements would settle.
- [ ] More recipes: Nextcloud, Home Assistant, a media server.
- [ ] Add a statically-compiled `strace` for issue reporting.
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
