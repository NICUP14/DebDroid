[← Docs](../README.md)

# Keep It Running

By default, Debian stops when you close the terminal. That is fine for trying
things out, but it means a web server or an SSH server stops too.

Background mode fixes that. Read this page before setting up any server.

## What you'll end up with

Debian running in the background, so anything you start inside it keeps running
after you close the terminal. It can also start again after a reboot.

## Start it in the background

```bash
su
sh /sdcard/debdroid/debdroid.sh start
```

It prints `Started` when it is ready. The first start takes longer because it
checks the Debian install for errors.

## Open a session

```bash
su
sh /sdcard/debdroid/debdroid.sh attach
```

You get a shell inside Debian. Type `exit` to leave. **Leaving does not stop
anything.** You can attach and leave as often as you like.

Attaching is also much faster than `run`, because everything is already set up.

## Check what's happening

```bash
su
sh /sdcard/debdroid/debdroid.sh status
```

This tells you whether Debian is running, how many programs are running inside
it, and whether the screen-off protection below is active.

## Stop it

```bash
su
sh /sdcard/debdroid/debdroid.sh stop
```

This stops everything inside Debian and unmounts it cleanly. Always stop it
this way rather than rebooting, so the Debian install is not damaged.

## Keep it awake when the screen is off

Android puts a sleeping phone into a deep sleep where nothing runs. A server
that is asleep does not answer.

DebDroid prevents this automatically while it is running, so you do not need to
do anything. You can confirm it with `status`, which shows `Wakelock: held`.

If your device's kernel does not support this, DebDroid says so when it starts
and keeps going. Your server will then only answer while the screen is on.

> [!NOTE]
> Keeping the device awake uses more battery. Leave it plugged in if you are
> running something continuously.

## Give it more memory

Phones have little memory, and a desktop or a database can run out. When
Android runs out of memory it kills programs, and sometimes reboots.

A swapfile gives the system room to breathe:

```bash
su
sh /sdcard/debdroid/debdroid.sh swap create 2G
```

This takes a few minutes, writes a 2 GB file to internal storage, and only
needs doing once. Then switch it on by editing
`/sdcard/debdroid/debdroid_mgr.sh` and changing:

```sh
CONFIG_ENV_SWAP=no
```

to:

```sh
CONFIG_ENV_SWAP=yes
```

Check it with `sh /sdcard/debdroid/debdroid.sh swap status`.

### If swap doesn't work on your device

Some devices cannot use a swapfile. DebDroid tells you which case you are in
rather than failing silently:

- **"This kernel does not support swap"**: your device's kernel was built
  without it. Nothing can be done short of a different kernel.
- **"A swapfile cannot live on an f2fs filesystem"**: your device formats its
  internal storage in a way that does not allow swapfiles. Look for a zram
  module for your root solution instead.

## Start it automatically after a reboot

If your device is rooted with Magisk, KernelSU or APatch:

```bash
su
cp /sdcard/debdroid/boot/debdroid.service.sh /data/adb/service.d/debdroid.sh
chmod 755 /data/adb/service.d/debdroid.sh
```

Reboot to test it, then check with `debdroid.sh status`.

To turn it off again, delete that file.

> [!NOTE]
> If a boot attempt fails, DebDroid does not try again on the next boot. This
> is deliberate: a script that fails the same way at every boot can make a
> device hard to recover. Look at `/data/local/debdroid/boot.log` to see what
> happened, then delete `/data/local/debdroid/boot.incomplete` to re-enable it.

## How to tell it worked

```bash
su
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach sh -c 'nohup sleep 600 &'
# Close the terminal app completely, then open it again
su
sh /sdcard/debdroid/debdroid.sh status
```

If `status` still reports it running, background mode is working.

## If it didn't work

- **"A background environment is already running"**: it is already up. Use
  `attach`, or `stop` first.
- **"The background environment did not start in time"**: look at
  `/data/local/debdroid/mnt.log`, which holds the full startup output.
- **Anything else**: run the checker, which inspects your whole setup:
  ```bash
  su
  sh /sdcard/debdroid/tests/device.sh
  ```

---

Next: [connect from your computer](remote-access.md) or
[run a desktop](desktop.md).
