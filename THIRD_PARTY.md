# Third-Party Components

DebDroid asks for root, which is more trust than most software asks for. This
page records where every file that is not source code came from, so that trust
is something you can check rather than something you have to extend.

The machine-readable version lives in [provenance/manifest](provenance/manifest)
and is verified by CI, which also fails on any binary that has no entry.

## How to read this

Each file falls into one of three tiers, in decreasing order of what can be
proven about it:

| Tier | Meaning |
| --- | --- |
| **A** | Built from source in this repository |
| **B** | Built from a documented, pinned recipe |
| **C** | Third-party prebuilt; origin recorded, but not reproducible here |

Tier C is the honest answer to "open source but ships closed blobs", and the
goal is to empty it rather than to explain it.

## Bundled binaries

| File | Tier | License | Origin |
| --- | --- | --- | --- |
| `bin/busybox` | C | GPL-2.0 | Prebuilt android build by [meefik](https://github.com/meefik/busybox) |
| `bin/ssl_helper` | C | GPL-2.0 | TLS helper for busybox `wget`, same source |
| `bin/e2fsck` | B | GPL-2.0 | [LineageOS/android_external_e2fsprogs](https://github.com/LineageOS/android_external_e2fsprogs) at commit `8045c66`, NDK r27d |
| `bin/resize2fs` | B | GPL-2.0 | Same as above |
| `bin/test_random` | A | MIT | `package/test/test_random` |
| `lib/librandom.so` | A | MIT | `package/lib/librandom` |
| `lib/libarc4random.so` | A | MIT | `package/lib/libarc4random` |

`bin/e2fsck` and `bin/resize2fs` are the model for the rest: their build is
pinned to an exact upstream commit and NDK version in
[package/bin/e2fsprogs/Compile.md](package/bin/e2fsprogs/Compile.md), so anyone
can reproduce them.

**`e2fsck` and `resize2fs` cannot be dropped.** busybox has no equivalent: its
`fsck` applet is only a front-end wrapper, and it provides no `resize2fs` at
all. It does provide `mke2fs` and `tune2fs`.

### Why busybox is being self-built

`bin/busybox` is the largest bundled binary and the one every script depends
on, yet it is the one with the least provenance. Building it from source with
the NDK pipeline that already works for e2fsprogs moves it to tier B, and
enabling busybox's own `ssl_client` applet removes the need for `ssl_helper`
entirely. See [docs/reference/building.md](docs/reference/building.md).

meefik's builds remain credited for the configuration and the prior art, and
are used as a cross-check on the applet table.

## Applications

DebDroid no longer ships APKs. Installing an app from a repository that builds
it from source and signs the result is a stronger guarantee than a binary
committed to a git repository, and it removes the need for you to trust this
project with the most sensitive app on your device.

| App | Package | License | Install from |
| --- | --- | --- | --- |
| Term One Plus | `com.termoneplus` | Apache-2.0 | [F-Droid](https://f-droid.org/packages/com.termoneplus/) |
| Android Terminal Emulator | `jackpal.androidterm` | Apache-2.0 | [F-Droid](https://f-droid.org/packages/jackpal.androidterm/) |
| Hacker's Keyboard | `org.pocketworkstation.pckeyboard` | Apache-2.0 | [F-Droid](https://f-droid.org/packages/org.pocketworkstation.pckeyboard/) |

Term One Plus is the recommended terminal: it is a maintained fork of
jackpal's Android Terminal Emulator, which has not been updated since 2015.

See [docs/reference/signatures.md](docs/reference/signatures.md) for how to
verify that what you installed is what you intended to install.

### Why the previously bundled APKs were removed

Each of the three was examined before being dropped:

- **`TerminalEmulator.apk`** was genuine. It was `jackpal.androidterm` v1.0.70
  from 2015, signed with `META-INF/JACKPAL_.RSA`, the upstream author's own
  key. It was removed because an app store is a better delivery channel than a
  git repository, not because anything was wrong with it.
- **`HackersKeyboard.apk`** could not be vouched for. The package name was
  correct, but the file carried `META-INF/android.arch.*.version` markers from
  Android Architecture Components, which postdate Hacker's Keyboard's last
  upstream release by years; every timestamp inside it was zeroed to
  `1980-00-00`; and it was signed with a generic `CERT.RSA` rather than an
  identifiable author key. None of that proves the file was malicious, but it
  is inconsistent with an upstream build, and a keyboard sees every keystroke
  typed on the device, including the password typed at `su`. It was removed for
  that reason alone.
- **`lin_os_swap_mod.zip`** is no longer needed. DebDroid now manages swap
  itself with the bundled `mkswap` and `swapon`, so there is nothing to flash.
  The module also carried a nested 136 KB `addon/keycheck` binary and targeted
  Android 7+, while DebDroid supports Android 5 and later.

## Swap

DebDroid's swap support reimplements what
[janithcooray's swap module](https://github.com/janithcooray/lin_os_swap_mod)
does, as `command/swap.sh` plus the `CONFIG_ENV_SWAP*` options.

This is an independent implementation rather than a port: the module ships no
license file, and the mechanism it uses — `dd`, `mkswap`, `swapon` and a
sysctl — is ordinary Linux administration rather than creative expression. The
module is credited for showing which mechanism works on android, and
particularly for its boot sentinel, which DebDroid's boot hook adopts.

Two details were taken deliberately:

- The swapfile is written out with `dd` rather than preallocated, because
  `swapon` refuses a file containing holes or unwritten extents.
- A sentinel file is written before enabling swap at boot and removed
  afterwards, so a failed attempt stands down on the next boot instead of
  repeating itself on a device that may not survive it.

The module's `util.sh` credited several XDA authors — Kingsman44, chainfire,
ianmacd, someone755 and Zappo — for volume-key menu code used by the Magisk
installer. None of that code is used here; it is recorded so its removal is
visible rather than silent.

## Licensing

The DebDroid scripts are MIT licensed. Some bundled binaries are GPL-2.0, and
**redistributing them carries an obligation to offer their source.** Shipping
them alongside MIT scripts does not change DebDroid's own license, because they
are separate programs that are executed, not linked into anything.

- Full license texts are in [licenses/](licenses/).
- For each GPL binary, the manifest records the exact upstream and the recipe
  that produces it, which is how the source offer is satisfied.

If anything here is inaccurate or incomplete, that is a bug worth reporting.
