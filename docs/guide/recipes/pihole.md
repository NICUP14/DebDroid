[← Recipes](README.md)

# Pi-hole on a Phone

Network-wide ad blocking, on hardware you already own.

## What you'll end up with

A DNS server on the phone that blocks ads and trackers for every device on your
network, including ones that cannot run an ad blocker themselves.

## What it needs

- [Background mode](../keep-it-running.md) set up.
- A fixed address for the phone, reserved in your router.
- About 1 GB of space.

## Steps

**1. Give the phone a fixed address.** In your router's DHCP settings, reserve
one for the phone. Pi-hole is no use if its address changes.

**2. Start the environment and install Pi-hole:**

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach
```

Then inside:

```bash
apt update
apt install -y curl
curl -sSL https://install.pi-hole.net | bash
```

The installer asks several questions. Accept the defaults, and when it asks
which interface to use, pick the wireless one.

> [!NOTE]
> The installer will try to use systemd, which does not run here. If it
> complains about `systemctl`, let it finish — the next step starts the
> services directly.

**3. Start the services:**

```bash
service pihole-FTL start
service lighttpd start
```

**4. Point your router at it.** In your router's DHCP settings, set the DNS
server to the phone's address. Every device then uses Pi-hole automatically.

## How to tell it worked

From another device on the network:

```bash
nslookup doubleclick.net 192.168.1.42
```

It should return `0.0.0.0`, meaning the domain is blocked. The web interface at
`http://192.168.1.42/admin` shows queries arriving as devices use it.

## Keep it running after a reboot

Add it to the [start-at-boot hook](../keep-it-running.md#start-it-automatically-after-a-reboot),
then have the services start with the environment by putting them in a command
script at `/sdcard/debdroid/command/pihole.sh`:

```sh
#!/bin/sh
service pihole-FTL start
service lighttpd start
```

## If it didn't work

- **Queries don't arrive** — the phone is asleep. Check
  `debdroid.sh status` shows `Wakelock: held`.
- **Works, then stops overnight** — same cause. Keep the device plugged in.
- **The installer failed on systemd** — expected; start the services with
  `service` as above.
- **Nothing resolves anywhere on the network** — set your router's DNS back to
  automatic, then debug with the phone's address set manually on one device.

> [!WARNING]
> Your whole network's DNS now depends on this phone. If it goes down,
> everything loses name resolution. Set a second DNS server in your router as a
> fallback.
