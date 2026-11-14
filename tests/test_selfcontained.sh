#!/bin/sh
# test_selfcontained.sh: Checks each script stands up without the frontend
#
# The frontend sources debdroid_env.sh and exports everything from it, so a
# script invoked through the frontend sees variables it never defines. Invoked
# directly - which the manager's own usage says is supported, and which the
# test suite and bug reports do - those variables are empty, and an empty path
# is worse than a missing one: it silently targets the wrong thing.

# shellcheck disable=SC2034
# BUSYBOX is read by the scripts this file runs.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX

t_section "the manager defines what it uses"

# NOTE: This is the regression that prompted the file. The manager referenced
#       DEBDROID_WAKELOCK and DEBDROID_SWAP in nine places, defined neither and
#       sources no environment file, so running it directly wrote an empty
#       string to /sys/power/wake_lock and tested "" for the swapfile.
for variable in DEBDROID_WAKELOCK DEBDROID_SWAP; do
    if $BUSYBOX grep -qF "\$$variable" "$TEST_SD"/debdroid_mgr.sh; then
        if $BUSYBOX grep -qE "^: \"\\\$\{$variable:=" "$TEST_SD"/debdroid_mgr.sh; then
            t_ok "$variable is given a default where it is used"
        else
            t_no "$variable is given a default where it is used" \
                "the manager uses it but sources no environment file," \
                "so running it directly would leave it empty"
        fi
    else
        t_skip "$variable is given a default where it is used" \
            "the manager no longer references it"
    fi
done

# The defaults have to agree with what the environment file exports, or the
# two paths disagree depending on how the manager was started
env_swap=$($BUSYBOX sed -n 's|^export DEBDROID_SWAP=.*/\([^/"]*\)$|\1|p' \
    "$TEST_SD"/debdroid_env.sh)
mgr_swap=$($BUSYBOX sed -n 's|^: "${DEBDROID_SWAP:=.*/\([^/}"]*\)}"$|\1|p' \
    "$TEST_SD"/debdroid_mgr.sh)
t_eq "the swapfile name matches the environment file" "$env_swap" "$mgr_swap"

env_lock=$($BUSYBOX sed -n 's/^export DEBDROID_WAKELOCK=//p' "$TEST_SD"/debdroid_env.sh)
mgr_lock=$($BUSYBOX sed -n 's/^: "${DEBDROID_WAKELOCK:=\(.*\)}"$/\1/p' \
    "$TEST_SD"/debdroid_mgr.sh)
t_eq "the wakelock name matches the environment file" "$env_lock" "$mgr_lock"

t_section "device-side scripts refuse to run inside debian"

# NOTE: The frontend's "command" option runs scripts inside the chroot, where
#       /data is not mounted and busybox sits at a different path. Anything
#       managing the device rather than the userland must not live there, and
#       must say so clearly if it is run there anyway.
if [ -f "$TEST_SD"/debdroid_swap.sh ]; then
    t_ok "swap is a frontend subcommand, not a command script"

    if $BUSYBOX grep -q 'd /debdroid/bin' "$TEST_SD"/debdroid_swap.sh; then
        t_ok "it detects being inside a session"
    else
        t_no "it detects being inside a session" \
            "no check for /debdroid/bin, which only exists inside the chroot"
    fi
else
    t_no "swap is a frontend subcommand, not a command script" \
        "expected $TEST_SD/debdroid_swap.sh"
fi

if [ -f "$TEST_SD"/../command/swap.sh ] || [ -f "$REPO_DIR"/command/swap.sh ]; then
    t_no "swap is not in command/" \
        "command/ scripts run inside the chroot, where this cannot work"
else
    t_ok "swap is not in command/"
fi

# Everything that is in command/ has to be safe to run inside debian, which
# means not reaching for android-only paths
for script in "$REPO_DIR"/command/*.sh; do
    [ -f "$script" ] || continue
    name=${script##*/}

    if $BUSYBOX grep -qE '/data/local/debdroid|getprop|/proc/swaps' "$script"; then
        t_no "command/$name is safe to run inside debian" \
            "it reaches for an android-only path, so it belongs outside command/"
    else
        t_ok "command/$name is safe to run inside debian"
    fi
done

t_section "android assumptions the host cannot check"

# NOTE: Each check below is a failure mode that only appears on a device, so
#       the rest of this suite passes with the bug present. They are asserted
#       statically rather than exercised because the host has no android
#       property service, no phone-shaped device names, no busybox built the way
#       the bundled one is, and no privilege to unshare a mount namespace.

# getprop belongs to the android toolbox and is not a busybox applet, so
# "$BUSYBOX getprop" writes a usage error to stderr and nothing to stdout. That
# is indistinguishable from an unset property, which emptied the abi and made
# the installer reject every device it ran on.
# NOTE: Comment lines are stripped first, as the fix itself documents the
#       mistake it replaced and would otherwise match.
getprop_misuse=
for script in "$TEST_SD"/*.sh "$REPO_DIR"/install.sh "$REPO_DIR"/tests/device.sh; do
    [ -f "$script" ] || continue
    if $BUSYBOX grep -vE '^[[:space:]]*#' "$script" |
            $BUSYBOX grep -qE '\$\{?BUSYBOX\}?"? +getprop'; then
        getprop_misuse="$getprop_misuse ${script##*/}"
    fi
done
if [ -z "$getprop_misuse" ]; then
    t_ok "no script expects getprop from busybox"
else
    t_no "no script expects getprop from busybox" \
        "getprop is an android binary, not a busybox applet, so this reads as" \
        "an unset property on every device. Use get_prop instead." \
        "offending:$getprop_misuse"
fi

# get_prop is what replaced it, so it has to exist and not route through busybox
if $BUSYBOX grep -q '^get_prop()' "$TEST_SD"/debdroid_lib.sh; then
    t_ok "get_prop is defined in the shared library"
else
    t_no "get_prop is defined in the shared library" \
        "check_arch and the resolv.conf loop both depend on it"
fi

# busybox df wraps a device name longer than its column onto a line of its own
# and prints the numbers on the next one, which is what /data looks like on a
# real phone. Parsing by line number then reads the wrapped name, whose fields
# are empty. "-P" forces one line per filesystem.
df_unsafe=
for script in "$TEST_SD"/*.sh "$REPO_DIR"/install.sh "$REPO_DIR"/tests/device.sh; do
    [ -f "$script" ] || continue
    if $BUSYBOX grep -vE '^[[:space:]]*#' "$script" |
            $BUSYBOX grep -E '\$\{?BUSYBOX\}?"? +df ' |
            $BUSYBOX grep -qv -- '-P'; then
        df_unsafe="$df_unsafe ${script##*/}"
    fi
done
if [ -z "$df_unsafe" ]; then
    t_ok "every df call forces the one-line POSIX format"
else
    t_no "every df call forces the one-line POSIX format" \
        "without \"-P\" busybox wraps a long device name and the columns shift," \
        "which reported 0M free on a device with 17G available" \
        "offending:$df_unsafe"
fi

# The bundled busybox is built without CONFIG_FEATURE_SWAPON_PRI, so its swapon
# rejects "-p" outright and swap never came up on a real device.
if $BUSYBOX grep -qE '\$\{?BUSYBOX\}?"? +swapon +-p' "$TEST_SD"/debdroid_mgr.sh; then
    if $BUSYBOX grep -qE '\$\{?BUSYBOX\}?"? +swapon +"\$DEBDROID_SWAP"' \
            "$TEST_SD"/debdroid_mgr.sh; then
        t_ok "swapon falls back when the build has no priority support"
    else
        t_no "swapon falls back when the build has no priority support" \
            "the bundled busybox has no \"-p\", so this is the only code path" \
            "and swap silently never enables"
    fi
else
    t_skip "swapon falls back when the build has no priority support" \
        "the manager no longer passes -p"
fi

# Pass 1 used to end on "unset", discarding what pass 2 returned, so every
# foreground run exited 0 and "debdroid.sh run false" looked successful.
if $BUSYBOX grep -q 'exit "\$DEBDROIDMGR_STATUS"' "$TEST_SD"/debdroid_mgr.sh; then
    t_ok "a foreground session hands back its exit status"
else
    t_no "a foreground session hands back its exit status" \
        "pass 1 must exit with what pass 2 returned, or every run reports success"
fi

# The pid file is the only handle "stop" has on a running supervisor. Removing
# it before checking for one let a foreground run orphan a live background
# environment, which then needed a reboot to clear.
if $BUSYBOX grep -q '^    if read_supervisor "\$DEBDROIDMGR_PID"; then' \
        "$TEST_SD"/debdroid_mgr.sh; then
    t_ok "a live supervisor is detected before the pid file is cleared"
else
    t_no "a live supervisor is detected before the pid file is cleared" \
        "the check must not be conditional on the mode: a foreground run that" \
        "deletes the pid file leaves the background environment unreachable"
fi

t_section "the frontend dispatches every documented subcommand"

# A subcommand in the help that has no branch silently falls through to "run",
# which would start a session instead of doing what was asked
for subcommand in run start attach stop status list command resize swap \
    self-update uninstall help; do
    if $BUSYBOX grep -q "DEBDROID_OPT\" = \"$subcommand\"" "$TEST_SD"/debdroid.sh; then
        t_ok "\"$subcommand\" has a dispatch branch"
    else
        t_no "\"$subcommand\" has a dispatch branch" \
            "it would fall through and be treated as a command to run"
    fi
done

t_summary
