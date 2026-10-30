[← Guides](README.md)

# Manage Storage

The Debian install is one big file on your phone's storage, with a fixed size.
If you run out of room inside Debian, make that file bigger.

## Check what you have

Inside a session:

```bash
df -h /
```

## Make it bigger

Exit Debian first — the resizer refuses to run while it is in use.

```bash
su
sh /sdcard/debdroid/debdroid.sh resize 5G
```

That makes the Debian install 5 GB. It keeps everything already in it.

Make sure your phone has that much free space first:

```bash
df -h /sdcard
```

## Sizes

A whole number followed by `K`, `M` or `G`:

| Works | Does not work | Why |
| --- | --- | --- |
| `5G` | `5` | The unit is required |
| `512M` | `5g` | Capital letters only |
| `2048M` | `1.5G` | Whole numbers only |
| | `5GB` | `G`, not `GB` |

## Making it smaller

This works, and keeps your files, but only if they fit in the new size:

```bash
su
sh /sdcard/debdroid/debdroid.sh resize 2G
```

If the data does not fit, it stops and changes nothing.

> [!WARNING]
> Shrinking is riskier than growing. Only do it if you need the space back, and
> know what is in there.

## Freeing space inside Debian

Often easier than resizing. Inside a session:

```bash
apt clean          # Delete downloaded package files
apt autoremove     # Delete packages nothing needs any more
du -sh /var/* | sort -h | tail   # Find what is actually large
```

## If it goes wrong

- **"Refusing to resize the debian image while the environment is running"** —
  exit every session first, including background mode (`debdroid.sh stop`).
- **"Invalid size"** — check the table above.
- **It ran out of space partway** — the Debian install is unchanged. Free up
  phone storage and try again.

---

Next: [keep it running](keep-it-running.md).
