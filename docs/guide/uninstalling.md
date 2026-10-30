[← Guides](README.md)

# Uninstall

## Remove DebDroid, keep your Debian install

```bash
su
sh /sdcard/debdroid/debdroid.sh uninstall
```

It lists everything it will remove and keep, then asks you to type `yes`.

**Your Debian install is kept by default.** It holds your files, so deleting it
is not something to do by accident. If you reinstall DebDroid later, it picks
the existing install back up.

## Remove everything

```bash
su
sh /sdcard/debdroid/debdroid.sh uninstall --all
```

This deletes the Debian install and everything in it, plus your settings. It
cannot be undone.

To delete the Debian install but keep your settings, use `--image` instead.

## What gets removed

| Removed | Kept unless you ask |
| --- | --- |
| Programs in `/data/local/debdroid` | The Debian install (`--image`) |
| Scripts in `/sdcard/debdroid` | `environment` and `debdroid_env.sh` (`--all`) |
| The swapfile | |
| The start-at-boot hook | |

It prints the full path of everything it touched, so nothing is removed
silently.

## If it refuses to run

That is deliberate, and worth understanding:

- **"A session is using the debian image"** — exit it, or run
  `debdroid.sh stop`.
- **"The debian image is still mounted"** — reboot, then uninstall. It will not
  delete the mount folder while Debian is mounted there, because that would
  delete the contents of your Debian install instead of an empty folder.

## Removing the Magisk module

If you installed DebDroid as a module, remove it in the Magisk app and reboot.
That leaves your Debian install in place; delete
`/sdcard/debdroid/img/debian.img` by hand if you want the space back.

## Doing it by hand

If the uninstaller will not run:

```bash
su
rm -rf /data/local/debdroid
rm -rf /sdcard/debdroid
rm -f /data/adb/service.d/debdroid.sh
```

Make sure nothing is running first, and check `mount | grep debdroid` is empty.
