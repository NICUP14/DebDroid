# DebDroid - Debian On Android

> [!IMPORTANT]
> Previous versions (v1.0 and v1.1) contain critical security bugs and missing functionality. Users are strongly encouraged to update DebDroid to the latest version (v1.2). The update keeps your existing debian image, see the [installation guide](docs/Install.md) for details.

DebDroid repurposes your old, unused Android devices into compact Debian workstations. It offers a lightweight sandboxed Linux environment suitable for many workloads traditionally assigned to Raspberry Pi and other single-board computers. Since it runs on top of native Android, you get a full Debian userspace without requiring additional hardware or a dedicated Linux installation, making it an attractive, plug-and-play solution for developers and sysadmins who need a portable, low-maintenance server on hand.

Check out the [photo gallery](docs/Gallery.md)!

## DebDroid is not a Chroot Script

Although it uses `chroot` internally, DebDroid's architecture is closer to a lightweight container environment than a traditional chroot script. It acts as a lightweight runtime layer for Debian on Android, assembling an isolated filesystem view, injecting compatibility fixes, shipping its own userspace tools, and bootstrapping the environment before starting the user's session.

In practice, it sits between Android and Debian, providing a compatibility and management layer rather than simply executing a chroot command.

```txt
Android
 ↓ 
DebDroid Runtime 
  • BusyBox
  • e2fsprogs
  • Compatibility libraries
  • Bootstrap/init script
  • Filesystem and namespace manager
 ↓
Debian
```

DebDroid combines filesystem management, mount namespace isolation, Android compatibility shims and environment bootstrapping into a single runtime layer.

## DebDroid is not Termux

Unlike Termux or Proot, which rely on Android-compiled binaries or user-space emulation, DebDroid runs a real Debian userspace directly on Android. It ships its own utilities, filesystem tools, compatibility libraries and bootstrap scripts, while linking key Android filesystems into the Debian environment. The result is a self-contained Debian runtime capable of running Debian software directly on Android.

## Features

- Small and portable.
- No external dependencies.
- Runs a minimal Debian Linux userland in an isolated chroot environment.
- Dynamically assembles the runtime environment.
- Uses private mount namespaces (`unshare`) to isolate mountpoints.
- Mounts key Android system paths to provide near-native Linux functionality.
- Supports `/dev` overlayfs, creating a writable layer over device files without modifying the real `/dev`.

**Linux+Android on the same device:** DebDroid manages a persistent Debian runtime inside Android while keeping the underlying Android OS intact. This lets your run Linux workloads side-by-side with your regular Android apps, all without dual-boot, flashing or loss of Android functionality.

**Flexible access:** You can interact with DebDroid locally through a terminal emulator, ADB or remotely via SSH, VNC, RDP and many others!

**Debian environment on a Android device:** DebDroid provides a complete Linux workspace with a wide array of supported utilities so you can perform software development, system‑administration, or other tasks directly on the device without needing a separate computer.

**Turn your idle Android devices into a headless server:** Whether you need a web server, CI runner, IoT gateway, or VPN endpoint, an old Android device becomes a self‑contained, energy‑efficient server that you can deploy anywhere without the hassle of wiring, power adapters, or a separate SBC.

## Disclaimer

The software is provided "as is", without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose, and noninfringement. In no event shall the authors or copyright holders be liable for any claim, damages, or other liability, whether in an action of contract, tort, or otherwise, arising from, out of, or in connection with the software or the use or other dealings in the software.

## Warp Zone

The list facilitates easy access to relevant documents of the project.

- [TODO List](TODO.md)
- [Architecture](docs/Architecture.md)
- [Security Implications](docs/Security.md)
- [Installation Guide](docs/Install.md)
- [Quickstart Guide](docs/Quickstart.md)
- [Troubleshooting Guide](docs/Troubleshooting.md) 
- [Photo Gallery](docs/Gallery.md)
- [Patches](docs/Patches.md)

## Join the Discussions

> [!NOTE]
> Starting from v1.2, discussions have moved to discord for easier communication with users.

Have questions, ideas, or feedback? Then go ahead and join our **[discord server](https://discord.gg/5aKx85eZc)**!

## Credits

The project wouldn't have existed without:

- [Meefik](https://github.com/meefik) for maintaining the android build of `busybox`.
- [LineageOS team](https://github.com/LineageOS) for maintaining the android build of `e2fsprogs`.
- [Janithcooray](https://github.com/janithcooray) for developing the Magisk swap module.

## License

Copyright © 2025-2026 Nicolae Petri

Licensed under the MIT License.
