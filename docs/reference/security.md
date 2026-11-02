[← Docs](../README.md)

# Security

## What you are trusting

Running DebDroid means trusting four things:

1. **The device's kernel**, which on a device old enough to be spare is almost
   certainly unpatched.
2. **The DebDroid scripts**, which run as root.
3. **The bundled binaries**, which run as root. See
   [third-party components](../../THIRD_PARTY.md) for where each came from and
   [integrity](integrity.md) for how downloads are verified.
4. **Whatever you install inside debian**, which also runs as root unless you
   create a normal user.

This page is about the first one, which is the part you cannot fix.

## Unpatched kernels

Most devices DebDroid targets have reached end of life. They no longer receive
security patches or kernel updates, so every vulnerability found since their
last update remains open.

This is not a small set. Android kernel vulnerabilities with public exploits
are common, and a device three or more years past its final update should be
assumed exploitable by anyone who can reach it.

**The practical consequences:**

- Do not put the device directly on the internet. Forwarding a port to it is
  the single worst thing you can do with it.
- Do not keep anything sensitive on it, or on anything it can reach.
- Treat it as disposable: useful for work you would not mind seeing exposed.
- If you need remote access, use a VPN such as WireGuard or Tailscale, so the
  device is never directly reachable.

DebDroid is a good way to get use out of hardware that would otherwise be
landfill. It is not a way to make that hardware trustworthy.

## Root is required, and that is the trade

DebDroid cannot work without root: mounting a filesystem image, creating a
mount namespace and calling `chroot` all require it.

What root buys is a real glibc userland, so debian packages run unmodified and
nearly the whole archive is available. Tools that avoid root, such as Termux or
proot-based distributions, pay for it in rebuilt packages and emulation
overhead.

Rooting a device weakens its security model, and that is a real cost. It is the
right trade for a spare device being repurposed, and the wrong one for your
daily phone.

## Isolation, and its limits

A session runs in a private mount namespace, so its mounts are invisible to
android and vanish when the session ends. That is isolation of the
**filesystem view**, and nothing more.

**It is not a container.** Specifically:

- **No PID namespace.** Processes in the session are visible to android, and
  android's are visible to debian.
- **No network namespace.** The session shares android's network stack
  entirely.
- **No user namespace.** Root in the session is root on the device.
- **No resource limits.** A runaway process can exhaust the device's memory.

None of this is an oversight; android devices do not support those namespaces.
See [session lifecycle](session-lifecycle.md#why-only-a-mount-namespace).

**The practical consequence:** code you run inside DebDroid can affect android.
There is no security boundary between the two. Do not use it to run untrusted
code.

## Writes that reach android

Deliberately:

- `/sdcard` is bind-mounted, so debian can read and write your phone's storage.
- `/system` and `/apex` are mounted read-only.
- `sysctl` changes, the wakelock and swap are system-wide, since they belong to
  the device rather than the session.

Not deliberately, and fixed in 1.2.6:

- `/dev` entries were created in android's real `/dev` before isolation. The
  device test suite now compares `/dev` before and after a session to catch
  any recurrence.
- Config files written into the image could escape through an absolute symlink
  and land on android's real `/etc`. They are now unlinked before being
  written. See [mounts](mounts.md#configuration-files-written-into-the-image).

## Randomness

The preloaded libraries replace `getrandom`, `getentropy` and `arc4random`
with direct `/dev/urandom` reads, because android's kernel does not provide
what glibc expects.

This is sound for a running system: the kernel seeds `/dev/urandom` from the
same pool. The one behaviour it does not reproduce is blocking on a freshly
booted system with no entropy, which does not arise for sessions started after
boot. See [patches](patches.md#is-replacing-the-randomness-source-safe).

## Reducing your exposure

- **Create a normal user** rather than working as root:
  `debdroid.sh command user`.
- **Keep the device off the internet**, behind a VPN if you need remote access.
- **Use a strong password** for SSH, or keys instead, and keep the default port.
- **Stop the environment** when you are not using it: `debdroid.sh stop`.
- **Verify what you installed**, both the apps and DebDroid's own files. See
  [signatures](signatures.md).
- **Keep debian updated**, which is the one layer that still gets patches:
  `debdroid.sh command update`.

## Reporting a problem

Security issues in the DebDroid scripts are worth reporting even if they seem
minor, particularly anything where a path, a filename or a command argument
reaches a privileged operation unvalidated. Those are the bugs that have
mattered here: the ones fixed in 1.2.6 were a symlink escape and an unquoted
command.
