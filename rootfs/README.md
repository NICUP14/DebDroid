# Rootfs Manifest

[manifest](manifest) lists the debian filesystems DebDroid can install.

## Why the checksums live here

They travel with the scripts and are reviewable in the commit history, so a
payload's checksum never comes from the same server as the payload. A checksum
served next to the file it describes proves nothing.

What that does and does not guarantee is set out in
[docs/reference/integrity.md](../docs/reference/integrity.md), including the
fact that checksums in a repository are not signatures.

## Why tarballs rather than images

A tarball carries no filesystem. The image is created on the device by
busybox's `mkfs.ext2`, which can only produce ext2 — so a modern `mke2fs`
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
