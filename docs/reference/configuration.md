[← Docs](../README.md)

# Configuration

Two files, with different purposes. Both are preserved across updates, and the
new versions land beside them as `.new` so a changed default can be compared.

| File | Holds |
| --- | --- |
| `/sdcard/debdroid/debdroid_mgr.sh` | The `CONFIG_*` flags, at the top of the file |
| `/sdcard/debdroid/debdroid_env.sh` | Paths and variables the scripts share |
| `/sdcard/debdroid/environment` | Variables exported inside the debian session |

## Manager flags

Edit the block at the top of `debdroid_mgr.sh`. Values are `yes`, `no` or a
number, and they take effect on the next session start.

### Device files

| Flag | Default | Effect |
| --- | --- | --- |
| `CONFIG_DEV_OVERLAY` | `yes` | Try an overlay for `/dev`, giving the session a writable layer over android's |
| `CONFIG_DEV_TMPFS` | `yes` | Fall back to a private tmpfs for `/dev` before resorting to a bind mount |
| `CONFIG_DEV_FD` | `yes` | Create `/dev/fd` |
| `CONFIG_DEV_IO` | `yes` | Create `/dev/stdin`, `/dev/stdout`, `/dev/stderr` |
| `CONFIG_DEV_LOOP` | `yes` | Link the loop devices, which android keeps under `/dev/block` |

Turning both `CONFIG_DEV_OVERLAY` and `CONFIG_DEV_TMPFS` off forces the bind
mount, which shares android's real `/dev` with the session. See
[mounts](mounts.md#dev).

### Bundled programs

| Flag | Default | Effect |
| --- | --- | --- |
| `CONFIG_DEBDROID_BIN` | `yes` | Mount the bundled binaries at `/debdroid/bin`. Required: it holds the bootstrap |
| `CONFIG_DEBDROID_LIB` | `yes` | Mount and preload the compatibility libraries |

Turning `CONFIG_DEBDROID_LIB` off disables the randomness fixes, which breaks
`gpg`, `sshd` and anything else needing `getrandom`. See [patches](patches.md).

### Environment

| Flag | Default | Effect |
| --- | --- | --- |
| `CONFIG_ENV_FSCK` | `yes` | Check and repair the image before mounting it |
| `CONFIG_ENV_APEX` | `yes` | Bind-mount `/apex`, needed by android's own tools on android 10+ |
| `CONFIG_ENV_SHMMAX` | `268435456` | Shared memory limit in bytes, raised for desktop software |
| `CONFIG_ENV_DNSSERVER` | `1.1.1.1` | Fallback nameserver when android reports none |

Turning off `CONFIG_ENV_FSCK` makes startup faster and is a bad idea: it is
what catches a damaged image before it is mounted and made worse.

### Power and memory

| Flag | Default | Effect |
| --- | --- | --- |
| `CONFIG_ENV_WAKELOCK` | `yes` | Hold a wakelock, so the device keeps serving with the screen off |
| `CONFIG_ENV_SWAP` | `no` | Enable the swapfile created by `debdroid.sh swap` |
| `CONFIG_ENV_SWAPPINESS` | `60` | How eagerly the kernel swaps, 0 to 100 |
| `CONFIG_ENV_SWAP_PRIO` | *(empty)* | Swap priority; empty places it below any existing zram |

`CONFIG_ENV_SWAP` is off by default because the swapfile takes gigabytes of
internal storage, and because it must be created first. The session only
enables an existing file, never creates one, since writing it takes minutes.

The empty priority default matters: most devices already run zram, which is far
faster than a file on internal storage, so the file is placed below it rather
than competing with it.

## Paths

`debdroid_env.sh` defines where things live. Changing these is supported but
the three lock-related paths must stay consistent with what `debdroid_mgr.sh`
derives from the mount point.

| Variable | Default |
| --- | --- |
| `DEBDROID_SDHOME` | `/sdcard/debdroid` |
| `DEBDROID_IMG` | `$DEBDROID_SDHOME/img/debian.img` |
| `DEBDROID_CMD` | `$DEBDROID_SDHOME/command` |
| `DEBDROID_HOME` | `/data/local/debdroid` |
| `DEBDROID_BIN` | `$DEBDROID_HOME/bin` |
| `DEBDROID_LIB` | `$DEBDROID_HOME/lib` |
| `DEBDROID_ENV` | `$DEBDROID_HOME/mnt` |
| `DEBDROID_LOCK` | `$DEBDROID_ENV.lock` |
| `DEBDROID_PID` | `$DEBDROID_ENV.pid` |
| `DEBDROID_LOG` | `$DEBDROID_ENV.log` |
| `DEBDROID_SWAP` | `$DEBDROID_HOME/swapfile` |
| `DEBDROID_WAKELOCK` | `debdroid` |
| `BUSYBOX` | `$DEBDROID_HOME/bin/busybox` |

The swapfile lives under `/data` deliberately: `/sdcard` is a FUSE filesystem
and cannot host swap.

## Inside the session

The session starts a clean login shell that inherits nothing from android, so
any variable you want inside debian goes in `/sdcard/debdroid/environment`,
which the bootstrap sources:

```sh
export TMPDIR=/tmp
export PATH=/usr/bin:/usr/sbin:/bin:/usr/local/bin:/usr/local/sbin:/system/bin:/debdroid/bin:"$PATH"
export EDITOR=vim
```

`/system/bin` is on the path so android's own tools are reachable from debian.
On android 10 and later that also needs `CONFIG_ENV_APEX`.
