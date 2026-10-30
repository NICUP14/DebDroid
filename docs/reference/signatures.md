[← Reference](README.md)

# Signatures

DebDroid links to apps rather than bundling them. That is only an improvement
if you can check that what you installed is what you meant to install.

## What a signature proves

Every Android app is signed with its developer's key. Android checks that the
signature is valid, and refuses to replace an installed app with one signed by
a different key.

So a matching fingerprint means **"same publisher as before"**. It does not
mean "safe", and it does not mean "the author you had in mind" — only that the
key is the same one as last time.

That is still useful. It is what stops a modified build from silently replacing
a genuine one.

## Why F-Droid

F-Droid builds each app from its published source code and signs the result
itself. The binary is traceable to a public build log rather than to someone's
upload, and for many apps the build is reproducible, so independent parties can
confirm the binary matches the source.

A binary committed to a git repository — which is what DebDroid used to ship —
offers none of that. You would be trusting this project, and this project had
nothing to show you.

## Checking an APK

With the Android build tools:

```sh
apksigner verify --print-certs app.apk
```

Without them, `keytool` comes with any JDK:

```sh
keytool -printcert -jarfile app.apk
```

Both print a SHA-256 fingerprint. Compare it against the one on the app's
F-Droid page, under *Versions*.

### On the device

Most people will not have a desktop to hand. In the F-Droid app, open the app's
page, then *Versions*, and expand a version to see its signer fingerprint.
F-Droid itself refuses to install an update whose signature does not match what
is already installed.

## A worked example from this project

DebDroid used to ship two APKs. Examining them before removing them shows both
what a good case and a bad case look like.

**The terminal emulator checked out.** `TerminalEmulator.apk` was
`jackpal.androidterm` v1.0.70 from 2015, and its signature block was named
`META-INF/JACKPAL_.RSA` — the upstream author's own key, matching what the
project published. Nothing was wrong with it; it was removed because an app
store is a better delivery channel than a git repository.

**The keyboard did not.** `HackersKeyboard.apk` had the right package name,
`org.pocketworkstation.pckeyboard`, but three things did not fit:

- It contained `META-INF/android.arch.*.version` marker files from Android
  Architecture Components, a library that postdates Hacker's Keyboard's last
  upstream release by years. The original build could not have contained them.
- Every file timestamp inside it was zeroed to `1980-00-00`, which is what
  repackaging tools produce, not a normal build.
- Its signature block was a generic `CERT.RSA` rather than a named author key.

None of that proves it was malicious. It does mean nobody could say where the
file came from — and a keyboard sees every keystroke on the device, including
the password you type at `su`. That is reason enough.

**The lesson is the pattern, not the file:** a package name that looks right
tells you very little. The signer and the build metadata are what carry
information.

## Checking DebDroid's own files

Bundled binaries, against the recorded checksums:

```sh
sh tools/verify-provenance.sh
```

A downloaded release:

```sh
sha256sum DebDroid-1.2.7.tar.gz
```

What those checks do and do not establish is set out in
[integrity](integrity.md), including the fact that checksums in a git
repository are not signatures.

## Recommended apps and their sources

| App | Package | Install from |
| --- | --- | --- |
| Term One Plus | `com.termoneplus` | [F-Droid](https://f-droid.org/packages/com.termoneplus/) |
| Android Terminal Emulator | `jackpal.androidterm` | [F-Droid](https://f-droid.org/packages/jackpal.androidterm/) |
| Hacker's Keyboard | `org.pocketworkstation.pckeyboard` | [F-Droid](https://f-droid.org/packages/org.pocketworkstation.pckeyboard/) |

Fingerprints are deliberately not reproduced here. A fingerprint copied into
documentation goes stale when a key is rotated, and a stale fingerprint is
worse than none: it teaches people to ignore a mismatch. Read it from the
F-Droid page, which is authoritative.
