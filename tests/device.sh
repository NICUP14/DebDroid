#!/system/bin/sh
# device.sh: Checks a real device for everything DebDroid depends on
#
# Run it before trusting an install, after an update, or when reporting a bug.
# The default run only reads: it never mounts the image, starts a session or
# writes to it, so it is safe on a device holding data you care about.
#
# Usage: sh /sdcard/debdroid/tests/device.sh [--session] [--help]
#
#   --session   Additionally start one real session and verify it end to end.
#               Refused while another session is running.

# Parses the options first, so the help works without an install
# NOTE: Only shell builtins are used here, as busybox is not located yet.
RUN_SESSION=no
for arg in "$@"; do
    case "$arg" in
        --session) RUN_SESSION=yes ;;
        --help|-h)
            echo "DebDroid device test suite"
            echo
            echo "Checks a real device for everything DebDroid depends on."
            echo "The default run only reads: it never mounts the image, starts"
            echo "a session or writes to it."
            echo
            echo "Usage: sh /sdcard/debdroid/tests/device.sh [--session]"
            echo
            echo "  --session   Additionally start one real session and verify"
            echo "              it end to end, then confirm it cleaned up."
            echo "              Refused while another session is running."
            echo
            echo "Set NO_COLOR=1 to disable colored output."
            exit 0
            ;;
        *)
            echo "Error: Unknown option: \"$arg\"" >&2
            echo "    Run \"$0 --help\" for usage." >&2
            exit 1
            ;;
    esac
done

# Loads the installed debdroid configuration and helpers
for file in debdroid_env.sh debdroid_ui.sh debdroid_lib.sh; do
    if [ ! -f /sdcard/debdroid/"$file" ]; then
        echo "Error: Missing required file: /sdcard/debdroid/$file" >&2
        echo "    Install DebDroid before running this suite." >&2
        exit 1
    fi
done

# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_env.sh
# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_lib.sh

# Loads the test helpers, which live next to this script
case "$0" in
    */*) DEVICE_DIR="${0%/*}" ;;
    *)   DEVICE_DIR=. ;;
esac
if [ ! -f "$DEVICE_DIR"/assert.sh ]; then
    echo "Error: Missing required file: $DEVICE_DIR/assert.sh" >&2
    exit 1
fi
# shellcheck disable=SC1090
. "$DEVICE_DIR"/assert.sh

if ! find_busybox; then
    echo "Error: Cannot locate the busybox binary." >&2
    exit 1
fi
test_setup_colors

echo "DebDroid device test suite (v$DEBDROID_VER)"

# Reports a device property as context rather than as a check
# NOTE: Aligned with the check labels, so it does not read as a detail line
#       belonging to whichever check happened to come before it.
t_info()
{
    echo " INFO $1: $2"
}

# Checks that a filesystem type is known to the running kernel
t_filesystem()
{
    if $BUSYBOX grep -qw "$1" /proc/filesystems 2>/dev/null; then
        t_ok "the kernel supports $1"
    else
        t_no "the kernel supports $1" "$2"
    fi
}

t_section "Device"

t_info "abi"      "$($BUSYBOX getprop ro.product.cpu.abi)"
t_info "abilist"  "$($BUSYBOX getprop ro.product.cpu.abilist)"
t_info "android"  "$($BUSYBOX getprop ro.build.version.release) (sdk $($BUSYBOX getprop ro.build.version.sdk))"
t_info "device"   "$($BUSYBOX getprop ro.product.model)"
t_info "kernel"   "$($BUSYBOX uname -r)"
t_info "busybox"  "$($BUSYBOX --help 2>&1 | $BUSYBOX head -n 1)"

if [ "$($BUSYBOX id -u)" -eq 0 ]; then
    t_ok "running as root"
else
    t_no "running as root" "Re-run the suite through \"su\"."
fi

if check_arch; then
    t_ok "the architecture is supported ($DEBDROID_ABI)"
else
    t_no "the architecture is supported" \
        "DebDroid does not support \"$DEBDROID_ABI\" yet."
fi

t_section "Installed files"

t_file "the frontend script is installed"  "$DEBDROID_SDHOME"/debdroid.sh
t_file "the manager script is installed"   "$DEBDROID_SDHOME"/debdroid_mgr.sh
t_file "the resizer script is installed"   "$DEBDROID_SDHOME"/debdroid_resize.sh
t_file "the environment file is installed" "$DEBDROID_SDHOME"/environment
t_file "the busybox binary is installed"   "$DEBDROID_BIN"/busybox
t_file "the e2fsck binary is installed"    "$DEBDROID_BIN"/e2fsck
t_file "the resize2fs binary is installed" "$DEBDROID_BIN"/resize2fs
t_file "the bootstrap script is installed" "$DEBDROID_BIN"/debinit

for binary in busybox e2fsck resize2fs; do
    if [ -x "$DEBDROID_BIN"/"$binary" ]; then
        t_ok "$binary is executable"
    else
        t_no "$binary is executable" \
            "Re-run install.sh to fix the permissions."
    fi
done

# NOTE: Android root shells often run with umask 077, which leaves these
#       directories unreadable and the preload libraries unloadable for a
#       non-root user inside the chroot.
for directory in "$DEBDROID_HOME" "$DEBDROID_BIN" "$DEBDROID_LIB" "$DEBDROID_ENV"; do
    t_mode "${directory} is traversable (755)" 755 "$directory"
done

for library in "$DEBDROID_LIB"/*.so*; do
    [ -f "$library" ] || continue
    t_mode "${library##*/} is readable (644)" 644 "$library"
done

t_section "Debian image"

if [ -L "$DEBDROID_IMG" ]; then
    t_no "the debian image is a regular file" \
        "It is a symlink, which the manager refuses to operate on."
elif [ -f "$DEBDROID_IMG" ]; then
    t_ok "the debian image is a regular file"
    t_info "size" "$($BUSYBOX du -h "$DEBDROID_IMG" 2>/dev/null | $BUSYBOX cut -f 1)"

    # Growing the image needs free space, so report it alongside
    free_space=$($BUSYBOX df -h /data 2>/dev/null | $BUSYBOX tail -n 1)
    t_info "free on /data" "${free_space:-unknown}"
else
    t_no "the debian image exists" "Expected it at: $DEBDROID_IMG"
fi

t_section "Kernel support"

# Without ext2 the image cannot be mounted at all
t_filesystem ext2 "The image is ext2, so DebDroid cannot mount it on this kernel."
t_filesystem proc "The chroot needs /proc."
t_filesystem devpts "The chroot needs /dev/pts for terminals."
t_filesystem tmpfs "The chroot needs tmpfs for /tmp and /dev/shm."

if $BUSYBOX grep -qw overlay /proc/filesystems 2>/dev/null; then
    t_ok "the kernel supports overlayfs (/dev gets a writable layer)"
else
    t_skip "the kernel supports overlayfs" \
        "Not supported, so /dev falls back to a bind mount. This is expected on older kernels."
fi

# Loop devices are how the image gets mounted
loops=$($BUSYBOX ls /dev/block/loop* 2>/dev/null | $BUSYBOX wc -l)
if [ "$loops" -gt 0 ]; then
    t_ok "loop devices are present ($loops found)"
else
    t_no "loop devices are present" \
        "Without /dev/block/loopN the image cannot be mounted."
fi

# The private mount namespace is the core of the design, so prove it works
if $BUSYBOX unshare --mount true 2>/dev/null; then
    t_ok "private mount namespaces work"
else
    t_no "private mount namespaces work" \
        "\"busybox unshare --mount\" failed, so DebDroid cannot isolate its mounts."
fi

# The lock is what prevents a second session from corrupting the image
if ( $BUSYBOX flock -n 9 ) 9> "$DEBDROID_LOCK" 2>/dev/null; then
    t_ok "advisory locking works"
else
    t_no "advisory locking works" \
        "\"busybox flock\" failed, so a second session could corrupt the image."
fi

t_section "Android integration"

if $BUSYBOX mountpoint -q /system 2>/dev/null; then
    t_ok "/system is mounted"
else
    t_skip "/system is mounted" "Android tools will not be reachable from the chroot."
fi

# Android 10 and later moved bionic into /apex, which the chroot needs for
# android commands such as "am" and "settings" to run
if [ -d /apex ]; then
    t_info "/apex" "present (android 10+); the chroot does not mount it yet"
else
    t_info "/apex" "absent (android 9 or older)"
fi

if [ -w /sys/power/wake_lock ]; then
    t_ok "wakelocks are available (/sys/power/wake_lock)"
else
    t_skip "wakelocks are available" \
        "Absent, so the device may sleep and stop answering while serving."
fi

if [ -f /proc/swaps ]; then
    t_ok "the kernel supports swap"
    swaps=$($BUSYBOX tail -n +2 /proc/swaps | $BUSYBOX wc -l)
    t_info "active swap areas" "$swaps"
else
    t_skip "the kernel supports swap" "Swap cannot be enabled on this kernel."
fi

# A swapfile cannot live on f2fs on the kernels DebDroid targets
data_fs=$($BUSYBOX awk '$2 == "/data" { print $3 }' /proc/self/mounts)
t_info "/data filesystem" "${data_fs:-unknown}"
if [ "$data_fs" = "f2fs" ]; then
    t_skip "a swapfile can live on /data" \
        "/data is f2fs, where swapon on a file fails; use zram instead."
fi

t_section "Required busybox applets"

# Applets the current scripts call, so a missing one breaks DebDroid today
missing=
for applet in awk cut tac seq kill sleep readlink mountpoint mount umount \
    chroot unshare flock sysctl hostname printf id getprop stat truncate du df; do
    $BUSYBOX --list | $BUSYBOX grep -qx "$applet" || missing="$missing $applet"
done
if [ -z "$missing" ]; then
    t_ok "every applet the scripts call is present"
else
    t_no "every applet the scripts call is present" "missing:$missing"
fi

# Applets later features need, absent is not a failure today
missing=
for applet in nsenter setsid start-stop-daemon mkswap swapon swapoff dd \
    sha256sum wget tar mke2fs tune2fs fuser lsof losetup; do
    $BUSYBOX --list | $BUSYBOX grep -qx "$applet" || missing="$missing $applet"
done
if [ -z "$missing" ]; then
    t_ok "every applet planned features need is present"
else
    t_skip "every applet planned features need is present" "missing:$missing"
fi

t_section "Session state"

# A held lock means a session is running, which makes the checks below unsafe
SESSION_RUNNING=no
if ( $BUSYBOX flock -n 9 ) 9> "$DEBDROID_LOCK" 2>/dev/null; then
    t_ok "no session is currently running"
else
    SESSION_RUNNING=yes
    t_skip "no session is currently running" \
        "A session holds the lock. Exit it before checking the image."
fi

# Leftover mounts mean a previous session did not clean up, which risks
# corrupting the image when the next one starts
leftover=$($BUSYBOX awk -v env="$DEBDROID_ENV" \
    '$2 == env || index($2, env "/") == 1 { print $2 }' /proc/self/mounts)
if [ -z "$leftover" ]; then
    t_ok "no leftover mounts under the environment"
else
    t_no "no leftover mounts under the environment" \
        "Reboot before starting DebDroid again, or the image may be corrupted." \
        "$leftover"
fi

# A read-only fsck is the cheapest way to catch a damaged image early
if [ "$SESSION_RUNNING" = "yes" ]; then
    t_skip "the debian image is clean" "Cannot check it while a session is running."
elif [ ! -f "$DEBDROID_IMG" ] || [ ! -x "$DEBDROID_BIN"/e2fsck ]; then
    t_skip "the debian image is clean" "The image or e2fsck is missing."
else
    # NOTE: "-n" answers no to every question, so nothing is written and any
    #       non-zero status means a real finding rather than a repair.
    if fsck_output=$("$DEBDROID_BIN"/e2fsck -fn "$DEBDROID_IMG" 2>&1); then
        t_ok "the debian image is clean"
    else
        t_no "the debian image is clean" \
            "Repair it: \"$DEBDROID_BIN/e2fsck -f $DEBDROID_IMG\"" \
            "$fsck_output"
    fi
fi

# The remaining checks start a real session, so they are opt-in
if [ "$RUN_SESSION" != "yes" ]; then
    echo
    echo "Skipping the session checks. Re-run with --session to include them."
    t_summary
    exit $?
fi

t_section "Live session"

if [ "$SESSION_RUNNING" = "yes" ]; then
    t_no "a session can be started" \
        "Another session is running. Exit it and re-run with --session."
    t_summary
    exit $?
fi

if [ ! -f "$DEVICE_DIR"/device_probe.sh ]; then
    t_no "the in-session probe is installed" \
        "Expected it at: $DEVICE_DIR/device_probe.sh"
    t_summary
    exit $?
fi

# Records the host's /dev so the session can be proven not to have written to it
# NOTE: Earlier versions created /dev/fd, /dev/std* and /dev/loopN symlinks in
#       android's real /dev before isolating it.
dev_before=$($BUSYBOX ls -1a /dev 2>/dev/null)

# Runs every in-session check in one session, as each start costs an fsck
# NOTE: The awkward arguments are deliberate: they cross three hops and a
#       "su -c" string, so the probe echoing them back proves nothing was lost.
session_log=$($BUSYBOX mktemp 2>/dev/null) || session_log=/data/local/tmp/debdroid-devtest.log
sh "$DEBDROID_SDHOME"/debdroid.sh run \
    sh /sdcard/debdroid/tests/device_probe.sh 'a b' "it's" '$HOME' '*' \
    > "$session_log" 2>&1
session_status=$?

# Reads a value the probe reported, or an empty string when it is absent
probe()
{
    $BUSYBOX sed -n "s/^DEVTEST:$1=//p" "$session_log" | $BUSYBOX head -n 1
}

if [ "$session_status" -eq 0 ]; then
    t_ok "a session starts and exits cleanly"
else
    t_no "a session starts and exits cleanly" \
        "exit status: $session_status" \
        "$($BUSYBOX tail -n 20 "$session_log")"
fi

if [ -n "$(probe ready)" ]; then
    t_ok "the probe ran inside the session"
else
    t_no "the probe ran inside the session" \
        "/sdcard may not be mounted in the chroot, so the probe was unreachable." \
        "$($BUSYBOX tail -n 20 "$session_log")"
    t_summary
    exit $?
fi

t_eq "the session umask is 0022" "0022" "$(probe umask)"
t_eq "the root filesystem is the image" "yes" "$(probe rootfs)"
t_info "debian release" "$(probe debian)"

# The first three are mounted with mount_required, so a missing one is a failure
t_eq "/proc is mounted"    "yes" "$(probe mount_proc)"
t_eq "/dev is mounted"     "yes" "$(probe mount_dev)"
t_eq "/dev/pts is mounted" "yes" "$(probe mount_devpts)"

# These use mount_optional, so the environment still runs without them
for optional in tmp sys sdcard; do
    if [ "$(probe "mount_$optional")" = "yes" ]; then
        t_ok "/$optional is mounted"
    else
        t_skip "/$optional is mounted" "Mounted with mount_optional, so this is not fatal."
    fi
done

t_info "/dev backing" "$(probe dev_type)"
t_info "/dev options" "$(probe dev_options)"
t_eq "the expected /dev nodes exist" "complete" "$(probe dev_nodes)"
t_eq "the bundled binaries are visible" "yes" "$(probe debdroid_bin)"
t_eq "the preload libraries are registered" "yes" "$(probe preload)"
t_info "preloaded" "$(probe preload_libs)"
t_eq "every preloaded library is readable" "yes" "$(probe preload_readable)"
t_eq "a nameserver is configured" "yes" "$(probe resolv)"
t_info "nameservers" "$(probe nameservers)"
t_eq "the hosts file is populated" "yes" "$(probe hosts)"
t_eq "the inet group exists" "yes" "$(probe inet)"
t_eq "apt belongs to the inet group" "yes" "$(probe apt_inet)"
t_info "kernel.shmmax" "$(probe shmmax)"

# The command crosses three hops before the chroot runs it, so prove that
# quotes, spaces, globs and variables all arrive untouched
t_eq "arguments survive the chroot intact" \
    "a b|it's|\$HOME|*|" "$(probe quoting)"

t_eq "the probe ran to completion" "yes" "$(probe complete)"

t_section "Session cleanup"

if ( $BUSYBOX flock -n 9 ) 9> "$DEBDROID_LOCK" 2>/dev/null; then
    t_ok "the lock is released after the session"
else
    t_no "the lock is released after the session" \
        "Something from the session is still running and holds the image."
fi

leftover=$($BUSYBOX awk -v env="$DEBDROID_ENV" \
    '$2 == env || index($2, env "/") == 1 { print $2 }' /proc/self/mounts)
if [ -z "$leftover" ]; then
    t_ok "the session left no mounts behind"
else
    t_no "the session left no mounts behind" \
        "Reboot before starting DebDroid again." "$leftover"
fi

if $BUSYBOX mountpoint -q "$DEBDROID_ENV" 2>/dev/null; then
    t_no "the image is unmounted" "$DEBDROID_ENV is still a mountpoint."
else
    t_ok "the image is unmounted"
fi

dev_after=$($BUSYBOX ls -1a /dev 2>/dev/null)
if [ "$dev_before" = "$dev_after" ]; then
    t_ok "the host's /dev was not modified"
else
    # Lists the entries that appeared, which are the ones the session leaked
    leaked=
    for entry in $dev_after; do
        case "
$dev_before
" in
            *"
$entry
"*) ;;
            *)  leaked="$leaked $entry" ;;
        esac
    done

    t_no "the host's /dev was not modified" \
        "The session created entries in android's real /dev:" \
        "appeared:${leaked:- (entries were removed instead)}"
fi

# The image must still be clean, as an unclean exit is what corrupts it
if fsck_output=$("$DEBDROID_BIN"/e2fsck -fn "$DEBDROID_IMG" 2>&1); then
    t_ok "the debian image is still clean after the session"
else
    t_no "the debian image is still clean after the session" \
        "Repair it: \"$DEBDROID_BIN/e2fsck -f $DEBDROID_IMG\"" \
        "$fsck_output"
fi

$BUSYBOX rm -f "$session_log"

t_summary
