# Quickstart Guide

## Usage

To display DebDroid's help page, run:

```bash
su
sh /sdcard/debdroid/debdroid.sh help
```

```txt
Usage:
  debdroid.sh [OPTION] [SUBOPTION] [ARGUMENTS]

Options:
  run [COMMAND...]
      Runs the default Debdroid environment.
      If COMMAND is provided, it executes that command inside the environment.
      If no command is given, an interactive shell is started.

  list
      Lists all command scripts in the command directory.

  command [COMMAND_NAME]
      Executes the specified command script from the command directory.
      Example: debdroid.sh command setup_user

  resize [SIZE]
      Resizes the debian image to the specified size.
      Example: debdroid.sh resize 5G

Notes:
  - Unrecognized options are treated the same as the 'run' option.
```

## Running Interactive Sessions

1. Open the `Terminal Emulator` app.
2. Type the following commands, pressing Enter after each one:

```bash
su
sh /sdcard/debdroid/debdroid.sh
```

This launches the environment and gives you a shell inside Debian.

Inside the chroot shell, users can execute the `exit` command to leave the environment and automatically unmount the filesystems.

> [!NOTE]
> Leaving the environment also stops every program still running inside it, including background services (e.g. `sshd`, VNC servers). Keep the session open for as long as they need to run.

Only one session can run at a time. Starting a second one fails with `The environment is already in use`.

## Running Commands

You can run a specific command inside the chroot without starting an interactive shell. DebDroid provides pre-made command scripts located in `/sdcard/debdroid/command`, which automate common tasks such as maintenance, setup, or service management.

To list available command scripts, run:

```bash
su
sh /sdcard/debdroid/debdroid.sh list
```

To execute a command script, run:

```bash
su
sh /sdcard/debdroid/debdroid.sh command <command-name>
```

To execute other commands directly inside the chroot, run:

```bash
su
sh /sdcard/debdroid/debdroid.sh apt update
# or (explicit version)
sh /sdcard/debdroid/debdroid.sh run apt update
```

This will execute the `apt update` command directly in the chroot environment.

## Image Resizing

> [!WARNING]
> Starting from v1.2, the size passed to the resizer is no longer relative!

The Debian root filesystem (debian.img) in DebDroid has a fixed size. If you need more space, you can easily expand it using the built-in resize helper.

The following command resizes the debian environment to 5 GiB:

```bash
su
sh /sdcard/debdroid/debdroid.sh resize 5G
```

For additional usage instructions, run:

```bash
su
sh /sdcard/debdroid/debdroid.sh resize
```

- Sizes are an integer followed by a unit: `K`, `M` or `G` (powers of 1024), e.g. `512M` or `5G`.
- Exit the environment before resizing, the resizer refuses to run while it is in use.
- Shrinking keeps your data, but fails if the data does not fit in the new size.
- Ensure you have **enough free storage** on your device to accommodate the new image size.

## User Networking

Due to Android group restrictions, users inside the DebDroid chroot need to be added to the `inet` group to access networking tools.

```bash
usermod -aG inet <username>
```

After this, the user should be able to use networking commands like `ping`.
