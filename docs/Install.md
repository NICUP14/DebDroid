# Installation Guide

> [!NOTE]
> Once installed, DebDroid can be safely updated by simply re-running the install script. It updates the scripts, binaries and libraries without overwriting the existing image.
> Back up `/sdcard/debdroid/environment` and `/sdcard/debdroid/debdroid_env.sh` before updating if you have customized them, as they are overwritten.

This guide is written for novice users. You only need basic knowledge of Android, like installing apps from unknown sources, extracting an archive, and a little experience using a terminal (command line).

## Requirements

Supported Android versions:

- Requires an `aarch64` Android device running Lollipop or later (released Nov 2014).
- Covers devices launched in the past ~12 years, from Android 5 through Android 14.

Newer Android releases include improved virtualization support, which may provide a better alternative for some use cases.

Additional requirements:

- The device must be rooted.
- You’ll need at least 1 GB of free storage (5 GB is recommended for a comfortable experience).

## What You Need

Before starting, make sure you have these apps installed on your device:

- [Terminal Emulator](../apk/TerminalEmulator.apk) - lets you access Android's command-line interface.
- [Hacker's Keyboard](../apk/HackersKeyboard.apk) - an advanced keyboard that makes typing commands easier.
- [Root Explorer](https://play.google.com/store/apps/details?id=com.speedsoftware.rootexplorer&hl=en-US) - lets you browse and manage Android files.

Alternatively, you can use a free alternative to Root Explorer, such as [Explorer](https://play.google.com/store/apps/details?id=com.speedsoftware.explorer) or [MiXplorer](https://play.google.com/store/apps/details?id=com.mixplorer.silver).

## Step 1: Downloading and Extracting the Project

1. Download the project zip file: [DebDroid-main.zip](https://github.com/NICUP14/DebDroid/archive/refs/heads/main.zip).
2. Locate `DebDroid-main.zip` in your `Download` folder (`/sdcard/Download`).
3. Extract the `DebDroid-main.zip` archive inside the `Download` folder. You can use an application such as `Root Explorer` or `MiXplorer` for this task.

After extraction, you should have a folder named `DebDroid-main` in your `Download` folder.

## Step 2: Installing DebDroid

1. Open the `Terminal Emulator` app.
2. Type the following commands, pressing Enter after each one:

```bash
su
cd /sdcard/Download/DebDroid-main
sh install.sh
```

The script will automatically install DebDroid files in:

- `/data/local/debdroid`
- `/sdcard/debdroid`

On a fresh install, the script asks for the size of the debian image (e.g. `5G`). Press Enter to keep the default size, it can be changed later with the [resizer](Quickstart.md#image-resizing).

After that, DebDroid is ready to use!
