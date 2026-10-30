[← Reference](README.md)

# Session Lifecycle

## Foreground session

`debdroid.sh run` goes through the manager in two passes.

### Pass 1, in android's namespace

1. Records the current mount namespace id in `DEBDROIDMGR_MNT` and exports it,
   which is how pass 2 knows it is somewhere else.
2. Re-executes itself under `busybox unshare --mount`, with the lock file
   opened as file descriptor 9 by a **per-command redirection**.

### Pass 2, in a private namespace

1. Refuses to continue if it is still in android's namespace. Operating there
   would make every mount system-wide.
2. Takes the lock with `flock -n 9`. A second session is refused here.
3. Sets the traps.
4. `start_environment`: checks the image, loop-mounts it, mounts the android
   filesystems, writes the config files, takes the wakelock, enables swap.
5. Runs the command through `chroot ... /bin/su - -c "umask 022 && ..."`.

### On exit

`stop_environment` runs from the EXIT trap:

1. Releases the wakelock and the swapfile, removes the pid file.
2. `kill_environment`: TERM to every process in the namespace, then KILL after
   a grace period.
3. Unmounts everything under the environment, read from `/proc/self/mounts` in
   reverse order so nested mounts go first.
4. Unmounts the image itself, never lazily — a lazy unmount would hide a still
   busy image and let the next session mount it again and corrupt it.

## Background session

`debdroid.sh start` uses the same two passes with two differences.

Pass 1 launches pass 2 **detached**, through `setsid`, with stdout and stderr
redirected to `mnt.log`, then waits for the pid file to appear before
reporting success. It gives up early if the log shows an error.

Pass 2 runs the same `start_environment`, then calls `supervise_environment`
instead of running a command: it writes its pid and namespace id to `mnt.pid`,
runs the bootstrap once, and blocks.

`attach` then enters that namespace with `nsenter --mount --target <pid>` and
chroots, skipping the check, the mounts and the bootstrap entirely — which is
why attaching is near-instant compared to a fresh `run`.

`stop` signals the supervisor, whose existing traps do all the teardown. There
is no second cleanup path.

## Design decisions worth not undoing

### The lock

Opened by a per-command redirection (`cmd 9> file`), never by a bare `exec 9>`:
POSIX leaves it unspecified whether descriptors above 2 opened by a bare `exec`
reach other programs.

Processes spawned in the session inherit descriptor 9, so the lock is held as
long as anything from the session is alive, and the kernel releases it when
they all exit. There are no stale locks to clean up.

Processes entered through `nsenter` do **not** inherit it, so attaching never
extends the lock. That is deliberate: the supervisor owns the lock, and
attached sessions come and go.

### The traps

```sh
trap 'stop_environment' EXIT
trap 'exit 1' HUP INT TERM QUIT PIPE
```

Signals only exit. The cleanup therefore runs exactly once, from the EXIT trap,
and startup never resumes after a signal. No "run once" guard is needed, and
adding one would be a mistake.

### Why the supervisor blocks the way it does

```sh
while :; do
    $BUSYBOX sleep 60 &
    wait
done
```

A POSIX shell runs no trap while a foreground command is executing. A plain
`sleep 60` would therefore delay `stop` by up to a minute. The sleep runs in
the background and `wait` is interruptible, so the signal lands immediately.

This looks like it could be simplified. It cannot.

### Why the pid file carries a namespace id

The kernel reuses process ids. A pid file left behind by a crashed supervisor
could name a live, unrelated process, and signalling it would kill something
the user never started.

So `mnt.pid` holds both the pid and the mount namespace id, and `attach` and
`stop` re-check that the pair still matches before acting. The lock remains the
authority on whether an environment is running; the pid file is only a hint for
`nsenter`.

### Why only a mount namespace

**This is the constraint that shapes everything else.** Android devices do not
support the other namespaces, which was established by testing across a range
of them. PID, user, network, IPC and UTS namespaces are not available.

Two consequences follow, and both look like workarounds because they are:

- **Process teardown scans `/proc`.** `signal_environment` compares every
  `/proc/<pid>/ns/mnt` against its own with `-ef`. With a PID namespace this
  would be unnecessary; without one, there is no alternative.
- **The supervisor is not PID 1 of anything.** It cannot reap orphans and must
  not try to. Reaching for `unshare --pid --fork` to make it a "proper init" is
  the obvious improvement and it does not work here.

Processes in a session are visible to android and share its network stack. That
is by design, not a gap.

CI enforces this: any `unshare` or `nsenter` flag other than `--mount` fails
the build.
