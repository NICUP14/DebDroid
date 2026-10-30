[← Guides](README.md)

# Something Went Wrong

Find the message you are seeing, or the thing that is happening, and follow the
fix. If nothing here matches, run the checker — it inspects your whole setup
and reports anything that looks wrong:

```bash
su
sh /sdcard/debdroid/tests/device.sh
```

Include its output if you report a problem.

## Look up what you're seeing

| What you see | What it means |
| --- | --- |
| `The environment is already in use` | [Something else is using Debian](#the-environment-is-already-in-use) |
| `A background environment is already running` | [It's already started](#a-background-environment-is-already-running) |
| `No background environment is running` | [Nothing is started yet](#no-background-environment-is-running) |
| `Failed to unmount the debian image, it is still in use` | [A program wouldn't stop](#failed-to-unmount-the-debian-image) |
| `Failed to repair the debian image` | [The install is damaged](#failed-to-repair-the-debian-image) |
| `Missing required super-user permissions` | [You're not root](#missing-required-super-user-permissions) |
| `Unsupported architecture` | [Wrong kind of device](#unsupported-architecture) |
| `No binaries are built for this architecture yet` | [Device recognised, not yet supported](#no-binaries-are-built-for-this-architecture-yet) |
| `Cannot locate the busybox binary` | [The install is incomplete](#cannot-locate-the-busybox-binary) |
| `Overlay is unavailable, using a private tmpfs for /dev` | Not a problem, see [messages that look worse than they are](#messages-that-look-worse-than-they-are) |
| `Falling back to a bind-mount for /dev` | [Harmless, but worth knowing](#messages-that-look-worse-than-they-are) |
| `Wakelocks are unavailable` | [The device will sleep](#messages-that-look-worse-than-they-are) |
| My SSH or VNC stops when I close the terminal | [Use background mode](#my-server-stops-when-i-close-the-terminal) |
| My connection drops after a few minutes | [The device is falling asleep](#my-connection-drops-after-a-few-minutes) |
| The phone rebooted while I was using the desktop | [It ran out of memory](#the-phone-rebooted-while-i-was-using-the-desktop) |
| `ping` or `apt` says permission denied | [The user needs the network group](#ping-or-apt-says-permission-denied) |

---

## The environment is already in use

Only one session can use Debian at a time, because two would damage the
install.

Exit the other session. Check for ones you forgot: another terminal tab, a
window in the background, or a session opened over ADB.

If you are sure nothing is open, a program from an earlier session may still be
running:

```bash
su
sh /sdcard/debdroid/debdroid.sh status
```

If it reports a background environment, stop it with `debdroid.sh stop`. If it
reports nothing and the problem persists, reboot.

## A background environment is already running

Debian is already up. Open a session with `debdroid.sh attach`, or stop it
first with `debdroid.sh stop`.

## No background environment is running

You used `attach` or `stop` before `start`. Run:

```bash
su
sh /sdcard/debdroid/debdroid.sh start
```

## Failed to unmount the debian image

A program inside Debian would not stop, so the install is still mounted.

**Reboot the device before using DebDroid again.** Starting it again while this
is the case can damage the Debian install.

DebDroid names the program that held it open, which is worth noting if it keeps
happening — it usually means a service needs stopping before you exit.

## Failed to repair the debian image

The Debian install has errors that could not be fixed automatically. Make sure
nothing is running, then repair it by hand and answer the questions it asks
(`y` is almost always right):

```bash
su
/data/local/debdroid/bin/e2fsck -f /sdcard/debdroid/img/debian.img
```

This usually follows an unclean shutdown, such as the battery running out while
a session was open.

## Missing required super-user permissions

Run `su` first, and grant the permission when your root app asks:

```bash
su
sh /sdcard/debdroid/debdroid.sh
```

If `su` is not found, the device is not rooted, and DebDroid cannot run on it.

## Unsupported architecture

DebDroid runs on 64-bit ARM, 32-bit ARM and Intel devices. The message shows
what your device reports. If it shows something else, the device is not
supported.

## No binaries are built for this architecture yet

Your device is recognised but DebDroid does not ship the programs for it yet.
Only 64-bit ARM (`arm64-v8a`) is available today; 32-bit ARM and Intel are
planned. Nothing you can change on the device will help.

## Cannot locate the busybox binary

The install is incomplete or was partly removed. Re-run the installer from the
project folder:

```bash
su
cd /sdcard/Download/DebDroid-main
sh install.sh
```

Re-running is safe: it keeps your Debian install and your settings.

## My server stops when I close the terminal

That is how `run` works — it stops Debian when the session ends.

Use background mode instead, which is what servers need:

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach sh /sdcard/debdroid/command/sshserver.sh
```

See [keep it running](keep-it-running.md).

## My connection drops after a few minutes

The device is going into a deep sleep, where nothing runs.

DebDroid prevents this automatically. Check that it is working:

```bash
su
sh /sdcard/debdroid/debdroid.sh status
```

If it does not say `Wakelock: held`, your kernel does not support it, and the
device will only answer while the screen is on. Keeping the screen on and the
device plugged in is the workaround.

## The phone rebooted while I was using the desktop

The device ran out of memory. Two things fix it, and DebDroid does both:

```bash
su
# Give the system room to breathe (takes a few minutes, needed once)
sh /sdcard/debdroid/command/swap.sh create 2G
```

Then set `CONFIG_ENV_SWAP=yes` in `/sdcard/debdroid/debdroid_mgr.sh`.

The desktop helper also turns off the XFCE4 power manager, which is the usual
culprit: it expects a laptop battery and can use a surprising amount of memory
on a phone. Start the desktop with
`sh /sdcard/debdroid/command/vncserver.sh` so this is handled for you.

## ping or apt says permission denied

Android only lets programs use the network if their user is in a group called
`inet`.

Create users with the helper, which handles this:

```bash
su
sh /sdcard/debdroid/debdroid.sh command user
```

For a user you already made, from inside Debian:

```bash
usermod -aG inet <username>
```

Then log out and back in.

---

## Messages that look worse than they are

These are warnings, not failures. DebDroid keeps running.

**`Overlay is unavailable, using a private tmpfs for /dev`**
Your kernel is older than the preferred method needs. DebDroid used the next
best one. Android's own device files are still left alone. No action needed.

**`Falling back to a bind-mount for /dev`**
Neither preferred method worked, so this session shares Android's device files
rather than having its own copy. Everything works; just be aware that changes
to `/dev` inside Debian affect Android too.

**`Wakelocks are unavailable`**
See [my connection drops](#my-connection-drops-after-a-few-minutes).

**`Repaired errors in the debian image`**
DebDroid found and fixed a problem on startup, usually because the last session
did not end cleanly. Nothing more to do, though it is worth using
`debdroid.sh stop` rather than rebooting.

**`Failed to mount "/sdcard", continuing without it`**
Your phone's storage is not visible inside Debian this session. Everything else
works. Starting the session again usually fixes it.

---

## Colours and output

DebDroid marks problems two ways:

- **`Warning:`** in purple — something failed, but it kept going.
- **`Error:`** in red — it could not continue, and cleaned up after itself.

Set `NO_COLOR=1` to turn colours off.

## Still stuck

Open an issue with the output of:

```bash
su
sh /sdcard/debdroid/tests/device.sh
```

That reports your device, Android version, kernel, and what DebDroid found, so
the problem can be diagnosed without a long back-and-forth.
