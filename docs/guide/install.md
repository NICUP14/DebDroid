[← Guides](README.md)

# Install DebDroid

There are two ways. **The first is much easier** — use it unless you have a
reason not to.

Check [will it work on my phone?](requirements.md) first.

## What you'll end up with

DebDroid installed, and a Debian session you can open.

---

## The easy way: flash the module

If your device is rooted with **Magisk, KernelSU or APatch**, this is one tap
and no terminal at all.

1. Download `DebDroid-module.zip` from the
   [releases page](https://github.com/NICUP14/DebDroid/releases).
2. Open your root app (Magisk, KernelSU or APatch).
3. Go to **Modules**, then **Install from storage**, and pick the zip.
4. Reboot.

That's it. It installs everything and sets Debian to start automatically after
each reboot.

Skip to [check it worked](#check-it-worked).

---

## The manual way

Use this if you rooted your device some other way, or if you want to see
exactly what gets installed.

You need a file manager that can extract a zip —
[MiXplorer](https://play.google.com/store/apps/details?id=com.mixplorer.silver)
works well — and a terminal app from
[requirements](requirements.md#apps-youll-want).

### 1. Download and extract

1. Download [DebDroid-main.zip](https://github.com/NICUP14/DebDroid/archive/refs/heads/main.zip).
2. Find it in your `Download` folder.
3. Extract it there, using your file manager.

You should now have a folder called `DebDroid-main` inside `Download`.

### 2. Run the installer

Open the terminal app and type these, pressing Enter after each:

```bash
su
cd /sdcard/Download/DebDroid-main
sh install.sh
```

Grant the root permission when your root app asks for it.

The installer asks how big to make the Debian install. **Press Enter** for the
default — you can change it later with [resize](storage.md).

---

## Check it worked

```bash
su
sh /sdcard/debdroid/tests/device.sh
```

This inspects the whole setup and reports anything wrong. It changes nothing,
so it is safe to run any time.

Most lines should say `PASS`. Some `SKIP` lines are normal — they mean your
device's kernel does not have an optional feature, which DebDroid works around.

Now open a session:

```bash
su
sh /sdcard/debdroid/debdroid.sh
```

You are inside Debian. Type `exit` to leave.

## Where things went

| Path | What it holds |
| --- | --- |
| `/sdcard/debdroid` | Scripts and the Debian install |
| `/data/local/debdroid` | Programs DebDroid needs |

## If it didn't work

- **`su: not found`** — the device is not rooted, and DebDroid cannot run on
  it.
- **`Unsupported architecture`** — see [requirements](requirements.md).
- **`Missing required file`** — you are in the wrong folder. `cd` to the
  extracted `DebDroid-main` folder; it should contain `install.sh`.
- **The module installed but nothing happens** — reboot, then run the checker
  above.
- **Anything else** — run the checker and see
  [something went wrong](problems.md).

## Updating later

Re-run the installer, flash a newer module, or use `debdroid.sh self-update`.
Your Debian install and your settings are kept either way. See
[updating](updating.md).

---

Next: [first steps](first-steps.md).
