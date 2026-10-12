# TODO List

For DebDroid v1.2:

- [X] Prompt resize during install.
- [X] Prompt for backup/overwrite of `debian.img` during install.
- [X] Implement resize functionality.
- [X] Update old binaries  (`e2fsck`, `resize2fs`, `truncate`).
- [X] Run `e2fsck` before mounting the image in `debdroid.sh`
- [X] Update binaries (`truncate`, `resize2fs`, `e2fsck`, `busybox`)
- [X] Implement feature-based configuration mechanism for DebDroid.
- [X] Security check for mounted image in `debdroid_resize.sh`.
- [X] Implement robust unshare check. (`/proc/self/ns/mnt`)
- [X] Update README:
  - Offload large sections into `docs`.
  - Document and mention the container runtime.
- [X] Categorize failures into warnings and errors in `debdroid_mgr.sh`.
- [ ] ~~Reorganize and refactor preload libraries:
  - Implement linker fix in `libexec.so`.
  - Move syscall logic from `librandom.so` and link into `libsyscall.so`.~~
- [X] Implement DebDroid PID loggers and locks for more robust checks.
- [ ] Implement DebDroid background mode. (start, stop, attach)
- [ ] Implement a smart configuration system for `debdroid_mgr.sh`
- [ ] Update docs:
  - Add x11 xfce4 vnc guide.
  - Document `debdroid_mgr.sh` configuration flags.
  - Add preloading and binary mentions to `docs/Security.md`.
  - Add randomness library mentions to `docs/Patches.md`.
  - Add ReTerminal mention to `docs/Install.md`.

Planned for v1.3:

- [ ] Add a statically-compiled `strace` binary for issue reporting.
- [ ] Rescue shell functionality for DebDroid.
- [ ] Report command functionality for DebDroid. (auto-generated issue reports)
- [ ] Update command functionality for DebDroid.
- [ ] Multi-arch support

Planned for future versions of DebDroid:

- [ ] GPU acceleration.
- [ ] Add QoL commands:
  - [X]`command/update`: Environment updater script.
  - [ ] `command/vncserver`: Easy vnc server management.
  - [ ] `command/sshserver`: Easy ssh server management.
