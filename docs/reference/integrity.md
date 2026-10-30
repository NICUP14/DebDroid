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
live in [provenance/manifest](../../provenance/manifest), travel with the
scripts, and are reviewable in the commit history.

The rule that follows is the important one:

> **A payload's checksum is never fetched from the same place as the payload.**
> A checksum served next to the file it describes proves nothing, because
> whoever changed one could change the other.

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

Closing that gap needs a signature verifier on the device, and busybox does not
include one. The plan is to build a small static `signify` or `minisign` from
source, pin its public key in the installed tree, and have the updater check a
signed release manifest whose checksums then cover each payload — a signature
at the root, hashes underneath.

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
