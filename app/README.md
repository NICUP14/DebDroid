# The DebDroid app

An installer and a front end for the scripts in this repository. It owns no
logic the shell already has: every action is
`su -c 'sh /sdcard/debdroid/debdroid.sh <verb> ...'`, and the app renders what
comes back. Read [AGENTS.md](../AGENTS.md) first.

## Building

```sh
make app                     # from the repository root
make app GRADLE=/path/gradle # with a gradle that is not on PATH
```

That runs `assembleDebug testDebugUnitTest lintDebug` in this directory. It
needs a JDK 17, the android sdk (`ANDROID_HOME`), and gradle 9.4.1 or later.
No gradle wrapper is committed, because its jar is a binary with no
provenance entry; CI installs gradle itself.

A release build is signed only when `DEBDROID_KEYSTORE`,
`DEBDROID_KEYSTORE_PASSWORD`, `DEBDROID_KEY_ALIAS` and `DEBDROID_KEY_PASSWORD`
are set. Without them `assembleRelease` produces an unsigned apk, which is
the point: an apk signed with a throwaway key could never be updated.

## What is generated, and from where

Nothing the shell owns is copied into this directory.

| Generated | From | By |
| --- | --- | --- |
| `versionName`, `versionCode` | `DEBDROID_VER` in `debdroid/debdroid_lib.sh` | `build.gradle.kts` |
| `assets/payload.zip` | `module/payload.list` plus `install.sh` | `stagePayload` |
| `Pinned.kt` (image sha256, size, release url) | `rootfs/manifest` | `generatePins` |
| the launcher icon | `res/icon/android/res` | used in place, as a resource directory |

The payload list is the one `module/build.sh` stages the module zip from, so
the module and the app always carry the same files. The build refuses a
payload containing a CR, which a windows checkout with `core.autocrlf` would
otherwise put into every script.

## Layout

| Package | Holds |
| --- | --- |
| `core` | Pure Kotlin, unit-tested: the probe parser, the install decision table, versions, quoting, the conf-file editor |
| `root` | Running commands through su, unpacking the payload, the DebDroid verbs |
| `work` | Tasks the user watches: the install, the image download, the boot hook |
| `term` | The terminal: emulator, key encoding, the view, pty sessions |
| `service` | The foreground service and notification, the Quick Settings tiles, the boot receiver |
| `ui` | The screens, drawn from the design canvas |

`src/main/cpp/pty.c` is the pty: android has no `forkpty()` an app can call.
It is written here rather than taken from a terminal project, as the usual one
is GPLv3 and this project is MIT.

## Rules that keep it honest

- **State comes from the probe.** `debdroid.sh probe` (see
  [docs/reference/probe.md](../docs/reference/probe.md)) is the only source of
  what the device is doing. No screen shows "running" because a button was
  pressed.
- **stderr is merged into every output view.** `error` and `warn` write to
  stderr; dropping it turns every failure into a spinner that stops.
- **The checksum is pinned at build time** from `rootfs/manifest` and never
  taken from a download. A downloaded mismatch is discarded; a mismatch in the
  offline installer the user supplied is shown and left to them (below).
- **Flags go to `debdroid.conf`, only when changed.** A default written there
  would pin it against every later release (AGENTS.md section 5).
- **minSdk 21 and no AndroidX.** Most AndroidX libraries raised their floor
  above android 5, which is this project's floor.

## Getting the image

There are two ways, and the app keeps no downloader of its own.

- **Downloaded by the installer.** When there is no image, `install.sh` runs
  `debdroid/debdroid_image.sh`, the same route the slim module zip takes, and
  the app only watches the staging files (`debian.img.download.gz`, then
  `debian.img.download`) to show progress. A mismatch there discards the file
  and installs nothing.
- **Taken from the offline installer.** "Use the offline installer instead"
  accepts `DebDroid-module-offline.zip` and nothing else: not a loose
  `debian.img` or `debian.img.gz`. The zip must carry `module.prop` with
  `id=debdroid`, and the image is read from its `img/` directory, whole or
  reassembled from the `debian.img.part-*` pieces in order. A mismatch with
  the pinned checksum is shown with both checksums and installed only if the
  user presses "Use it anyway" (see
  [integrity](../docs/reference/integrity.md#the-one-exception-a-file-you-supply-yourself)).

The app used to download with android's own http stack as well. On android 7
that failed certificate validation for the releases host, whose root the
device does not trust, so it was removed rather than patched with a pinned
certificate. The installer's route works there, and the proper fix for the
devices it does not reach is a rebuilt busybox whose `wget` handles modern
TLS on its own.

## Known limits

- Command scripts run in a terminal tab rather than in an output sheet, as
  `user` and `vncserver` ask for passwords.
- Nothing is installed while Debian is running: the installer would replace
  the busybox its supervisor executes. The app offers to stop it first.
- A device without android's toolbox `curl` cannot download the image until
  busybox is rebuilt (AGENTS.md section 5); the offline installer works there.
