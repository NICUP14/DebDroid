[← Reference](README.md)

# Command Reference

```txt
debdroid.sh [OPTION] [ARGUMENTS]
```

Every command needs root. Unrecognised options are treated as `run`.

## Sessions

### `run [COMMAND...]`

Starts the environment, runs the command (or an interactive shell), then stops
the environment and unmounts everything.

```sh
debdroid.sh run apt update
debdroid.sh run              # interactive shell
```

Arguments are passed through unchanged: quoting, spaces, globs and `$`
survive, and are not expanded on the android side.

Every `run` performs a full startup, including the filesystem check. For
repeated use, `start` plus `attach` is much faster.

### `start`

Starts the environment in the background and returns. A supervisor holds the
namespace, the lock and the mounts open until stopped, so programs started
inside keep running after the terminal closes.

Startup output goes to `/data/local/debdroid/mnt.log`.

### `attach [COMMAND...]`

Opens a session in the background environment. Skips the filesystem check, the
mounts and the bootstrap, since the supervisor already did them.

```sh
debdroid.sh attach
debdroid.sh attach apt update
```

Leaving an attached session stops nothing.

### `stop`

Stops the background environment: signals every process in it, unmounts
everything, releases the lock, the wakelock and the swapfile.

### `status`

Reports whether an environment is running, its process and mount counts, and
whether the wakelock and swap are active.

## Commands and scripts

### `list`

Lists the command scripts in `/sdcard/debdroid/command`.

### `command NAME`

Runs a command script inside the environment.

| Script | Does |
| --- | --- |
| `update` | `apt update && apt upgrade` inside debian |
| `user` | Creates a non-root user with the right groups |
| `sshserver` | Installs and starts an ssh server |
| `vncserver` | Installs and starts an XFCE4 desktop over VNC |

Names may contain only letters, digits, `_` and `-`; anything else is rejected
before the filesystem is touched.

> [!NOTE]
> These run **inside** debian. Anything belonging to the device rather than to
> the userland is a top-level subcommand instead — `swap` is the example, and
> it refuses to run if it finds itself inside a session.

## Maintenance

### `resize SIZE`

Resizes the debian image. Refuses to run while the environment is in use.

**Size grammar:** an integer followed by `K`, `M` or `G`, where each is a power
of 1024.

| Accepted | Rejected | Why |
| --- | --- | --- |
| `5G` | `5` | The unit is required: a bare number means *blocks* to `resize2fs` |
| `512M` | `5g` | Uppercase only |
| `10K` | `1.5G` | Integers only |
| `05G` | `5GB` | `G`, not `GB` |
| | `5 G` | No spaces |

Growing keeps the data. Shrinking keeps the data but fails if it does not fit.

### `swap <create [SIZE] | remove | status>`

Manages the swapfile sessions use when `CONFIG_ENV_SWAP=yes`.

| Option | Does |
| --- | --- |
| `create [SIZE]` | Writes and formats the swapfile, default 1G |
| `remove` | Disables and deletes it |
| `status` | Reports the swapfile, the swappiness and the active swap areas |

Runs on the android side, not inside debian, and refuses to run from within a
session. Creating the file takes minutes, so a session only ever *enables* an
existing one.

Checks free space first, and refuses on an f2fs `/data`, where `swapon` on a
regular file fails on the kernels DebDroid targets.

### `self-update [--check] [--rollback] [--offline FILE]`

Updates DebDroid itself. To update debian, use `command update` instead.

| Option | Does |
| --- | --- |
| `--check` | Reports the installed version and where releases are published |
| `--offline FILE` | Installs a release bundle already on the device |
| `--rollback` | Restores the version saved before the last update |

Refuses to run while a session holds the lock. Verifies checksums before
unpacking, backs up the current version first, and keeps `environment` and
`debdroid_env.sh`.

### `uninstall [--image] [--all] [--yes]`

Removes DebDroid.

| Option | Does |
| --- | --- |
| *(none)* | Removes the installation, keeps the image and the configuration |
| `--image` | Also deletes the debian image |
| `--all` | Also deletes the image and the configuration |
| `--yes` | Skips the confirmation prompt |

Refuses while a session is running, and refuses when the image is still
mounted, since removing the mount point then would delete the contents of the
debian installation.

### `help`

Prints the usage summary.

## Environment variables

| Variable | Effect |
| --- | --- |
| `BUSYBOX` | Path to the busybox binary, overriding the search |
| `NO_COLOR` | Any value disables colored output |
| `DEBDROIDMGR_MODE` | Mode the manager runs in: `run`, `start`, `attach`, `stop`, `status` |
| `DEBDROID_NONINTERACTIVE` | Set by the module installer, skips the installer's size prompt |
| `DEBDROID_USER` | Username for `command user`, skipping its prompt |
| `DEBDROID_SSH_PORT` | Port for `command sshserver` (default 8022) |
| `DEBDROID_VNC_DISPLAY` | VNC display number (default 1, so port 5901) |
| `DEBDROID_VNC_GEOMETRY` | VNC resolution (default 1280x720) |
| `DEBDROID_VNC_DEPTH` | VNC colour depth (default 24) |

## The manager directly

```txt
debdroid_mgr.sh <img> <mount-point> <bin-dir> <lib-dir> [command...]
```

The frontend calls this; it is documented because a bug report may involve it.
It reads its mode from `DEBDROIDMGR_MODE`, which keeps its positional arguments
compatible with earlier releases. Note that it sources no environment file, so
`BUSYBOX` must be set or findable.

## Test suite

```sh
sh /sdcard/debdroid/tests/device.sh [--session]
```

See [testing](testing.md).
