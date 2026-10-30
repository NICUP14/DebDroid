# DebDroid — Debian on Android

Turn an old, rooted Android phone into a real Debian machine. Not an emulator
and not a rebuilt userland: actual Debian, running actual Debian packages.

A spare phone already has a CPU, RAM, storage, a battery that doubles as a UPS,
Wi-Fi and a screen. DebDroid makes it usable as a small server or workstation
without flashing anything or losing Android.

![neofetch running in a terminal](res/cli-neofetch.jpg)

## What you can do with it

**Run a server.** SSH, a web server, Pi-hole, Syncthing, a CI runner, a VPN
endpoint. It keeps running when you close the terminal, and can start again
after a reboot.

**Run a desktop.** XFCE4 over VNC, with Firefox, an office suite and an editor.
See the [gallery](docs/gallery.md).

**Develop on it.** Compilers, interpreters and debuggers — the real ones from
the Debian archive, not ports.

**Keep using Android.** Debian runs alongside it. No dual-boot, no flashing,
nothing lost.

## How it compares

| | DebDroid | Termux | proot-distro | Linux Deploy |
| --- | --- | --- | --- | --- |
| Needs root | **Yes** | No | No | Yes |
| Real glibc userland | **Yes** | No (bionic) | Yes | Yes |
| Debian packages run unmodified | **Yes** | No (rebuilt) | Yes | Yes |
| Syscall overhead | **None** | None | Yes (ptrace) | None |
| Android version floor | **5.0** | 7.0 | 7.0 | varies |
| Hardware access (`/dev`, `/sys`) | **Yes** | Limited | Limited | Yes |
| Runs servers in the background | Yes | Yes | Yes | Yes |
| Android tools reachable from Linux | **Yes** | Via addon app | No | Partly |

**Root is the trade.** It is what buys a real glibc userland with no emulation,
so nearly the whole Debian archive works unmodified. If you do not want to root
your device, Termux is the better answer and that is not a criticism of it.

The Android 5.0 floor is the other reason this exists: current Termux needs
Android 7, which leaves the 2012–2015 devices — the ones most likely to be
sitting in a drawer — without a good option.

## Getting started

**[Start here →](docs/guide/README.md)**

1. [Will it work on my phone?](docs/guide/requirements.md)
2. [Install it](docs/guide/install.md) — about ten minutes
3. [First steps](docs/guide/first-steps.md)

Requires a rooted `arm64-v8a` device running Android 5.0 or later, and 1 GB of
free space (5 GB is comfortable).

## How it works

DebDroid is a runtime layer, not a chroot script. It builds an isolated
filesystem view in a private mount namespace, holds a lock that two sessions
cannot defeat, preloads compatibility libraries that fix what Android's kernel
does not provide, ships its own userspace tools, and bootstraps the environment
before handing over a session.

```txt
Android
 ↓
DebDroid runtime
  • BusyBox and e2fsprogs
  • Compatibility libraries (preloaded)
  • Bootstrap script
  • Filesystem and namespace manager
 ↓
Debian
```

The [reference documentation](docs/reference/README.md) covers the design,
including the two constraints that shape it: only a mount namespace is
available on Android devices, and the image stays ext2 because old kernels
reject modern ext4 features.

## Documentation

**[Guides](docs/guide/README.md)** — step by step, assumes nothing.

**[Reference](docs/reference/README.md)** — architecture, session lifecycle,
mounts, configuration, every command, and the security model.

**[Third-party components](THIRD_PARTY.md)** — where every bundled binary came
from, with checksums. DebDroid asks for root, so this is written down rather
than left to trust.

## Security

Most devices old enough to be spare no longer receive security patches, so
their kernels have known, unpatched holes. **Do not expose one directly to the
internet, and do not keep anything sensitive on it.** Use a VPN if you need
remote access.

DebDroid is a good way to get use out of hardware that would otherwise be
landfill. It does not make that hardware trustworthy. See
[security](docs/reference/security.md) for the full picture.

## Contributing

[TODO list](TODO.md) · [Changelog](CHANGELOG.md) · [Testing](docs/reference/testing.md)

```sh
make check    # syntax and shellcheck
make test     # the host test suite, no device needed
make all      # both, plus the provenance check
```

On a device, `sh /sdcard/debdroid/tests/device.sh` checks a real install and
only reads by default. Its output is what a useful bug report contains.

## Credits

- [Meefik](https://github.com/meefik) for the android build of `busybox`.
- [LineageOS](https://github.com/LineageOS) for the android port of `e2fsprogs`.
- [Janithcooray](https://github.com/janithcooray) for the swap module that
  showed which approach works on android.

## License

Copyright © 2025-2026 Nicolae Petri. MIT licensed; see [LICENSE](LICENSE).

Some bundled binaries are GPL-2.0; see [NOTICE](NOTICE) and
[THIRD_PARTY.md](THIRD_PARTY.md).

## Disclaimer

Provided "as is", without warranty of any kind. See [LICENSE](LICENSE) for the
full text.
