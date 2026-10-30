# DebDroid Reference

Technical documentation, for contributors, packagers and anyone auditing what
DebDroid does on a device.

If you just want to use DebDroid, the [guides](../guide/README.md) are written
for that and assume nothing.

## How it works

- **[Architecture](architecture.md)** — the layout and what each piece does.
- **[Session lifecycle](session-lifecycle.md)** — namespace, lock, mount order, teardown, background supervisor.
- **[Mounts](mounts.md)** — what gets mounted, why, and the `/dev` strategy.
- **[Patches](patches.md)** — the preload libraries and the programs they fix.

## Using and configuring it

- **[Command reference](cli.md)** — every command, option and exact grammar.
- **[Configuration](configuration.md)** — the `CONFIG_*` flags and `debdroid_env.sh`.
- **[Output conventions](output.md)** — warnings, errors and `NO_COLOR`.

## Trust and security

- **[Security](security.md)** — threat model, and what an EOL kernel means.
- **[Integrity](integrity.md)** — how downloads are verified, and the limits of that.
- **[Signatures](signatures.md)** — verifying the apps and release files you install.
- **[Third-party components](../../THIRD_PARTY.md)** — where every bundled binary came from.

## Building and testing

- **[Building](building.md)** — cross-compiling the bundled binaries.
- **[Testing](testing.md)** — the three test tiers and how to run them.
- **[Releasing](releasing.md)** — the release process and where versions live.

## Design constraints

Two limits shape the whole design, and both look like oversights until you know
why they are there. They are documented in
[session lifecycle](session-lifecycle.md#why-only-a-mount-namespace) and
[architecture](architecture.md#why-ext2).
