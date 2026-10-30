[← Guides](README.md)

# Will It Work on My Phone?

Four things to check. All four have to be true.

## 1. Is it rooted?

DebDroid needs root. Open a terminal app and type:

```bash
su
```

If the prompt changes to `#`, you have root. If it says "not found" or
"permission denied", DebDroid cannot run on this device.

This is the trade DebDroid makes: because it has root, it runs real Debian
programs instead of rebuilt ones, so almost everything in the Debian archive
works. Without root, that is not possible. If you are not rooted and do not
want to be, Termux is the better choice.

## 2. Is it a 64-bit ARM device?

Nearly every phone from the last decade is. To check:

```bash
getprop ro.product.cpu.abi
```

| It says | Status |
| --- | --- |
| `arm64-v8a` | Supported |
| `armeabi-v7a` | Recognised, but the programs for it are not built yet |
| `x86_64` or `x86` | Recognised, but the programs for it are not built yet |
| anything else | Not supported |

## 3. Is Android 5.0 or newer?

```bash
getprop ro.build.version.release
```

Android 5.0 (released 2014) is the floor. Newer is fine — DebDroid runs on
Android 14 too.

## 4. Is there enough free space?

1 GB is the minimum. 5 GB is comfortable. A desktop with a browser wants more.

```bash
df -h /data
```

## Apps you'll want

| App | Why | Install from |
| --- | --- | --- |
| A terminal | To type commands | [Term One Plus](https://f-droid.org/packages/com.termoneplus/) |
| A better keyboard | Phone keyboards lack Ctrl, Tab and arrow keys | [Hacker's Keyboard](https://f-droid.org/packages/org.pocketworkstation.pckeyboard/) |
| A file manager with root | To unzip the download | [MiXplorer](https://play.google.com/store/apps/details?id=com.mixplorer.silver) or similar |

These install from F-Droid, an app store that builds each app from its source
code and signs the result. That is a stronger guarantee than a file handed to
you by this project, which is why DebDroid links to them instead of bundling
them. If you want to verify what you installed, see
[signatures](../reference/signatures.md).

## Things worth knowing

**Battery.** Running Debian continuously keeps the device awake. Leave it
plugged in.

**Heat.** Phones have no fans. Sustained heavy work will make the device
throttle itself.

**Security.** Most devices old enough to be spare no longer get security
updates, so their kernels have known unpatched holes. Do not put one directly
on the internet, and do not keep anything sensitive on it. See
[security](../reference/security.md).

---

Ready? [Install DebDroid](install.md).
