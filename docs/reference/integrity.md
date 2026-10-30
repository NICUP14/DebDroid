[← Reference](README.md)

# Integrity

DebDroid runs as root. Anything it downloads and installs therefore runs as
root too, which makes a tampered file a compromised device rather than a failed
download. This page states exactly what is guaranteed, and what is not.

## The invariant

**Nothing unverified is executed, extracted or moved into place.**

Every fetch follows the same order:

1. Download to a staging directory.
2. Verify the SHA-256 checksum.
3. Only then unpack, `chmod`, install or run it.

Nothing is made executable before verification, and nothing is unpacked before
verification. A mismatch aborts and leaves the existing installation
**completely untouched** — a partly applied update is the one outcome worse
than a failed one.

## Where the checksums come from

The trust anchor is this git repository, not the download server. Checksums
live in [provenance/manifest](../../provenance/manifest) and
[rootfs/manifest](../../rootfs/manifest), travel with the scripts, and are
reviewable in the commit history.

The rule that follows is the important one:

> **A payload's checksum is never *only* fetched from the same place as the
> payload.** A checksum served next to the file it describes cannot establish
> that the file was not tampered with, because whoever changed one could change
> the other.

### Mirror checksums are still used

Most mirrors do publish a checksum beside the artifact, and DebDroid fetches it
where an entry records one. It is not redundant — it does a different job:

| Source | Catches |
| --- | --- |
| The manifest here | A tampered or substituted artifact |
| The mirror's checksum file | A corrupted or truncated download |

A disagreement between the two is reported loudly rather than resolved
silently, because it means either the mirror changed the artifact or this
manifest has gone stale.

### Where a mirror's checksums are better than ours

Debian's are **signed**. The `InRelease` file is signed by the Debian archive
keyring and transitively covers the package hashes, which anchors it in a key
rather than in the transport.

That is a stronger guarantee than this repository offers, since anyone with
push access here could rewrite a checksum. `rootfs/build.sh` already benefits,
because `debootstrap` verifies against the signed release while building — but
that happens on a maintainer's machine, and what reaches the device afterwards
is a tarball covered only by a checksum recorded here.

Carrying a signature all the way to the device is the open gap described below.

## Why HTTP fallback is not a hole

Downloads try HTTPS and fall back to plain HTTP with a warning. That sounds
wrong, and would be, if the checksum came from the connection.

It does not. An attacker who blocks port 443 to force the fallback still cannot
produce a file matching a hash recorded in the repository. The fallback exists
because old devices have outdated certificate stores and dated TLS stacks,
where a checksum-verified HTTP download is genuinely more reliable than an
HTTPS download that simply fails.

Two limits on it: it is never silent, and it is never used for the manifest
that carries the checksums.

## What this does not protect against

This is the part worth reading.

**Checksums in git are not signatures.** Anyone who can push to this repository
can change the expected hashes along with the files they describe. Verification
proves a download matches what the repository says it should be; it does not
prove the repository itself was not tampered with.

This is the one place where upstream does better than DebDroid: Debian's
release files are GPG-signed, so their chain is rooted in a key. DebDroid's is
rooted in a git history, which is weaker.

Closing it means signing DebDroid's own releases and pinning the public key in
the installed tree. That is a small piece of work, not a distant one: `signify`
and `minisign` are a few hundred lines of Ed25519 verification and cross-compile
statically with the same NDK pipeline that already builds e2fsprogs. It is
tracked for v1.3, and `tools/sign-release.sh` plus `verify_signature` in
`debdroid_lib.sh` are the scaffolding, waiting on a published key.

### Why Debian's own key does not cover the tarball

It would be reasonable to ask why DebDroid does not simply verify Debian's
signature on the device instead of minting its own key. The answer is that
there is nothing of Debian's to verify at that point.

Debian signs the **archive**: `InRelease` is signed by the archive keyring and
covers the package indices, which in turn cover the `.deb` files. It does not
sign rootfs tarballs, because a rootfs tarball is not one of Debian's
artifacts — it is one of ours, assembled from theirs.

That signature chain is still doing real work, just earlier:
`rootfs/build.sh` runs `debootstrap`, which verifies the archive signature
while fetching every package. So the contents are verified against Debian's key
at build time, on a maintainer's machine. What ships afterwards is a file this
project produced, and only this project can vouch for it.

Verifying the Debian archive **on the device** would only matter for running
`debootstrap` there, which would need `gpgv` and libgcrypt — `gpgv` being the
verify-only binary `apt` and `debootstrap` themselves use. That is a much larger
dependency than an Ed25519 verifier, and it buys nothing for the current design,
where the device installs a tarball rather than bootstrapping from the archive.

Once the rootfs is installed, `apt` inside debian verifies against Debian's keys
normally, with no help from DebDroid. The gap is confined to the initial
tarball and to DebDroid's own release files.

**Until that ships, the honest summary is:** downloads are protected against
tampering in transit and against a compromised mirror, but not against a
compromised repository.

## The one-liner installer

A `curl | sh` style installer runs before you can read what it does. That is
inherent to the format, not something DebDroid can fix.

Mitigations: the one-liner fetches, verifies against a fingerprint printed in
the README, and only then executes. If you would rather inspect things first,
the Magisk module and the manual zip are both verifiable before anything runs.

## Checking things yourself

Verify the bundled binaries against the manifest:

```sh
sh tools/verify-provenance.sh
```

Verify a release file you downloaded:

```sh
sha256sum DebDroid-1.2.7.tar.gz
```

and compare it against the published checksum.

See also [signatures](signatures.md) for the apps DebDroid recommends, and
[third-party components](../../THIRD_PARTY.md) for where each binary came from.
