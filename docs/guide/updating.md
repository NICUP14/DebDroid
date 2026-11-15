[← Docs](../README.md)

# Update DebDroid

Two different things can be updated, and they are easy to confuse:

| You want to update | Command |
| --- | --- |
| Debian and its packages | `debdroid.sh command update` |
| DebDroid itself | `debdroid.sh self-update` |

**In the app:** Manage → **Update packages** updates Debian. To update
DebDroid itself, install the newer `DebDroid.apk` over the old one, open it and
press **Update**. Your Debian install and your settings are kept.

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
newer version, install the newer `DebDroid.apk` and open it, or flash the newer
`DebDroid-module.zip` in your root app.

Your Debian install and your settings are kept.

When `self-update` installs a release, it saves the previous version first,
so you can undo it. Installing the app or the module saves no copy to go back
to.

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

It keeps your Debian install. It keeps `environment` and `debdroid_env.sh` if
you changed a setting in them, and saves the new versions beside them as `.new`
so you can compare. If you never changed one, it is replaced with the new
version. Your settings in `debdroid.conf` are kept too, and nothing is written
beside them, because the update does not ship a copy of that file.

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
