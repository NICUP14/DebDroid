# The probe

`debdroid.sh probe` reports DebDroid's state as `key=value` lines, for
programs. `debdroid.sh status` reports much the same facts for people, and its
wording is governed by [output conventions](output.md), which means it is free
to change. A program that scraped it would break the first time a sentence
improved. The probe is the contract instead.

```sh
su -c 'sh /sdcard/debdroid/debdroid.sh probe'
```

```txt
probe=1
version=1.2.6
installed_version=1.2.6
busybox=ok
root=yes
abi=arm64-v8a
arch=arm64
arch_built=yes
sdk=24
scripts_present=yes
binaries_present=yes
binaries_ok=yes
image_present=yes
image_size=2147483648
image_ok=yes
image_partial=no
running=yes
session=background
pid=4182
mounts=14
processes=6
wakelock=held
swap=off
started=1731672000
services=ssh
mount_ns=yes
swapfile=no
module_installed=yes
root_manager=magisk
boot_hook=module
log=/data/local/debdroid/mnt.log
```

## The contract

- One `key=value` per line. The value is everything after the first `=`.
- A key is never renamed and never given a new meaning. New keys may be added
  in any release, so a reader must ignore keys it does not know.
- A value never contains a newline.
- A fact that cannot be determined is printed with an empty value rather than
  left out, so a reader can tell "unknown" from "an older probe".
- It always exits 0. A refusal would tell the caller less than the lines it
  would have printed.
- It is read-only. It mounts, starts, creates and repairs nothing, so it is
  safe to run as often as a screen wants to refresh.
- `probe` is the format version, and changes only if this contract does.

## Running it from a copy that is not installed

The probe also runs straight out of an unpacked release, which is how the app
decides what to install on a device that has nothing on it yet:

```sh
su -c 'sh ./debdroid/debdroid_probe.sh'
```

It uses the helpers beside itself, prefers the installed
`/sdcard/debdroid/debdroid_env.sh` when there is one (that file holds the
user's paths), and falls back to the bundled `bin/busybox`, restoring its
execute bit first as the installer does. With no busybox anywhere it prints
`busybox=missing` and `root`, then stops, because every other fact needs it.

`version` is always the version of the copy that ran. `installed_version` is
the version of the scripts installed on the device, and is empty when there
are none. Comparing the two is how an installer knows whether it is about to
update, reinstall or downgrade.

## Keys

| Key | Values | Meaning |
| --- | --- | --- |
| `probe` | `1` | Format version |
| `version` | `1.2.6` | The probing copy's own version |
| `installed_version` | version or empty | The installed scripts' version |
| `busybox` | `ok`, `missing` | Whether any usable busybox was found |
| `root` | `yes`, `no` | Whether the probe ran as root |
| `abi` | `arm64-v8a`... | The android abi, empty if unreadable |
| `arch` | `arm64`, `armhf`... | The debian architecture, empty if unrecognised |
| `arch_built` | `yes`, `no` | Whether binaries for `arch` actually ship |
| `sdk` | `24`... | The android api level |
| `scripts_present` | `yes`, `no` | `/sdcard/debdroid/debdroid.sh` exists |
| `binaries_present` | `yes`, `no` | busybox, e2fsck and resize2fs are installed |
| `binaries_ok` | `yes`, `no` | ...and all three are executable |
| `image_present` | `yes`, `no` | The debian image exists |
| `image_size` | bytes or empty | Its size |
| `image_ok` | `yes`, `no` | It carries an ext2 superblock |
| `image_partial` | `yes`, `no` | An interrupted download was left beside it |
| `running` | `yes`, `no` | A background environment is running |
| `session` | `background`, `foreground`, `none` | What holds the image |
| `pid` | pid or empty | The background supervisor |
| `mounts` | count or empty | Filesystems mounted in the environment |
| `processes` | count or empty | Processes in the environment |
| `wakelock` | `held`, `not_held`, `unavailable` | DebDroid's wakelock |
| `swap` | `on`, `off` | Whether the swapfile is active |
| `started` | epoch seconds or empty | When the background environment started |
| `services` | `ssh`, `vnc`, comma-separated, or empty | Shipped servers running in the environment |
| `mount_ns` | `yes`, `no` | The kernel offers a mount namespace |
| `swapfile` | `yes`, `no` | Whether a swapfile exists |
| `module_installed` | `yes`, `no` | The DebDroid root module, unless marked for removal |
| `root_manager` | `magisk`, `kernelsu`, `apatch`, `unknown`, `none` | What provides root |
| `boot_hook` | `module`, `service_d`, `none` | What starts the environment at boot, counting a module update that is staged for the next reboot |
| `log` | path | The background startup log |

`binaries_present=yes` with `binaries_ok=no` is the specific failure a flash
produces when the payload arrives at 0644: the files are there and nothing can
run them. It is repaired by running the installer again, not by downloading
anything.

`image_ok` checks the ext2 magic, not the checksum. Hashing a gigabyte takes
minutes on these devices, so the checksum belongs to the download, where it is
the trust anchor (see [integrity](integrity.md)), and to an explicit verify.

`session` and `running` read the same state `status` does, through one shared
function, so the two cannot disagree.
