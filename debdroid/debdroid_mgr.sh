#!/system/bin/sh

# Debdroid Manager configuration parameters
CONFIG_DEV_FD=yes
CONFIG_DEV_IO=yes
CONFIG_DEV_LOOP=yes

# How /dev is provided, tried in this order; the bind mount is always the last
# resort, as it shares android's real /dev with the session
CONFIG_DEV_OVERLAY=yes
CONFIG_DEV_TMPFS=yes
CONFIG_DEBDROID_BIN=yes
CONFIG_DEBDROID_LIB=yes
CONFIG_ENV_FSCK=yes
CONFIG_ENV_APEX=yes # Needed by android's own tools on android 10 and later
CONFIG_ENV_SHMMAX=268435456 # 250MB
CONFIG_ENV_DNSSERVER=1.1.1.1 # Fallback dns server (Cloudflare)

# Holds a wakelock for the session, so the device keeps serving while asleep
CONFIG_ENV_WAKELOCK=yes

# Enables a swapfile for the session, created by "debdroid.sh command swap"
# NOTE: Off by default, as the swapfile takes gigabytes of internal storage.
CONFIG_ENV_SWAP=no
CONFIG_ENV_SWAPPINESS=60 # How eagerly the kernel swaps [0 - 100]
CONFIG_ENV_SWAP_PRIO=    # Swap priority, empty places it below any zram

# Loads the output helpers and shared functions, which live next to this script
case "$0" in
    */*) DEBDROIDMGR_DIR="${0%/*}" ;;
    *)   DEBDROIDMGR_DIR=. ;;
esac
for file in debdroid_ui.sh debdroid_lib.sh; do
    if [ ! -f "$DEBDROIDMGR_DIR/$file" ]; then
        echo "Error: Missing required file: $DEBDROIDMGR_DIR/$file" >&2
        exit 1
    fi
    # shellcheck disable=SC1090
    . "$DEBDROIDMGR_DIR/$file"
done

# Mounts a filesystem under the environment, unless it is already mounted
# Sets "mount_output" to the mount command's output
# Usage: mount_fs <path-in-env> <mount-args...>
mount_fs()
{
    target="$DEBDROIDMGR_ENV/$1"
    shift
    mkdir -p "$target"
    mount_output=
    $BUSYBOX mountpoint -q "$target" || mount_output=$($BUSYBOX mount "$@" "$target" 2>&1)
}

# Mounts a filesystem the environment cannot run without
mount_required()
{
    if ! mount_fs "$@"; then
        error "Failed to mount \"/$1\"." "$mount_output"
        exit 1
    fi
}

# Mounts a filesystem the environment can run without
mount_optional()
{
    mount_fs "$@" || warn "Failed to mount \"/$1\", continuing without it." "$mount_output"
}

# Checks if the script runs in the host's mount namespace
in_host_namespace()
{
    mnt=$($BUSYBOX readlink /proc/self/ns/mnt | $BUSYBOX cut -d ':' -f 2)
    [ "$DEBDROIDMGR_MNT" = "$mnt" ]
}

# Sends a signal to every other process in the environment's mount namespace
# Sets "signaled" to the number of processes that received the signal
signal_environment()
{
    signaled=0
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        [ "$pid" = "$$" ] && continue
        if [ "$proc"/ns/mnt -ef /proc/$$/ns/mnt ]; then
            $BUSYBOX kill -"$1" "$pid" 2> /dev/null && signaled=$((signaled + 1))
        fi
    done
}

# Kills the processes left running in the environment (i.e. daemons)
kill_environment()
{
    # Signaling the host's mount namespace would kill the whole system
    if in_host_namespace; then
        error "Refusing to kill processes in the host's namespace."
        return 1
    fi

    signal_environment 0
    [ "$signaled" -eq 0 ] && return 0
    step "Stopping $signaled leftover process(es)"

    # Asks processes to terminate, then forces them after a grace period
    for signal in TERM KILL; do
        signal_environment "$signal"
        for _ in 1 2 3 4 5; do
            signal_environment 0
            [ "$signaled" -eq 0 ] && return 0
            $BUSYBOX sleep 1
        done
    done

    warn "Failed to kill $signaled process(es) in the environment."
    return 1
}

# Prints the mount points under the environment, most recent first
environment_mounts()
{
    $BUSYBOX awk -v env="$DEBDROIDMGR_ENV" \
        '$2 == env || index($2, env "/") == 1 { print $2 }' /proc/self/mounts | $BUSYBOX tac
}

# Takes an android wakelock, so the device keeps serving while the screen is off
# NOTE: Without it the device deep-sleeps and stops answering ssh and vnc.
acquire_wakelock()
{
    [ "$CONFIG_ENV_WAKELOCK" = "yes" ] || return 0

    if [ ! -w /sys/power/wake_lock ]; then
        warn "Wakelocks are unavailable, the device may sleep while serving." \
            "The kernel does not provide \"/sys/power/wake_lock\"."
        return 0
    fi

    step "Taking a wakelock"
    echo "$DEBDROID_WAKELOCK" > /sys/power/wake_lock 2>/dev/null
}

# Releases the wakelock taken by acquire_wakelock
release_wakelock()
{
    [ "$CONFIG_ENV_WAKELOCK" = "yes" ] || return 0
    [ -w /sys/power/wake_unlock ] || return 0

    echo "$DEBDROID_WAKELOCK" > /sys/power/wake_unlock 2>/dev/null
}

# Enables the swapfile prepared by the swap command
# NOTE: Creating it is slow, so it never happens during startup.
enable_swap()
{
    [ "$CONFIG_ENV_SWAP" = "yes" ] || return 0

    if [ ! -f "$DEBDROID_SWAP" ]; then
        warn "Swap is enabled but no swapfile exists, continuing without it." \
            "Create it first: \"debdroid.sh command swap\""
        return 0
    fi

    if [ ! -f /proc/swaps ]; then
        warn "The kernel does not support swap, continuing without it."
        return 0
    fi

    # Already active from a previous session or from the boot hook
    $BUSYBOX grep -q "^$DEBDROID_SWAP " /proc/swaps 2>/dev/null && return 0

    step "Enabling swap"
    $BUSYBOX sysctl -w vm.swappiness="$CONFIG_ENV_SWAPPINESS" > /dev/null 2>&1

    # NOTE: Most devices already run zram, which is far faster than a file on
    #       internal storage, so the file is placed below it by default.
    swap_priority=$CONFIG_ENV_SWAP_PRIO
    if [ -z "$swap_priority" ]; then
        swap_priority=-2
    fi

    if ! swap_output=$($BUSYBOX swapon -p "$swap_priority" "$DEBDROID_SWAP" 2>&1); then
        warn "Failed to enable swap, continuing without it." "$swap_output"
    fi
}

# Disables the swapfile this session enabled
disable_swap()
{
    [ "$CONFIG_ENV_SWAP" = "yes" ] || return 0
    [ -f "$DEBDROID_SWAP" ] || return 0
    $BUSYBOX grep -q "^$DEBDROID_SWAP " /proc/swaps 2>/dev/null || return 0

    step "Disabling swap"
    $BUSYBOX swapoff "$DEBDROID_SWAP" > /dev/null 2>&1
}

# Keeps the environment mounted until the supervisor is signaled to stop
supervise_environment()
{
    # Records the pid and namespace, which attach and stop both need
    supervisor_mnt=$($BUSYBOX readlink /proc/self/ns/mnt | $BUSYBOX cut -d ':' -f 2)
    echo "$$ $supervisor_mnt" > "$DEBDROIDMGR_PID"
    chmod 644 "$DEBDROIDMGR_PID"

    # Runs the bootstrap once here, so attaching later skips it entirely
    step "Bootstrapping the environment"
    $BUSYBOX chroot "$DEBDROIDMGR_ENV" /bin/su - -c \
        "umask 022 && sh /debdroid/bin/debinit" > /dev/null 2>&1

    section "The environment is ready"
    step "Attach to it with \"debdroid.sh attach\""
    step "Stop it with \"debdroid.sh stop\""

    # Waits to be signaled, without ever blocking the traps
    # NOTE: A POSIX shell runs no trap while a foreground command is executing,
    #       so a plain "sleep" would delay "stop" for its whole duration. The
    #       sleep runs in the background instead, as "wait" is interruptible.
    while :; do
        $BUSYBOX sleep 60 &
        wait
    done
}

# Truncates a configuration file inside the image, ready to be written
# Usage: write_env_file <path-in-env>
# NOTE: The file is unlinked first rather than truncated in place. A debian
#       image can ship /etc/resolv.conf as an absolute symlink, which
#       systemd-resolved does, and writing through it would land on the host's
#       real /etc instead of inside the image. Unlinking removes the symlink
#       itself, never its target.
write_env_file()
{
    $BUSYBOX rm -f "$DEBDROIDMGR_ENV/$1"
    true > "$DEBDROIDMGR_ENV/$1"

    # An existing file keeps its old mode when truncated, so set it explicitly
    chmod 644 "$DEBDROIDMGR_ENV/$1"
}

# Populates the session's own /dev with the nodes debian expects to find
# NOTE: This runs after /dev is mounted, never before. Creating these in the
#       host's real /dev is what earlier versions did, which left /dev/fd,
#       /dev/std* and /dev/loopN behind in android itself.
populate_dev()
{
    dev_root="$DEBDROIDMGR_ENV"/dev

    # Links the standard streams to their file descriptor
    if [ "$CONFIG_DEV_FD" = "yes" ] && [ ! -e "$dev_root"/fd ]; then
        ln -s /proc/self/fd "$dev_root"/fd
    fi
    if [ "$CONFIG_DEV_IO" = "yes" ]; then
        [ ! -e "$dev_root"/stdin ]  && ln -s /proc/self/fd/0 "$dev_root"/stdin
        [ ! -e "$dev_root"/stdout ] && ln -s /proc/self/fd/1 "$dev_root"/stdout
        [ ! -e "$dev_root"/stderr ] && ln -s /proc/self/fd/2 "$dev_root"/stderr
    fi

    # Links the loopback devices, which android keeps under /dev/block
    if [ "$CONFIG_DEV_LOOP" = "yes" ]; then
        for idx in $($BUSYBOX seq 0 7); do
            [ -e "$dev_root"/loop"$idx" ] && continue
            [ -e "$dev_root"/block/loop"$idx" ] || continue
            ln -s /dev/block/loop"$idx" "$dev_root"/loop"$idx"
        done
    fi

    return 0
}

# Copies the device nodes a debian userland cannot start without
# NOTE: Used by the tmpfs fallback, where /dev starts out empty.
clone_dev_nodes()
{
    dev_root="$DEBDROIDMGR_ENV"/dev

    # NOTE: "cp -a" on a device node recreates the node itself rather than
    #       copying its contents, which is exactly what is wanted here.
    for node in null zero full random urandom tty ptmx console; do
        [ -e /dev/"$node" ] || continue
        [ -e "$dev_root"/"$node" ] && continue
        $BUSYBOX cp -a /dev/"$node" "$dev_root"/ 2> /dev/null
    done

    # The loop devices live in a subdirectory the image does not provide
    if [ -d /dev/block ]; then
        mkdir -p "$dev_root"/block
        for idx in $($BUSYBOX seq 0 7); do
            [ -e /dev/block/loop"$idx" ] || continue
            $BUSYBOX cp -a /dev/block/loop"$idx" "$dev_root"/block/ 2> /dev/null
        done
    fi
}

# Mounts /dev, trying the three approaches in order of isolation
# NOTE: An overlay gives the session a writable /dev without touching android's.
#       A tmpfs is the next best thing. A bind mount shares android's real /dev,
#       so anything written lands in the running system, which is why it is last.
mount_dev()
{
    mkdir -p "$DEBDROIDMGR_ENV"/dev

    # The overlay's upper and work directories must live on the same filesystem,
    # so both are subdirectories of one tmpfs
    # NOTE: Earlier versions put the upper dir on a tmpfs and the work dir on the
    #       image, which the kernel always rejected, so the overlay never once
    #       mounted and the bind fallback was taken every time.
    if [ "$CONFIG_DEV_OVERLAY" = "yes" ]; then
        mkdir -p "$DEBDROIDMGR_ENV"/mnt/dev-ovl
        if mount_fs mnt/dev-ovl -t tmpfs -o mode=755 tmpfs; then
            # NOTE: The overlay takes its mode from the upper directory, so 755
            #       it is: a 700 upper would hide /dev from every non-root user.
            mkdir -p "$DEBDROIDMGR_ENV"/mnt/dev-ovl/upper \
                     "$DEBDROIDMGR_ENV"/mnt/dev-ovl/work
            chmod 755 "$DEBDROIDMGR_ENV"/mnt/dev-ovl/upper
            chmod 700 "$DEBDROIDMGR_ENV"/mnt/dev-ovl/work

            if $BUSYBOX mount -t overlay overlay \
                    -o lowerdir=/dev,upperdir="$DEBDROIDMGR_ENV"/mnt/dev-ovl/upper,workdir="$DEBDROIDMGR_ENV"/mnt/dev-ovl/work \
                    "$DEBDROIDMGR_ENV"/dev > /dev/null 2>&1; then
                populate_dev
                return 0
            fi

            # Releases the tmpfs, as the overlay it was prepared for failed
            $BUSYBOX umount "$DEBDROIDMGR_ENV"/mnt/dev-ovl > /dev/null 2>&1
        fi
        rm -rf "$DEBDROIDMGR_ENV"/mnt/dev-ovl
    fi

    # A private tmpfs still keeps the session out of android's /dev
    if [ "$CONFIG_DEV_TMPFS" = "yes" ]; then
        if $BUSYBOX mount -t tmpfs -o rw,mode=755,nosuid tmpfs \
                "$DEBDROIDMGR_ENV"/dev > /dev/null 2>&1; then
            warn "Overlay is unavailable, using a private tmpfs for /dev." \
                "Android's own /dev stays untouched."
            clone_dev_nodes
            populate_dev
            return 0
        fi
    fi

    # Last resort: the session shares android's real /dev
    warn "Falling back to a bind-mount for /dev." \
        "The session shares android's /dev, so changes to it are system-wide."
    mount_required dev --bind /dev
    populate_dev
}

# Mounts the chroot environment
start_environment()
{
    # Disables mount propagation
    $BUSYBOX mount --make-rprivate /

    # Ensures DEBDROIDMGR_IMG is a regular file
    if [ ! -f "$DEBDROIDMGR_IMG" ]; then
        error "Cannot locate the debian image: \"$DEBDROIDMGR_IMG\""
        exit 1
    fi

    # Ensures DEBDROIDMGR_ENV is not a symlink
    if [ -L "$DEBDROIDMGR_ENV" ]; then
        error "Refusing to operate on a symlinked environment."
        exit 1
    fi

    # Attempts image repair
    if [ "$CONFIG_ENV_FSCK" = "yes" ]; then
        step "Checking the debian image"
        fsck_output=$("$DEBDROIDMGR_BIN"/e2fsck -fp "$DEBDROIDMGR_IMG" 2>&1)
        fsck_status=$?

        # Exit codes 1 and 2 mean that errors were found and corrected
        if [ "$fsck_status" -ge 4 ]; then
            error "Failed to repair the debian image." "$fsck_output" \
                "Repair it manually: \"$DEBDROIDMGR_BIN/e2fsck -f $DEBDROIDMGR_IMG\""
            exit 1
        elif [ "$fsck_status" -ne 0 ]; then
            warn "Repaired errors in the debian image." "$fsck_output" \
                "The environment was probably not stopped properly last time."
        fi
    fi

    # Prepares the image mountpoint
    step "Mounting the debian image"
    mkdir -p "$DEBDROIDMGR_ENV"
    if ! mount_output=$($BUSYBOX mount -o loop "$DEBDROIDMGR_IMG" "$DEBDROIDMGR_ENV" 2>&1); then
        error "Failed to mount the debian image." "$mount_output" \
            "Loop mounts can fail sometimes, rerun the script."
        exit 1
    fi

    # Ensures DEBDROIDMGR_ENV is a valid filesystem
    if [ ! -f "$DEBDROIDMGR_ENV/bin/sh" ]; then
        error "The debian image does not contain a valid linux filesystem."
        exit 1
    fi

    # Mounts the /proc filesystem
    step "Mounting system filesystems"
    mount_required proc -t proc /proc

    # Mounts the /dev filesystem, preferring a layer the session can write to
    mount_dev

    # Mounts the /dev/pts filesystem
    mount_required dev/pts -t devpts devpts

    # Mounts the /dev/shm filesystem
    mount_optional dev/shm -o rw,nosuid,nodev,noexec,mode=1777 -t tmpfs tmpfs

    # Mounts the /sys filesystem
    mount_optional sys -r -t sysfs /sys

    # Mounts the /tmp filesystem
    mount_optional tmp -t tmpfs -o rw,nosuid,nodev,mode=1777,size=64M tmpfs

    # Mounts the /system filesystem
    mount_optional system -r /system

    # Mounts /apex, where android 10 and later keep bionic and its libraries
    # NOTE: Without it android's own tools (am, settings, svc) cannot run from
    #       inside the chroot on a modern device, as their linker lives there.
    if [ "$CONFIG_ENV_APEX" = "yes" ] && [ -d /apex ]; then
        mount_optional apex --bind /apex
    fi

    # Mounts the /sdcard filesystem
    mount_optional sdcard --bind /sdcard

    # Mounts the /debdroid/bin directory (required, as it contains debinit)
    # NOTE: /debdroid stays visible in the chroot, so a 700 umask would hide the libraries from non-root users
    mkdir -p "$DEBDROIDMGR_ENV"/debdroid/bin
    chmod 755 "$DEBDROIDMGR_ENV"/debdroid
    [ "$CONFIG_DEBDROID_BIN" = "yes" ] && mount_required debdroid/bin --bind "$DEBDROIDMGR_BIN"

    # Mounts and preloads libs in the /debdroid/lib directory
    mkdir -p "$DEBDROIDMGR_ENV"/debdroid/lib
    write_env_file etc/ld.so.preload
    if [ "$CONFIG_DEBDROID_LIB" = "yes" ]; then
        mount_optional debdroid/lib --bind "$DEBDROIDMGR_LIB"

        # Registers debdroid libraries in /etc/ld.so.preload
        for lib in "$DEBDROIDMGR_ENV"/debdroid/lib/*.so*; do
            [ -f "$lib" ] || continue # Ensures that library exists
            echo /debdroid/lib/"${lib##*/}" >> "$DEBDROIDMGR_ENV"/etc/ld.so.preload
        done
    fi

    # Grows the system's shared memory capacity
    step "Configuring the environment"
    $BUSYBOX sysctl -w kernel.shmmax="$CONFIG_ENV_SHMMAX" > /dev/null 2>&1

    # Keeps the device awake and gives it room to swap while it serves
    acquire_wakelock
    enable_swap

    # Creates the /etc/resolv.conf file
    write_env_file etc/resolv.conf
    for server in 1 2 3 4; do
        [ -z "$($BUSYBOX getprop net.dns$server)" ] && break
        echo "nameserver $($BUSYBOX getprop net.dns$server)" >> "$DEBDROIDMGR_ENV"/etc/resolv.conf
    done

    # Creates a fallback for the /etc/resolv.conf file
    if [ ! -s "$DEBDROIDMGR_ENV"/etc/resolv.conf ]; then
        warn "No DNS servers found, falling back to nameserver: \"$CONFIG_ENV_DNSSERVER\""
        echo "nameserver $CONFIG_ENV_DNSSERVER" >> "$DEBDROIDMGR_ENV"/etc/resolv.conf
    fi

    # Creates the /etc/hosts file
    write_env_file etc/hosts
    echo "127.0.0.1     localhost $($BUSYBOX hostname)" >> "$DEBDROIDMGR_ENV"/etc/hosts
    echo "::1           localhost ip6-localhost ip6-loopback" >> "$DEBDROIDMGR_ENV"/etc/hosts
}

# Unmounts the chroot environment
stop_environment()
{
    echo
    section "Stopping environment: $DEBDROIDMGR_ENV"

    # Lets the device sleep again and gives back the swapfile
    release_wakelock
    disable_swap

    # The pid file is only a hint for attach, so it goes before anything can fail
    $BUSYBOX rm -f "$DEBDROIDMGR_PID"

    # Processes left running keep the filesystems busy
    kill_environment

    # Unmounts nested filesystems before their parents (includes user mounts)
    step "Unmounting filesystems"
    for mount_point in $(environment_mounts); do
        [ "$mount_point" = "$DEBDROIDMGR_ENV" ] && continue
        $BUSYBOX umount "$mount_point" > /dev/null 2>&1 || $BUSYBOX umount -l "$mount_point" > /dev/null 2>&1
    done

    # Unmounts the root filesystem (never lazily, as it would hide a busy image)
    if $BUSYBOX mountpoint -q "$DEBDROIDMGR_ENV" && ! umount_output=$($BUSYBOX umount "$DEBDROIDMGR_ENV" 2>&1); then
        error "Failed to unmount the debian image, it is still in use." "$umount_output" \
            "Reboot the device before starting the environment again to prevent image corruption."
    fi
}

# Waits for a detached supervisor to report that the environment is ready
# NOTE: Startup includes an fsck, which takes a while on a large image, so the
#       wait is generous but gives up as soon as the log shows a failure.
wait_for_supervisor()
{
    for _ in $($BUSYBOX seq 1 300); do
        if [ -f "$DEBDROIDMGR_PID" ]; then
            step "Started"
            step "Attach to it with \"debdroid.sh attach\""
            return 0
        fi

        # Startup failed, so the reason is already in the log
        if $BUSYBOX grep -q '^Error:' "$DEBDROIDMGR_LOG" 2> /dev/null; then
            error "Failed to start the background environment." \
                "$($BUSYBOX cat "$DEBDROIDMGR_LOG")"
            return 1
        fi

        $BUSYBOX sleep 1
    done

    error "The background environment did not start in time." \
        "Inspect it: \"cat $DEBDROIDMGR_LOG\""
    return 1
}

# Attaches a session to the environment a supervisor already holds open
# NOTE: Only the mount namespace is entered, as android devices support no
#       other namespace. The bootstrap is skipped, as the supervisor ran it.
attach_environment()
{
    if ! read_supervisor "$DEBDROIDMGR_PID"; then
        error "No background environment is running." \
            "Start one with \"debdroid.sh start\""
        return 1
    fi

    # Defines the chroot command, defaulting to a login shell
    [ $# -eq 0 ] && set -- /bin/su
    shell_quote "$@"

    $BUSYBOX nsenter --mount --target "$supervisor_pid" -- \
        "$BUSYBOX" chroot "$DEBDROIDMGR_ENV" /bin/su - -c "umask 022 && $DEBDROID_QUOTED"
}

# Stops the background environment by signaling its supervisor
# NOTE: The supervisor's own traps do the cleanup, so there is none here.
stop_background()
{
    if ! read_supervisor "$DEBDROIDMGR_PID"; then
        # Clears a stale pid file, so the next start is not misled by it
        $BUSYBOX rm -f "$DEBDROIDMGR_PID"
        error "No background environment is running."
        return 1
    fi

    section "Stopping the background environment"
    step "Signaling the supervisor (pid $supervisor_pid)"
    $BUSYBOX kill -TERM "$supervisor_pid" 2> /dev/null

    # Waits for the supervisor to finish unmounting before reporting success
    for _ in $($BUSYBOX seq 1 60); do
        [ -d /proc/"$supervisor_pid" ] || break
        $BUSYBOX sleep 1
    done

    if [ -d /proc/"$supervisor_pid" ]; then
        error "The supervisor did not stop." \
            "Inspect it: \"cat $DEBDROIDMGR_LOG\""
        return 1
    fi

    step "Stopped"
}

# Reports whether an environment is running and what state it is in
report_status()
{
    section "DebDroid status"

    if read_supervisor "$DEBDROIDMGR_PID"; then
        step "Background environment: running (pid $supervisor_pid)"
        step "Log file: $DEBDROIDMGR_LOG"

        mounts=$($BUSYBOX awk -v env="$DEBDROIDMGR_ENV" \
            '$2 == env || index($2, env "/") == 1' /proc/"$supervisor_pid"/mounts |
            $BUSYBOX wc -l)
        step "Mounted filesystems: $mounts"
    elif ( $BUSYBOX flock -n 9 ) 9> "$DEBDROIDMGR_LOCK" 2> /dev/null; then
        step "Background environment: stopped"
        step "No session is using the debian image"
        return 0
    else
        step "Background environment: stopped"
        step "A foreground session is using the debian image"
        return 0
    fi

    # Counts the processes sharing the supervisor's namespace
    processes=0
    for proc in /proc/[0-9]*; do
        [ "$proc"/ns/mnt -ef /proc/"$supervisor_pid"/ns/mnt ] &&
            processes=$((processes + 1))
    done
    step "Processes in the environment: $processes"

    if [ "$CONFIG_ENV_WAKELOCK" = "yes" ] && [ -r /sys/power/wake_lock ]; then
        if $BUSYBOX grep -qw "$DEBDROID_WAKELOCK" /sys/power/wake_lock 2>/dev/null; then
            step "Wakelock: held"
        else
            step "Wakelock: not held"
        fi
    fi

    if [ -f /proc/swaps ] && $BUSYBOX grep -q "^$DEBDROID_SWAP " /proc/swaps 2>/dev/null; then
        step "Swap: enabled"
    fi
}

# Prints HELP Usage
if [ $# -eq 0 ]; then
    echo "$0: A lightweight utility to create safe, isolated chroot environments."
    echo "Usage: $0 <img> <mount-point> <bin-dir> <lib-dir> (<command>)?"
    echo "Set DEBDROIDMGR_MODE to run (default), start, attach, stop or status."
    exit
fi

# Checks for popular busybox install locations
require_busybox

# Enables colored output
setup_colors

require_arch
require_root

# Checks for missing arguments
if [ $# -lt 4 ]; then
    error "Expected at least 4 arguments, but got $#." \
        "Usage: $0 <img> <mount-point> <bin-dir> <lib-dir> (<command>)?"
    exit 1
fi

# Configures parameters
DEBDROIDMGR_IMG=$1
DEBDROIDMGR_ENV=$2
DEBDROIDMGR_BIN=$3
DEBDROIDMGR_LIB=$4
DEBDROIDMGR_LOCK="$DEBDROIDMGR_ENV".lock
DEBDROIDMGR_PID="$DEBDROIDMGR_ENV".pid
DEBDROIDMGR_LOG="$DEBDROIDMGR_ENV".log
shift 4

# Derives the paths the frontend would otherwise have exported
# NOTE: This script sources no environment file, and its usage says it can be
#       run directly. Without these defaults an empty wakelock name would be
#       written to /sys/power/wake_lock, and the swap checks would test "".
#       An exported value still wins, so the frontend keeps overriding them.
: "${DEBDROID_WAKELOCK:=debdroid}"
: "${DEBDROID_SWAP:=${DEBDROIDMGR_BIN%/*}/swapfile}"

# Filled in by read_supervisor, which lives in debdroid_lib.sh
# NOTE: Declared here so the contract is visible: every use below is only valid
#       after a successful read_supervisor call.
supervisor_pid=
supervisor_mnt=

# Selects what to do with the environment, defaulting to a foreground session
: "${DEBDROIDMGR_MODE:=run}"

# These modes work from the host namespace, so they run before it is private
case "$DEBDROIDMGR_MODE" in
    attach)
        attach_environment "$@"
        exit $?
        ;;
    stop)
        stop_background
        exit $?
        ;;
    status)
        report_status
        exit $?
        ;;
    run|start) ;;
    *)
        error "Unknown mode: \"$DEBDROIDMGR_MODE\"" \
            "Expected run, start, attach, stop or status."
        exit 1
        ;;
esac

# Pass 1: Sets up the private mount namespace
if [ -z "$DEBDROIDMGR_MNT" ]; then
    # Records the mount namespace id
    DEBDROIDMGR_MNT=$($BUSYBOX readlink /proc/self/ns/mnt | $BUSYBOX cut -d ':' -f 2)
    export DEBDROIDMGR_MNT DEBDROIDMGR_MODE

    # Refuses to start a second background environment over a running one
    if [ "$DEBDROIDMGR_MODE" = "start" ] && read_supervisor "$DEBDROIDMGR_PID"; then
        error "A background environment is already running." \
            "Attach to it with \"debdroid.sh attach\", or stop it first."
        exit 1
    fi

    # Clears a pid file left behind by a supervisor that did not exit cleanly
    $BUSYBOX rm -f "$DEBDROIDMGR_PID"

    # Runs pass 2 inside a private mount namespace, with the lock file open as fd 9
    # NOTE: Cleanup happens in pass 2, the environment's mounts are invisible from here
    # NOTE: POSIX leaves it unspecified whether fds opened by a bare "exec" reach other programs
    # NOTE: "$@" keeps the user's command intact, so quotes and spaces survive the hop
    if [ "$DEBDROIDMGR_MODE" = "start" ]; then
        # Detaches the supervisor, so it outlives the terminal that started it
        # NOTE: setsid gives it its own session, which is what stops a hangup
        #       from reaching it when the terminal closes.
        $BUSYBOX setsid "$BUSYBOX" unshare --mount sh "$0" \
            "$DEBDROIDMGR_IMG" "$DEBDROIDMGR_ENV" "$DEBDROIDMGR_BIN" "$DEBDROIDMGR_LIB" "$@" \
            9> "$DEBDROIDMGR_LOCK" > "$DEBDROIDMGR_LOG" 2>&1 &

        section "Starting the background environment"
        step "Log file: $DEBDROIDMGR_LOG"
        wait_for_supervisor
        exit $?
    fi

    $BUSYBOX unshare --mount sh "$0" "$DEBDROIDMGR_IMG" "$DEBDROIDMGR_ENV" "$DEBDROIDMGR_BIN" "$DEBDROIDMGR_LIB" "$@" \
        9> "$DEBDROIDMGR_LOCK"

    unset DEBDROIDMGR_MNT

# Pass 2: Sets up the linux filesystem
else
    # Defines the chroot command, defaulting to a login shell
    [ $# -eq 0 ] && set -- /bin/su

    # Quotes the command, as chroot runs it through a single "su -c" string
    shell_quote "$@"
    DEBDROIDMGR_EXEC=$DEBDROID_QUOTED

    # Ensures that further modifications run in a private mount namespace
    if in_host_namespace; then
        error "Failed to create a private mount namespace." \
            "Refusing to operate in the host's namespace: risk of system corruption."
        exit 1
    fi

    # Prevents mounting the image twice, which corrupts it (fd 9 is opened by pass 1)
    # NOTE: Processes spawned in the environment inherit the lock and hold it until they exit
    if ! $BUSYBOX flock -n 9; then
        error "The environment is already in use: \"$DEBDROIDMGR_ENV\"" \
            "Exit all running instances before starting a new one."
        exit 1
    fi

    # Makes the script trigger "stop_environment" on exit
    # NOTE: Signals only exit, so that the cleanup runs once and startup never resumes
    trap 'stop_environment' EXIT
    trap 'exit 1' HUP INT TERM QUIT PIPE

    section "Starting environment: $DEBDROIDMGR_ENV"
    start_environment

    # Holds the environment open for "attach" instead of running a session
    if [ "$DEBDROIDMGR_MODE" = "start" ]; then
        supervise_environment
        exit 0
    fi

    # Spawns a clean login session without inheriting env variables
    # NOTE: Resets the umask, as Android root shells often use 077, which makes new files private
    step "Running chroot command: \"$*\""
    echo
    $BUSYBOX chroot "$DEBDROIDMGR_ENV" /bin/su - -c "umask 022 && sh /debdroid/bin/debinit && $DEBDROIDMGR_EXEC"
fi
