<div align="center">

# DebDroid

**Real Debian on a rooted Android phone.**

Real Debian packages, not an emulator and not a rebuilt userland.<br>
Android keeps working. Nothing is flashed and nothing is lost.

[![ci](https://img.shields.io/github/actions/workflow/status/NICUP14/DebDroid/ci.yml?branch=testing&style=flat-square&label=ci)](https://github.com/NICUP14/DebDroid/actions/workflows/ci.yml)
[![version](https://img.shields.io/badge/version-1.2.6-blue?style=flat-square)](CHANGELOG.md)
[![android](https://img.shields.io/badge/android-5.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](docs/guide/requirements.md)
[![arch](https://img.shields.io/badge/arch-arm64--v8a-lightgrey?style=flat-square)](docs/guide/requirements.md)
[![shell](https://img.shields.io/badge/shell-POSIX%20sh-89e051?style=flat-square)](AGENTS.md)
[![license](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

[**Get started**](docs/guide/README.md) · [Guides](docs/guide/README.md) · [Reference](docs/reference/README.md) · [Changelog](CHANGELOG.md)

<img src="res/cli-neofetch.jpg" width="620" alt="neofetch running in a terminal">

</div>

## Quick look

```sh
sh /sdcard/debdroid/debdroid.sh start              # debian comes up in the background
sh /sdcard/debdroid/debdroid.sh command sshserver  # now ssh in from your laptop
sh /sdcard/debdroid/debdroid.sh attach             # or get a shell right here
```

Close the terminal and it keeps running. It can also start again after a reboot.

## What you can do with it

|  |  |
| --- | --- |
| 🖥️ **Run a server** | SSH, nginx, Pi-hole, Syncthing, Gitea, a CI runner, a VPN endpoint. The battery acts as a UPS. |
| 🪟 **Run a desktop** | XFCE4 over VNC, with Firefox, an office suite, GIMP and Wireshark. |
| 🔧 **Develop on it** | The compilers, interpreters and debuggers from the Debian archive, unmodified. |
| 📱 **Keep Android** | Debian runs alongside it. No dual boot, no flashing, no data loss. |

<table>
  <tr>
    <td><img src="res/xfce4-firefox.png" alt="firefox on xfce4"></td>
    <td><img src="res/xfce4-code.png" alt="visual studio code on xfce4"></td>
    <td><img src="res/xfce4-gimp.png" alt="gimp on xfce4"></td>
  </tr>
  <tr>
    <td align="center"><sub>Firefox</sub></td>
    <td align="center"><sub>Visual Studio Code</sub></td>
    <td align="center"><sub>GIMP</sub></td>
  </tr>
</table>

More in the [gallery](docs/gallery.md).

## How it compares

| | DebDroid | Termux | proot-distro | Linux Deploy |
| --- | :---: | :---: | :---: | :---: |
| Needs root | **Yes** | No | No | Yes |
| Real glibc userland | **Yes** | No (bionic) | Yes | Yes |
| Debian packages run unmodified | **Yes** | No (rebuilt) | Yes | Yes |
| Syscall overhead | **None** | None | Yes (ptrace) | None |
| Android version floor | **5.0** | 7.0 | 7.0 | varies |
| Hardware access (`/dev`, `/sys`) | **Yes** | Limited | Limited | Yes |
| Android tools reachable from Linux | **Yes** | Via addon app | No | Partly |

Root is what buys the rest of that table. It gives you a real glibc userland
with no emulation, so nearly the entire Debian archive works untouched. If you
do not want to root your device, use Termux instead. It is a good project.

The Android 5.0 floor is the other reason DebDroid exists. Current Termux needs
Android 7, which leaves the 2012-2015 devices without a good option, and those
are the ones most likely to be sitting in a drawer.

## Getting started

| | |
| --- | --- |
| **1.** | [Will it work on my phone?](docs/guide/requirements.md) |
| **2.** | [Install it](docs/guide/install.md), about ten minutes |
| **3.** | [First steps](docs/guide/first-steps.md) |

You need a rooted `arm64-v8a` device on Android 5.0 or later and 1 GB of free
space. 5 GB is comfortable.

## How it works

DebDroid is a runtime layer rather than a chroot script. It builds an isolated
filesystem view, holds a lock that two sessions cannot defeat, preloads
compatibility libraries for what the kernel does not provide, ships its own
userspace tools, and bootstraps Debian before handing over a session. That is
about 2000 lines of POSIX shell, with no daemon and no app required.

Two constraints shape the design. Only a mount namespace is available on
Android devices, and the image stays ext2 because old kernels reject modern
ext4 features. Both came out of testing on real phones, not from a preference.
[The reference docs](docs/reference/README.md) cover what follows from them.

## Security

> [!WARNING]
> Devices old enough to be spare usually stopped getting patches years ago, so
> their kernels have known, unfixed holes. Do not expose one to the internet,
> and do not keep anything sensitive on it. Use a VPN for remote access.

DebDroid gets more years out of hardware that would otherwise be landfill. It
does not make that hardware safe. See [security](docs/reference/security.md)
for the details.

DebDroid asks for root, so the origin, version, license and checksum of every
bundled binary is written down in [THIRD_PARTY.md](THIRD_PARTY.md).

## Contributing

```sh
make check    # syntax and shellcheck
make test     # the host test suite, no device needed
make all      # both, plus the provenance check
```

Start with [AGENTS.md](AGENTS.md). It has the conventions, which file to change
for a given task, and the gotchas that produce code which looks correct and
breaks on real hardware. Open work is in [TODO.md](TODO.md), and the test tiers
are described in [testing](docs/reference/testing.md).

On a device, `sh /sdcard/debdroid/tests/device.sh` checks a real install and
only reads by default. Its output is what a useful bug report contains.

<details>
<summary><b>Credits and license</b></summary>

<br>

- [Meefik](https://github.com/meefik) for the android build of `busybox`.
- [LineageOS](https://github.com/LineageOS) for the android port of `e2fsprogs`.
- [Janithcooray](https://github.com/janithcooray) for the swap module that
  showed which approach works on android.

Copyright © 2025-2026 Nicolae Petri. MIT licensed, see [LICENSE](LICENSE).
Provided "as is", without warranty of any kind.

Some bundled binaries are GPL-2.0, see [NOTICE](NOTICE) and
[THIRD_PARTY.md](THIRD_PARTY.md).

</details>
