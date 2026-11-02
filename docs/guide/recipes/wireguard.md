[← Recipes](README.md)

# WireGuard on a Phone

Reach your home network from anywhere, without exposing anything to the
internet directly.

## What you'll end up with

A VPN endpoint on the phone. You connect to it from outside, and from there
reach everything on your home network, including the other recipes, safely.

## Why this one matters

The devices DebDroid runs on no longer get security patches. Forwarding a port
to one is a genuinely bad idea.

A VPN is the exception worth making, because WireGuard is small, its attack
surface is tiny, and it drops anything that is not a valid authenticated packet
without responding at all. To a port scanner it looks like nothing is there.

That makes this the right way to get at your other services remotely: expose
only WireGuard, and reach everything else through it.

## What it needs

- [Background mode](../keep-it-running.md) set up.
- The ability to forward one UDP port on your router.
- A way to find your home address from outside: a dynamic DNS name, or a static
  address.

## Steps

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach
```

Inside:

```bash
apt update
apt install -y wireguard-tools

# Keys for the server
wg genkey | tee /etc/wireguard/server.key | wg pubkey > /etc/wireguard/server.pub
chmod 600 /etc/wireguard/server.key

# Keys for a client
wg genkey | tee /etc/wireguard/client.key | wg pubkey > /etc/wireguard/client.pub
```

Write `/etc/wireguard/wg0.conf`:

```ini
[Interface]
Address = 10.9.0.1/24
ListenPort = 51820
PrivateKey = <contents of server.key>

[Peer]
PublicKey = <contents of client.pub>
AllowedIPs = 10.9.0.2/32
```

Bring it up:

```bash
wg-quick up wg0
```

**Forward the port.** In your router, forward UDP 51820 to the phone's address.
That one port is all you expose.

## Configure your client

On the device you will connect from:

```ini
[Interface]
Address = 10.9.0.2/24
PrivateKey = <contents of client.key>

[Peer]
PublicKey = <contents of server.pub>
Endpoint = yourname.example.com:51820
AllowedIPs = 10.9.0.0/24, 192.168.1.0/24
PersistentKeepalive = 25
```

Adjust `192.168.1.0/24` to your actual home network.

## How to tell it worked

From outside your home network, connect, then:

```bash
ping 10.9.0.1
```

If that answers, the tunnel is up. Then try reaching another device on your
home network by its local address.

## If it didn't work

- **`wg-quick` fails on `sysctl`**: the kernel setting it wants may be
  unavailable. Set `Table = off` in the `[Interface]` section and add the
  routes by hand.
- **No module**: WireGuard needs kernel support. If your device's kernel
  lacks it, `wireguard-go` is a userspace implementation that works instead.
- **Connects but cannot reach anything**: check `AllowedIPs` on the client
  covers your home network, and that the phone can reach those addresses
  itself.
- **Works at home, not from outside**: the port forward is wrong, or your
  home address changed. Verify with a port checker.

> [!IMPORTANT]
> Keep the phone's SSH server on the local network only, and reach it through
> the VPN. Forwarding SSH as well defeats the point of this setup.
