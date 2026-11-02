# Rootfs Manifest

> [!IMPORTANT]
> **This directory is maintainer tooling. Users never touch any of it.**
>
> Installing DebDroid is either one tap in a root app or three lines in a
> terminal, and the debian filesystem arrives ready to use. Nobody installing
> DebDroid runs `debootstrap`, builds a tarball, or reads this manifest.
>
> See [the install guide](../docs/guide/install.md) for what users actually do.

[manifest](manifest) lists the debian filesystems DebDroid can install, and the
scripts here build and publish them. The published result is what a device
downloads and verifies automatically.

## Two checksums, two jobs

Most mirrors publish a checksum file beside the artifact, and an entry can
record where with a `sha256url=` field. Both are used, because they catch
different things.

**The checksum in this manifest** travels with the scripts and is reviewable in
the commit history, so it does not come from the server that serves the
payload. That is what makes it useful against a tampered artifact: an attacker
who can replace the tarball on a mirror can also replace the checksum file next
to it, but not the one recorded here.

**The mirror's own checksum file** catches a corrupted or truncated download,
and cross-checks this manifest. A disagreement between the two is reported
loudly rather than quietly preferring one: it means either the mirror changed
the artifact or this manifest has gone stale, and both are worth knowing.

### The exception worth knowing about

Debian's mirrors sign their checksums. The `InRelease` file is signed by the
Debian archive keyring and transitively covers the package hashes, so it is
anchored in a key rather than in the transport.

That is a **stronger** anchor than this manifest, which anyone with push access
to this repository could rewrite. `build.sh` gets the benefit already, because
`debootstrap` verifies against it at build time. But that happens on a
maintainer's machine, not on the device.

Carrying it through to the device needs a signature verifier, which busybox does
not include. It is the same gap tracked in
[docs/reference/integrity.md](../docs/reference/integrity.md), which is explicit
about checksums in a repository not being signatures.

## Why tarballs rather than images

A tarball carries no filesystem. The image is created on the device by
busybox's `mkfs.ext2`, which can only produce ext2, so a modern `mke2fs`
cannot put features in it that old phone kernels refuse to mount.

A prebuilt image skips that safeguard, which is why an `IMAGE` entry has to
record its filesystem and feature flags, and why `TARBALL` is preferred.

## Building the tarballs

On a Linux machine with root:

```sh
sudo sh rootfs/build.sh bookworm arm64
```

It runs `debootstrap` in two stages with `qemu-user-static` for foreign
architectures, applies the DebDroid fixes, and writes a tarball plus its
checksum. Add the result to the manifest, then `make provenance`.

## Mirrors

Each entry may list several URLs. They are tried in order, so one server being
unavailable is not an outage. Any mirror's content still has to match the
checksum recorded here.
