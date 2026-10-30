[← Reference](README.md)

# Mounts

Every mount lives in the session's private mount namespace, so none of it is
visible to android and all of it disappears when the session ends.

Mount propagation is disabled first (`mount --make-rprivate /`), so nothing
done inside the namespace leaks back out.

## What gets mounted

| Path | Source | Required | Notes |
| --- | --- | --- | --- |
| `/` | the debian image, loop-mounted | yes | ext2, see [architecture](architecture.md#why-ext2) |
| `/proc` | `proc` | yes | |
| `/dev` | overlay, tmpfs or bind | yes | see below |
| `/dev/pts` | `devpts` | yes | terminals need it |
| `/dev/shm` | `tmpfs` | no | `mode=1777` |
| `/sys` | `/sys`, read-only | no | |
| `/tmp` | `tmpfs` | no | `mode=1777`, 64M |
| `/system` | `/system`, read-only | no | android's own tools |
| `/apex` | bind | no | android 10+, see below |
| `/sdcard` | bind | no | moving files in and out |
| `/debdroid/bin` | bind of the bundled binaries | yes | holds the bootstrap |
| `/debdroid/lib` | bind of the compatibility libraries | no | preloaded |

"Required" failures stop the session; the rest produce a warning and carry on.

Already-mounted targets are skipped rather than stacked, which is what makes
the startup idempotent.

### `/system` is mounted read-only, not bind-mounted

```sh
mount -r /system
```

This is deliberate and must not be changed to a bind mount. Doing so has been
observed to break the running system.

## `/dev`

Three strategies, tried in order. Which one a device gets is reported by
`tests/device.sh` and by the probe.

### 1. Overlay (preferred)

An overlayfs with android's `/dev` as the lower layer and a tmpfs as the upper,
so the session can create device nodes without touching android's.

Both `upperdir` and `workdir` must be on the **same filesystem**, so both are
subdirectories of one tmpfs (`mnt/dev-ovl/upper` and `mnt/dev-ovl/work`).

> [!NOTE]
> Until v1.2.6 the upper directory was a tmpfs while the work directory was on
> the ext2 image. The kernel rejects that combination, so the overlay never
> once mounted and every session silently used the bind fallback — a listed
> feature that had never run.

The upper directory is mode **755**, not 700: an overlay takes its mode from
the upper directory, and 700 would make `/dev` unreadable to every non-root
user inside the chroot.

### 2. Private tmpfs

If overlayfs is unavailable, or the kernel refuses a tmpfs as an upper layer —
which older kernels do — a plain tmpfs is mounted at `/dev` and the needed
nodes are copied in with `cp -a`, which recreates a device node rather than
copying its contents.

Android's `/dev` is still left alone, which is the point.

### 3. Bind mount (last resort)

The session shares android's real `/dev`. Everything works, but anything
written to `/dev` inside debian affects android too. A warning says so.

### Where the symlinks go

`/dev/fd`, `/dev/stdin`, `/dev/stdout`, `/dev/stderr` and `/dev/loopN` are
created **after** `/dev` is mounted, under the environment.

> [!NOTE]
> Until v1.2.6 they were created before any isolation, in android's real
> `/dev`, leaving entries behind in the running system. `tests/device.sh`
> compares `/dev` before and after a session specifically to catch this.

## `/apex`

Android 10 moved bionic and its linker into `/apex`. Without it, android's own
command-line tools — `am`, `settings`, `svc`, `dumpsys` — cannot run inside the
chroot on a modern device, because their linker is missing.

Bind-mounting it is what makes those reachable from debian, which is an
integration advantage that needs a separate addon app elsewhere.

Controlled by `CONFIG_ENV_APEX`, and skipped on devices without `/apex`.

## Teardown

Mount points are read from `/proc/self/mounts` and unmounted in reverse order,
so nested mounts go before their parents. This includes mounts the user made
inside the session.

The matching is prefix-aware: a path that merely shares a prefix with the
environment, such as a sibling directory called `mntother`, is excluded. Getting
that wrong would unmount unrelated filesystems.

The image root is unmounted **never lazily**. A lazy unmount would succeed
while the image was still in use, hiding the problem until the next session
mounted it again and corrupted it. When it fails, the error says to reboot, and
names the process holding it.

## Configuration files written into the image

`etc/resolv.conf`, `etc/hosts` and `etc/ld.so.preload` are written from the
android side, pointing into the mounted image.

Each is **unlinked before being written**, not truncated in place.

> [!NOTE]
> A debian image can ship `/etc/resolv.conf` as an absolute symlink, which
> systemd-resolved does. Truncating through it wrote to the host's real `/etc`
> instead of into the image. Unlinking removes the symlink itself, never its
> target.

Each is then explicitly set to mode 644, because truncating an existing file
leaves its old mode in place.
