[← Guides](README.md)

# Connect From Your Computer

Typing on a phone is unpleasant. Connect from a real keyboard instead.

## What you'll end up with

An SSH server on the phone that you reach from your laptop over Wi-Fi, and
which keeps running when you put the phone down.

## Before you start

Read [keep it running](keep-it-running.md) first. An SSH server that stops when
you close the terminal is no use.

You also want a normal user to log in as:

```bash
su
sh /sdcard/debdroid/debdroid.sh command user
```

## Set it up

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach sh /sdcard/debdroid/command/sshserver.sh
```

It installs the server if needed, generates its keys, starts it, and prints the
exact command to connect with — something like:

```txt
 -> ssh -p 8022 <user>@192.168.1.42
```

Port 8022 rather than the usual 22, to avoid clashing with anything Android
runs.

## Connect

From your laptop, on the same Wi-Fi:

```bash
ssh -p 8022 yourname@192.168.1.42
```

The first connection asks you to confirm the server's fingerprint. Say yes.

## Keys instead of a password

Better, and avoids typing a password every time. From your laptop:

```bash
ssh-copy-id -p 8022 yourname@192.168.1.42
```

## Phone keeps changing address

Phones get a new address from the router periodically. Either reserve a fixed
one for it in your router settings, or use its name if your router supports
that.

## How to tell it worked

Connect, close the terminal app on the phone completely, and connect again. If
the second connection works, it is set up properly.

## If it didn't work

- **Connection refused** — the server is not running. Check with
  `debdroid.sh status`, and that you used the right port.
- **It worked, then stopped after a few minutes** — the phone is falling
  asleep. See [my connection drops](problems.md#my-connection-drops-after-a-few-minutes).
- **It stops when you close the terminal** — you used `run` instead of
  `start`. See [keep it running](keep-it-running.md).
- **Permission denied** — the user needs the network group. See
  [ping or apt says permission denied](problems.md#ping-or-apt-says-permission-denied).

## Don't put this on the internet

This works on your home network. Forwarding a port to it from the internet is a
bad idea: these devices no longer get security updates. If you need access from
outside, use a VPN such as WireGuard or Tailscale. See
[security](../reference/security.md).

---

Next: [run a desktop](desktop.md).
