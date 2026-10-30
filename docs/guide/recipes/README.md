[← Guides](../README.md)

# Recipes

End-to-end projects, each a concrete reason to keep an old phone running.

All of them assume you have read [keep it running](../keep-it-running.md), since
every one of these is a server and would otherwise stop when you close the
terminal.

## Network

- **[Pi-hole](pihole.md)** — block ads for every device on your network.
- **[Syncthing](syncthing.md)** — sync files between your devices, no cloud.
- **[WireGuard](wireguard.md)** — reach your home network from anywhere.

## Development

- **[Git server](gitea.md)** — host your own repositories.
- **[CI runner](ci-runner.md)** — run builds on hardware you already own.

## Each recipe follows the same shape

What you'll end up with, what it needs, the steps, how to tell it worked, and
what to do if it didn't.

## A note on what these devices are good for

A phone from 2015 has roughly the compute of a Raspberry Pi 3, with a battery
that doubles as a UPS and Wi-Fi built in. That is genuinely enough for all of
the above.

What it is not good for is anything that needs to be secure against an
attacker, because the kernel no longer gets patches. Keep these services on
your home network, behind a VPN if you need them remotely, and read
[security](../../reference/security.md) before deciding what to put on one.
