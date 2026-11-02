[← Docs](../README.md)

# First Steps

You have DebDroid installed. This page gets you from there to a working Debian
you can actually use.

## Open a session

```bash
su
sh /sdcard/debdroid/debdroid.sh
```

You are now root inside Debian. The prompt changes, and commands like `uname -a`
report Linux rather than Android.

To leave:

```bash
exit
```

> [!IMPORTANT]
> Leaving stops everything inside Debian, including background programs. That
> is fine while you are exploring. Once you want to run a server, read
> [keep it running](keep-it-running.md) first.

## Install something

Inside the session:

```bash
apt update
apt install neofetch
neofetch
```

If `apt update` fails to reach anything, see
[ping or apt says permission denied](problems.md#ping-or-apt-says-permission-denied).

Anything in the Debian archive works here, and you get the real package rather
than a rebuilt one.

## Make yourself a user

Running as root all the time is a bad habit, and some programs refuse to run as
root at all. Create a normal user:

```bash
su
sh /sdcard/debdroid/debdroid.sh command user
```

It asks for a name and a password, then puts the user in the groups it needs.
That includes the one Android requires before any program can use the network,
which is easy to miss when doing it by hand.

Then, inside a session:

```bash
su - yourname
```

## Run one command without opening a session

Useful for scripts:

```bash
su
sh /sdcard/debdroid/debdroid.sh run apt update
```

Quotes work as you would expect, so this does the right thing:

```bash
sh /sdcard/debdroid/debdroid.sh run apt install 'package one' 'package two'
```

## Check everything is healthy

```bash
su
sh /sdcard/debdroid/tests/device.sh
```

This reads your setup and reports anything wrong: missing programs, a damaged
Debian install, features your device lacks. It changes nothing, so it is safe
to run whenever. Run it now so you know what a healthy result looks like.

## Where things are

| Path | What it holds |
| --- | --- |
| `/sdcard/debdroid` | Scripts, commands and the Debian install |
| `/sdcard/debdroid/environment` | Settings that apply inside Debian |
| `/data/local/debdroid` | Programs DebDroid needs |

Inside a session, your phone's storage is at `/sdcard`, so you can move files
between Android and Debian freely.

---

## What next

- **A server you can reach from your laptop:** [connect from your computer](remote-access.md)
- **A full desktop:** [run a desktop](desktop.md)
- **Running out of space:** [manage storage](storage.md)
- **Keeping it running after you close the terminal:** [keep it running](keep-it-running.md)
