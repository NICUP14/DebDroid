[← Guides](README.md)

# Install DebDroid

About ten minutes. Check [will it work on my phone?](requirements.md) first.

## What you'll end up with

DebDroid installed, and a Debian session you can open.

## Step 1: Get the apps

From [requirements](requirements.md#apps-youll-want): a terminal, a better
keyboard, and a file manager that can unzip.

## Step 2: Download the project

1. Download [DebDroid-main.zip](https://github.com/NICUP14/DebDroid/archive/refs/heads/main.zip).
2. Find it in your `Download` folder.
3. Extract it there, using your file manager.

You should now have a folder called `DebDroid-main` in `Download`.

## Step 3: Install

Open the terminal app and type these, pressing Enter after each:

```bash
su
cd /sdcard/Download/DebDroid-main
sh install.sh
```

Grant the root permission when asked.

It asks how big to make the Debian install. Press Enter for the default, or
type something like `5G`. You can change it later with
[resize](storage.md).

Files are installed to:

- `/sdcard/debdroid` — scripts and the Debian install
- `/data/local/debdroid` — the programs DebDroid needs

## Step 4: Check it worked

```bash
su
sh /sdcard/debdroid/tests/device.sh
```

This inspects the whole setup and reports anything wrong. It changes nothing.
Everything should say `PASS`, with some `SKIP` lines for features your device's
kernel may not have — those are fine.

Then open a session:

```bash
su
sh /sdcard/debdroid/debdroid.sh
```

Type `exit` to leave.

## Installing as a Magisk module

If you use Magisk, KernelSU or APatch, you can flash the module instead, which
is a single tap and sets up start-at-boot for you:

1. Download `DebDroid-module.zip` from the releases page.
2. In your root app, go to Modules and install from storage.
3. Reboot.

## If it didn't work

- **`su: not found`** — the device is not rooted. DebDroid cannot run on it.
- **`Unsupported architecture`** — see [requirements](requirements.md).
- **`Missing required file`** — you are in the wrong folder. `cd` to the
  extracted `DebDroid-main` folder, which should contain `install.sh`.
- **Anything else** — run the checker above and see
  [something went wrong](problems.md).

## Updating later

Re-run the installer, or use `debdroid.sh self-update`. Your Debian install and
your settings are kept either way. See [updating](updating.md).

---

Next: [first steps](first-steps.md).
