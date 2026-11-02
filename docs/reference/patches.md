[← Docs](../README.md)

# Patches

Debian software expects a Linux kernel that behaves in ways android's does not.
DebDroid closes the gap with preloaded libraries and a bootstrap script, rather
than by modifying the debian installation, so an `apt upgrade` cannot undo the
fixes.

## Preloaded compatibility libraries

Registered in `/etc/ld.so.preload` at session start, so they load into every
process debian runs.

| Library | Fixes |
| --- | --- |
| `librandom.so` | The `getrandom` syscall and `getentropy` |
| `libarc4random.so` | glibc's `arc4random` family |

Both redirect to reading `/dev/urandom` directly.

### Why this is needed

`getrandom` either is missing or misbehaves on the kernels DebDroid targets.
Anything that needs randomness at startup then fails outright, which includes
`gpg`, `sshd` and much of the TLS stack.

### Is replacing the randomness source safe

Reading `/dev/urandom` is appropriate here. On Linux and android the kernel
seeds `/dev/urandom` from the same pool `getrandom` uses, so the quality is the
same once the pool is initialised, which on a device that has finished booting
it is.

The one thing this does not reproduce is `getrandom`'s blocking behaviour on a
freshly booted system with no entropy. DebDroid sessions start well after boot,
so this does not arise in practice. It would matter on an embedded device
generating keys seconds after power-on.

> [!IMPORTANT]
> These libraries are built against **glibc**, not android's bionic, because
> they are loaded inside the debian chroot. Building them with the NDK produces
> objects debian's linker cannot load at all. This is the easiest mistake to
> make when adding an architecture; see [building](building.md).

Turning off `CONFIG_DEBDROID_LIB` disables all of this.

## The bootstrap

`bin/debinit` runs inside the chroot at session start, and logs to
`/tmp/debinit.log`.

### The inet group

Android restricts network sockets to members of group `3003`, called `inet`.
Debian knows nothing about this, so a user created normally cannot use the
network at all: `ping` and `apt` both fail with permission errors that do not
mention groups.

The bootstrap creates the group, and `command/user.sh` adds new users to it.
This was the single most common thing new users hit.

### The apt user

`apt` runs its downloads as the unprivileged `_apt` user, which hits exactly
the problem above. The bootstrap puts `_apt` in `inet`, which is what makes
`apt update` work.

### Runtime directories

`/run/sshd` and `/run/dbus` are created if missing, as the services that need
them will not start otherwise and `/run` is a tmpfs that starts empty.

### D-Bus

Started if `dbus-daemon` is installed, since desktop software expects a system
bus. A stale `/run/dbus/pid` is removed first.

> [!NOTE]
> `dbus-daemon` was the cause of the image corruption fixed in 1.2.5: it kept
> running after a session exited, held the image mounted, and the next session
> mounted it again. The teardown now stops every process in the namespace.

## Patched programs

Programs known to work in the environment as a result:

| Program | Needed |
| --- | --- |
| `gpg` | the randomness fixes |
| `sshd` | the randomness fixes, `/run/sshd`, the `inet` group |
| `apt` | `_apt` in the `inet` group |
| XFCE4 | D-Bus, the shared memory limit, the power manager disabled |
| `snapd` | work in progress; it expects systemd |

## What is not patched

**systemd does not run.** It expects to be PID 1, and a PID namespace is not
available on android. Services are started directly instead, which is why
`command/sshserver.sh` runs `sshd` rather than `systemctl start ssh`.

Anything that hard-depends on systemd will not work. Most things offer a way
around it.
