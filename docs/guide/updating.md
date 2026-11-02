[← Docs](../README.md)

# Update DebDroid

Two different things can be updated, and they are easy to confuse:

| You want to update | Command |
| --- | --- |
| Debian and its packages | `debdroid.sh command update` |
| DebDroid itself | `debdroid.sh self-update` |

## Update Debian

The usual `apt` update, run inside Debian:

```bash
su
sh /sdcard/debdroid/debdroid.sh command update
```

Safe to do often.

## Update DebDroid

```bash
su
sh /sdcard/debdroid/debdroid.sh self-update --check
```

That reports your version and where releases are published. To install a
release you have downloaded:

```bash
su
sh /sdcard/debdroid/debdroid.sh self-update --offline /sdcard/Download/DebDroid-1.2.7.tar.gz
```

Your Debian install and your settings are kept. The previous version is saved
first, so you can undo it:

```bash
su
sh /sdcard/debdroid/debdroid.sh self-update --rollback
```

Exit every session first. It refuses to replace files while Debian is running,
because doing so can damage the install.

## Updating by re-running the installer

Still works, and is fine:

```bash
su
cd /sdcard/Download/DebDroid-main
sh install.sh
```

It keeps your Debian install, and keeps `environment` and `debdroid_env.sh` if
you have edited them. The new versions are saved alongside as `.new` so you can
compare.

## Why downloads are checked

DebDroid runs as root, so anything it installs runs as root too. Every download
is checked against a known fingerprint before it is unpacked or run, and a
mismatch stops the update with nothing changed.

If you want to understand exactly what is and is not guaranteed, see
[integrity](../reference/integrity.md). It is honest about the limits.

## After updating

```bash
su
sh /sdcard/debdroid/tests/device.sh
```

---

Next: [uninstall](uninstalling.md), if that is what you came for.
