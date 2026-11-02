# DebDroid Documentation

Everything is one click from here. The guides tell you what to do and assume
nothing. The reference explains how it works, for contributors, packagers and
anyone auditing what runs on their device.

## Start here

Three steps. If one of them goes wrong, the linked page covers it in full.

**1. Check your phone can run it.** In a terminal app:

```bash
su                              # the prompt should change to #
getprop ro.product.cpu.abi      # should say arm64-v8a
```

Both have to work. You also need Android 5.0 or later and 1 GB free. If the
first command fails, the device is not rooted and DebDroid cannot run on it.
More detail: [will it work on my phone?](guide/requirements.md)

**2. Install it.** If you rooted with Magisk, KernelSU or APatch, download
`DebDroid-module.zip` from the
[releases page](https://github.com/NICUP14/DebDroid/releases) and flash it in
your root app. No terminal needed. It downloads Debian while installing, so the
phone needs Wi-Fi. Other ways, including one that needs no network, and what to
do when it fails: [install DebDroid](guide/install.md)

**3. Open a session:**

```bash
su
sh /sdcard/debdroid/debdroid.sh
```

You are in Debian. `apt install` anything. `exit` leaves.

Next: [first steps](guide/first-steps.md) covers making a user, which you want
before running much else.

## Guides

Most people come for one of these.

- **A server that stays up.** Debian stops when you close the terminal unless
  you tell it not to. [Keep it running](guide/keep-it-running.md) is the page to
  read before setting up anything that needs to stay running.
- **SSH from a laptop**, so you stop typing on a phone keyboard:
  [connect from your computer](guide/remote-access.md)
- **A desktop** with Firefox and an office suite, over VNC:
  [run a desktop](guide/desktop.md)
- **A finished project** rather than a toolkit:
  [recipes](guide/recipes/README.md) has ad blocking, file sync, a VPN, a git
  server and a CI runner, each start to finish.

When something breaks, [something went wrong](guide/problems.md) is organised by
the message you are actually looking at. Before filing anything, this reports
your whole setup and changes nothing:

```bash
su
sh /sdcard/debdroid/tests/device.sh
```

Also here: [manage storage](guide/storage.md) if you are running out of space,
[update DebDroid](guide/updating.md), and [uninstall](guide/uninstalling.md) for
what gets removed and what is kept.

## Reference

Two constraints shape every design decision. Both look like oversights, neither
is, and CI fails the build on either being violated.

**Only the mount namespace.** Android devices support no other, which was
established by testing across a range of them rather than assumed. So the
supervisor is not PID 1 of anything and cannot reap orphans, process teardown
scans `/proc/*/ns/mnt` instead of using a PID namespace, and sessions share
Android's process list and network stack. Those are consequences, not bugs.
Details in
[session lifecycle](reference/session-lifecycle.md#why-only-a-mount-namespace).

**The image stays ext2.** Old phone kernels reject the features a modern
`mke2fs` enables by default, producing an image the target devices cannot mount
at all. busybox's `mkfs.ext2` is ext2-only, so on-device creation is safe by
construction. Details in [architecture](reference/architecture.md#why-ext2).

**How it works:** [architecture](reference/architecture.md) ·
[session lifecycle](reference/session-lifecycle.md) ·
[mounts](reference/mounts.md) · [patches](reference/patches.md)

**Using it:** [every command](reference/cli.md) ·
[configuration](reference/configuration.md) ·
[output conventions](reference/output.md)

**Trust:** [security](reference/security.md) ·
[integrity](reference/integrity.md) · [signatures](reference/signatures.md)

[Integrity](reference/integrity.md) is worth reading before trusting anything
here. Checksums held in this repository are not signatures, and the page says
so plainly.

**Building and testing:** [building](reference/building.md) ·
[testing](reference/testing.md) · [releasing](reference/releasing.md)

If you are changing code, [AGENTS.md](../AGENTS.md) is the orientation file:
conventions, which file to change for a given task, and the gotchas that
produce code which looks correct and breaks on real hardware.

```sh
make all    # syntax, shellcheck, tests, provenance, docs, versions
```

## Resources

- **[Gallery](gallery.md)**: what it actually looks like running.
- **[Icon](../res/icon/README.md)**: the launcher icon, its colours, and the
  vector sources, for anyone packaging DebDroid into an app.
- **[Third-party components](../THIRD_PARTY.md)**: where every bundled binary
  came from, with checksums.
- **[Changelog](../CHANGELOG.md)**: what each release shipped.
- **[Roadmap](../TODO.md)**: open work, by target version.
- **[Releases](https://github.com/NICUP14/DebDroid/releases)**: the module zips
  and the prebuilt image.

---

**Nothing in this repository has been verified on real hardware yet.** Every
device-dependent claim in these pages should be read as unverified until
`tests/device.sh` has run on a phone.
