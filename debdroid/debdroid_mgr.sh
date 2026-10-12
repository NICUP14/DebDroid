#!/system/bin/sh

# Debdroid Manager configuration parameters
CONFIG_DEV_FD=yes
CONFIG_DEV_IO=yes
CONFIG_DEV_LOOP=yes
CONFIG_DEBDROID_BIN=yes
CONFIG_DEBDROID_LIB=yes
CONFIG_ENV_FSCK=yes
CONFIG_ENV_SHMMAX=268435456 # 250MB
CONFIG_ENV_DNSSERVER=1.1.1.1 # Fallback dns server (Cloudflare)

# Loads the output helpers, which live next to this script
case "$0" in
    */*) DEBDROIDMGR_UI="${0%/*}"/debdroid_ui.sh ;;
    *)   DEBDROIDMGR_UI=./debdroid_ui.sh ;;
esac
if [ ! -f "$DEBDROIDMGR_UI" ]; then
    echo "Error: Missing required file: $DEBDROIDMGR_UI" >&2
    exit 1
fi
# shellcheck disable=SC1090
. "$DEBDROIDMGR_UI"

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

# Mounts the chroot environment
start_environment()
{
    # Disables mount propagation
    $BUSYBOX mount --make-rprivate /

    # Links the standard streams to their file descriptor
    if [ "$CONFIG_DEV_FD" = "yes" ] && [ ! -e "/dev/fd" ]; then
        ln -s /proc/self/fd /dev/fd
    fi
    if [ "$CONFIG_DEV_IO" = "yes" ]; then
        [ ! -e "/dev/stdin" ]  && ln -s /proc/self/fd/0 /dev/stdin
        [ ! -e "/dev/stdout" ] && ln -s /proc/self/fd/1 /dev/stdout
        [ ! -e "/dev/stderr" ] && ln -s /proc/self/fd/2 /dev/stderr
    fi

    # Links /dev/block loopback devices under /dev
    if [ "$CONFIG_DEV_LOOP" = "yes" ]; then
        for idx in $($BUSYBOX seq 0 7); do
            [ ! -e /dev/loop"$idx" ] && ln -s /dev/block/loop"$idx" /dev/loop"$idx"
        done
    fi

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

    # Mounts the /dev filesystem (overlayed)
    mkdir -p "$DEBDROIDMGR_ENV"/dev "$DEBDROIDMGR_ENV"/mnt/dev-work
    mount_fs mnt/dev-upper -t tmpfs tmpfs
    chmod 700 "$DEBDROIDMGR_ENV"/mnt/dev-upper "$DEBDROIDMGR_ENV"/mnt/dev-work
    if ! $BUSYBOX mount -t overlay overlay \
            -o lowerdir=/dev,upperdir="$DEBDROIDMGR_ENV"/mnt/dev-upper,workdir="$DEBDROIDMGR_ENV"/mnt/dev-work \
            "$DEBDROIDMGR_ENV"/dev > /dev/null 2>&1; then
        warn "Overlay is not supported, falling back to a bind-mount for /dev."
        mount_required dev --bind /dev

        # Cleans the unused tmpfs
        $BUSYBOX umount "$DEBDROIDMGR_ENV"/mnt/dev-upper
        rm -rf "$DEBDROIDMGR_ENV"/mnt/dev-upper "$DEBDROIDMGR_ENV"/mnt/dev-work
    fi

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

    # Mounts the /sdcard filesystem
    mount_optional sdcard --bind /sdcard

    # Mounts the /debdroid/bin directory (required, as it contains debinit)
    mkdir -p "$DEBDROIDMGR_ENV"/debdroid/bin
    [ "$CONFIG_DEBDROID_BIN" = "yes" ] && mount_required debdroid/bin --bind "$DEBDROIDMGR_BIN"

    # Mounts and preloads libs in the /debdroid/lib directory
    mkdir -p "$DEBDROIDMGR_ENV"/debdroid/lib
    true > "$DEBDROIDMGR_ENV"/etc/ld.so.preload
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

    # Creates the /etc/resolv.conf file
    true > "$DEBDROIDMGR_ENV"/etc/resolv.conf
    for server in 1 2 3 4; do
        [ -z "$(getprop net.dns$server)" ] && break
        echo "nameserver $(getprop net.dns$server)" >> "$DEBDROIDMGR_ENV"/etc/resolv.conf
    done

    # Creates a fallback for the /etc/resolv.conf file
    if [ ! -s "$DEBDROIDMGR_ENV"/etc/resolv.conf ]; then
        warn "No DNS servers found, falling back to nameserver: \"$CONFIG_ENV_DNSSERVER\""
        echo "nameserver $CONFIG_ENV_DNSSERVER" >> "$DEBDROIDMGR_ENV"/etc/resolv.conf
    fi

    # Creates the /etc/hosts file
    true > "$DEBDROIDMGR_ENV"/etc/hosts
    echo "127.0.0.1     localhost $($BUSYBOX hostname)" >> "$DEBDROIDMGR_ENV"/etc/hosts
    echo "::1           localhost ip6-localhost ip6-loopback" >> "$DEBDROIDMGR_ENV"/etc/hosts
}

# Unmounts the chroot environment
stop_environment()
{
    echo
    section "Stopping environment: $DEBDROIDMGR_ENV"

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

# Prints HELP Usage
if [ $# -eq 0 ]; then
    echo "$0: A lightweight utility to create safe, isolated chroot environments."
    echo "Usage: $0 <img> <mount-point> <bin-dir> <lib-dir> (<command>)?"
    exit
fi

# Checks for popular busybox install locations
if [ -z "$BUSYBOX" ]; then
    if [ -x /sbin/busybox ]; then
        BUSYBOX=/sbin/busybox
    elif [ -x /system/bin/busybox ]; then
        BUSYBOX=/system/bin/busybox
    elif [ -x /system/xbin/busybox ]; then
        BUSYBOX=/system/xbin/busybox
    elif [ -x /data/local/bin/busybox ]; then
        BUSYBOX=/data/local/bin/busybox
    else
        error "Cannot locate the busybox binary." \
            "Manually set it using the \$BUSYBOX variable."
        exit 1
    fi
fi

# Enables colored output
setup_colors

# Checks for a matching architecture
ARCH=$(getprop ro.product.cpu.abi)
if [ "$ARCH" != "arm64-v8a" ]; then
    error "Unsupported architecture: \"$ARCH\"" \
        "This script only works on arm64-v8a (aarch64)."
    exit 1
fi

# Check if the user has root permissions 
if [ "$(id -u)" -ne 0 ]; then
    error "Missing required super-user permissions."
    exit 1
fi

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
shift 4

# NOTE: Remains unquoted as it has to preserve flags when passed to chroot
# shellcheck disable=SC2124
DEBDROIDMGR_EXEC=$@

# Pass 1: Sets up the private mount namespace
if [ -z "$DEBDROIDMGR_MNT" ]; then
    # Records the mount namespace id
    DEBDROIDMGR_MNT=$($BUSYBOX readlink /proc/self/ns/mnt | $BUSYBOX cut -d ':' -f 2)
    export DEBDROIDMGR_MNT

    # Runs pass 2 inside a private mount namespace, with the lock file open as fd 9
    # NOTE: Cleanup happens in pass 2, the environment's mounts are invisible from here
    # NOTE: POSIX leaves it unspecified whether fds opened by a bare "exec" reach other programs
    # shellcheck disable=SC2086
    $BUSYBOX unshare --mount sh "$0" "$DEBDROIDMGR_IMG" "$DEBDROIDMGR_ENV" "$DEBDROIDMGR_BIN" "$DEBDROIDMGR_LIB" $DEBDROIDMGR_EXEC \
        9> "$DEBDROIDMGR_LOCK"

    unset DEBDROIDMGR_MNT

# Pass 2: Sets up the linux filesystem
else
    # Defines the chroot command
    : "${DEBDROIDMGR_EXEC:=/bin/su}"

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

    # Spawns a clean login session without inheriting env variables
    step "Running chroot command: \"$DEBDROIDMGR_EXEC\""
    echo
    $BUSYBOX chroot "$DEBDROIDMGR_ENV" /bin/su - -c "sh /debdroid/bin/debinit && $DEBDROIDMGR_EXEC"
fi
