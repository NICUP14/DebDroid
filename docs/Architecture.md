# Architecture

```txt
/sdcard/debdroid/
├── environment         # User environment config
├── debdroid_env.sh     # DebDroid environment config (paths, environment variables)
├── debdroid.sh         # DebDroid user interface script
├── debdroid_mgr.sh     # DebDroid backend script (mounts and manages chroot)
├── debdroid_resize.sh  # DebDroid image resizer
├── debdroid_ui.sh      # Output helpers shared by the scripts (colors, errors, warnings)
├── img/
│   └── debian.img      # Debian root filesystem
└── command/            # Command scripts
```

Scripts placed inside the `command` folder can be easily executed to conduct specific tasks within the chroot environment, such as updating the system or managing ssh & vnc servers (coming soon!). Consult the [running commands section](Quickstart.md#running-commands).

```txt
/data/local/debdroid/
├── bin            # External binaries (main ones listed below)
│   ├── busybox    # Userspace tools used by the scripts
│   ├── e2fsck     # Image checker and repair tool
│   ├── resize2fs  # Image resizer
│   └── debinit    # DebDroid bootstrap script
├── lib            # External libraries
├── mnt            # Debian mount point
└── mnt.lock       # Session lock file
```

Programs stored in `bin` can be run directly inside the chroot as it is mounted and appended to `$PATH`. Libraries placed inside `lib` will be automatically preloaded, allowing for custom overrides and patches without any modifications to Linux system files.

`bin/debinit` is the DebDroid bootstrap script executed at the start of every session. It runs specifically in the Linux userland to address persistent environment issues and quirks.

For security reasons, the session spawns a clean login shell without inheriting the host's environment variables. Any required variable must be explicitly set in `debdroid/environment`, which is automatically sourced by `bin/debinit`.

## Session Lifecycle

Each session runs in a private mount namespace, so its mounts are invisible to Android and are discarded when the session ends.

1. `debdroid_mgr.sh` takes the `mnt.lock` lock. Only one session can use the image at a time, as mounting it twice corrupts it.
2. `e2fsck` checks and repairs the image.
3. The image and the Android filesystems are mounted, and the user's command (or shell) is started.
4. On exit, every process left running in the session (e.g. `dbus-daemon`, `sshd`) is stopped, then all filesystems are unmounted.

The lock is inherited by the processes running in the session, so it is only released once all of them have exited.
