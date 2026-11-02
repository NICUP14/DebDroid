[← Docs](../README.md)

# Run a Desktop

A real Linux desktop on the phone, which you use from another device over VNC.
Firefox, an office suite, an editor. See the [gallery](../gallery.md).

## What you'll end up with

XFCE4 running on the phone, which you connect to with a VNC app from a laptop,
tablet or another phone.

## Before you start

Three things, in order. Skipping the first two is the usual reason this goes
badly.

**1. Read [keep it running](keep-it-running.md).** A desktop that dies when you
close the terminal is pointless.

**2. Set up swap.** A desktop needs more memory than a phone has. Without
swap, Android may kill things or reboot the device:

```bash
su
sh /sdcard/debdroid/debdroid.sh swap create 2G
```

Then set `CONFIG_ENV_SWAP=yes` in `/sdcard/debdroid/debdroid_mgr.sh`.

**3. Make sure you have space.** A desktop with a browser wants 6 GB or more:

```bash
su
sh /sdcard/debdroid/debdroid.sh resize 8G
```

## Set it up

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach sh /sdcard/debdroid/command/vncserver.sh
```

The first run installs the desktop, which takes a while and downloads a lot.
It asks you to set a VNC password. Eight characters is the maximum, which is a
VNC limitation rather than ours.

When it finishes it prints where to connect:

```txt
 -> Connect a vnc client to 192.168.1.42:5901
```

## Connect

Install a VNC client (RealVNC, TigerVNC and Remmina all work), then connect to
the address it printed using the password you set.

## Change the resolution

Match your screen for the best result:

```bash
su
DEBDROID_VNC_GEOMETRY=1920x1080 sh /sdcard/debdroid/debdroid.sh \
    attach sh /sdcard/debdroid/command/vncserver.sh
```

## If it's slow

Normal for a phone. These help:

- A lower resolution.
- A lower colour depth: `DEBDROID_VNC_DEPTH=16`.
- Turn off XFCE4's compositing: Settings → Window Manager Tweaks →
  Compositor.
- Use a wired network via a USB adapter, if the device supports one.

## About the power manager

DebDroid turns off the XFCE4 power manager automatically. It expects a laptop
battery, and on a phone it can use enough memory to reboot the device. This was
one of the most common complaints about running a desktop, and the helper now
handles it so you do not have to.

## If it didn't work

- **The phone rebooted**: out of memory. Set up swap, above.
- **Black screen on connect**: the session failed to start. Look in
  `~/.vnc/*.log` inside a session.
- **Connection refused**: check it is running with `debdroid.sh status`, and
  that you used port 5901 (display `:1`).
- **It stopped when you closed the terminal**: you used `run` instead of
  `start`.

---

Next: [something went wrong](problems.md) if you need it.
