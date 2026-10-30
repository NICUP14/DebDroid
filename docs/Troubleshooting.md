# Troubleshooting Guide

DebDroid labels its problems as either a warning or an error:

- `Warning:` something failed, but the environment can still run (e.g. `/sdcard` could not be mounted).
- `Error:` the environment cannot run, it is stopped and cleaned up.

Warnings are shown in purple and errors in red. Set `NO_COLOR=1` to disable colors.

## System reboot due to XFCE4

Newer releases of the XFCE4 desktop cause known problems within DebDroid environments. The issue is due to the power‑manager plugin, which assumes a traditional PC setup. This mismatch triggers excessive memory consumption, which can quickly exhaust the limited RAM on Android devices, resulting in a system reboot.

Two things fix this, and DebDroid now handles both:

```bash
su
# Give the device room to breathe
sh /sdcard/debdroid/command/swap.sh create 2G
# Then start the desktop, which disables the power-manager plugin for you
sh /sdcard/debdroid/debdroid.sh start
sh /sdcard/debdroid/debdroid.sh attach sh /sdcard/debdroid/command/vncserver.sh
```

Set `CONFIG_ENV_SWAP=yes` in `/sdcard/debdroid/debdroid_mgr.sh` so each session enables the swapfile.

> [!NOTE]
> Earlier versions of this guide pointed at a third-party Magisk swap module. That is no longer needed: DebDroid manages its own swapfile with the bundled tools. The module remains credited in [Third-Party Components](../THIRD_PARTY.md).


## The environment is already in use

Only one DebDroid session can run at a time. Exit the other session first, including sessions opened in other terminal windows or over ADB.

If no session is open, a program started in an earlier session may still be running. Rebooting the device stops it.

## Failed to unmount the debian image, it is still in use

A program inside the environment could not be stopped on exit, so the image is still mounted. Reboot the device before starting DebDroid again, otherwise the image may be corrupted.

## Failed to repair the debian image

The image has errors that `e2fsck` cannot fix automatically. Make sure no session is running, then repair it manually and answer the prompts:

```bash
su
/data/local/debdroid/bin/e2fsck -f /sdcard/debdroid/img/debian.img
```

> [!NOTE]
> Earlier releases left programs such as `dbus-daemon` running after exit, which kept the image mounted (`umount: can't unmount ... Device or resource busy`) and damaged it. If you are upgrading, repair the image once as shown above.
