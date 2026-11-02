[← Recipes](README.md)

# Syncthing on a Phone

Keep files in sync between your devices, with no cloud service involved.

## What you'll end up with

An always-on Syncthing node. Your laptop, desktop and phone sync through it,
and because it is always on, they stay in sync even when they are never
switched on at the same time.

## What it needs

- [Background mode](../keep-it-running.md) set up.
- Enough space for what you are syncing, plus room to grow.

## Steps

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach
```

Inside:

```bash
apt update
apt install -y syncthing

# Create a user to run it, rather than running as root
adduser --disabled-password --gecos "" sync
su - sync -c 'syncthing --no-browser --home=~/.config/syncthing' &
```

It prints its device ID on first run. Note it down.

**Let other devices reach the web interface.** By default it only listens
locally. Edit `~/.config/syncthing/config.xml` as the `sync` user and change:

```xml
<address>127.0.0.1:8384</address>
```

to:

```xml
<address>0.0.0.0:8384</address>
```

Then restart it.

## Add it to your other devices

Open `http://<phone-address>:8384` from your laptop, and add your devices by
their IDs. Point the shared folder somewhere with space:

- `/sdcard/Sync` makes the files visible to Android apps too.
- `/home/sync/Sync` keeps them inside Debian.

## How to tell it worked

Create a file in the shared folder on your laptop. It should appear on the
phone within a few seconds. Check from a session with `ls`.

## Start it with the environment

Put it in `/sdcard/debdroid/command/syncthing.sh`:

```sh
#!/bin/sh
su - sync -c 'syncthing --no-browser --home=~/.config/syncthing' &
```

Then `debdroid.sh attach sh /sdcard/debdroid/command/syncthing.sh` after
starting, or add it to the boot hook.

## If it didn't work

- **Can't reach the web interface**: the listen address is still
  `127.0.0.1`. See above.
- **Devices don't find each other**: they need to be on the same network, or
  discovery needs to be enabled. Adding the phone's address manually in the
  other device's settings is the reliable fallback.
- **Sync stops when the screen goes off**: the device is sleeping. Check
  `debdroid.sh status` reports the wakelock as held.
- **Out of space**: [resize the install](../storage.md), or point the folder
  at `/sdcard`, which uses your phone's main storage.
