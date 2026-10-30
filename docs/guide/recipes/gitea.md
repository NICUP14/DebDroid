[← Recipes](README.md)

# Git Server on a Phone

Host your own repositories, with a web interface.

## What you'll end up with

Gitea running on the phone: push and pull over SSH or HTTP, with issues, pull
requests and a web UI.

## What it needs

- [Background mode](../keep-it-running.md) set up.
- About 2 GB of space, plus room for your repositories.
- Swap, if the device has under 2 GB of RAM. See
  [keep it running](../keep-it-running.md#give-it-more-memory).

## Steps

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach
```

Inside:

```bash
apt update
apt install -y git sqlite3 wget

# Gitea ships a single binary; pick the build matching your architecture
ARCH=$(dpkg --print-architecture)
wget -O /usr/local/bin/gitea \
    "https://dl.gitea.com/gitea/1.22.6/gitea-1.22.6-linux-$ARCH"
chmod 755 /usr/local/bin/gitea

# A user to run it as, rather than root
adduser --system --group --disabled-password --home /var/lib/gitea git
mkdir -p /var/lib/gitea/{custom,data,log} /etc/gitea
chown -R git:git /var/lib/gitea
chown root:git /etc/gitea
chmod 770 /etc/gitea
```

Start it:

```bash
su - git -c 'GITEA_WORK_DIR=/var/lib/gitea /usr/local/bin/gitea web \
    --config /etc/gitea/app.ini' &
```

Open `http://<phone-address>:3000` and complete the setup. Choose **SQLite**
as the database — it is the right choice on a phone, and avoids running a
separate database server.

Afterwards, tighten the config directory:

```bash
chmod 750 /etc/gitea
chmod 640 /etc/gitea/app.ini
```

## How to tell it worked

From your laptop:

```bash
git clone http://<phone-address>:3000/youruser/yourrepo.git
cd yourrepo
echo test > test.txt && git add -A && git commit -m "Test" && git push
```

The commit should appear in the web interface.

## Start it with the environment

Put the start command in `/sdcard/debdroid/command/gitea.sh` and run it after
`start`, or add it to the boot hook.

## If it didn't work

- **Killed during setup** — out of memory. Set up swap and try again.
- **Port 3000 refused** — check it is running, and that the phone is awake.
- **Slow web interface** — normal on older hardware. The git operations
  themselves are usually fine; it is the UI that feels slow.
- **Out of space** — repositories grow. [Resize the install](../storage.md), or
  move `/var/lib/gitea` onto `/sdcard`.

> [!NOTE]
> Keep this on your local network. If you need it remotely, reach it through
> [WireGuard](wireguard.md) rather than forwarding a port.
