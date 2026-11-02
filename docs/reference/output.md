[← Docs](../README.md)

# Output Conventions

Shared by every script through `debdroid_ui.sh`.

## Levels

| Prefix | Colour | Meaning |
| --- | --- | --- |
| `:: ` | bold | A section: starting or stopping something |
| ` -> ` | plain | A step within a section |
| `Warning:` | purple | Something failed, but the environment can still run |
| `Error:` | red | The environment cannot continue; it is stopped and cleaned up |

Warnings and errors go to stderr, everything else to stdout.

Details follow a warning or error, indented four spaces. Captured output from
`e2fsck`, `mount`, `umount` and `resize2fs` is passed as a detail and only
shown when something actually goes wrong, which keeps a normal startup quiet.

## Colour

Colour is emitted only when stdout and stderr are both terminals, `NO_COLOR` is
unset, and busybox is executable:

```sh
NO_COLOR=1 sh /sdcard/debdroid/debdroid.sh
```

Escape codes come from `busybox printf`, because the shells on very old android
versions have no `printf` builtin.

## Wording

- Values are quoted: `Cannot locate the debian image: "/sdcard/..."`.
- Sentences end with a period.
- The image is called "the debian image", never "the rootfs" or "the img".
- An error says what to do next where there is something to do, which is why
  most carry a hint line.

## Test suite output

`tests/assert.sh` uses its own levels, so a check's result lines up with the
others at a glance:

```txt
 PASS the session umask is 0022
 FAIL the host's /dev was not modified
      The session created entries in android's real /dev:
 SKIP the kernel supports overlayfs
      Not supported, so /dev falls back to a bind mount.
 INFO /data filesystem: ext4
```

`SKIP` is not a failure: it means the check could not run, usually because the
device's kernel lacks an optional feature. `INFO` reports context that a bug
report needs rather than asserting anything.
