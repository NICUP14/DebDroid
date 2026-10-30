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
