#!/bin/sh
# test_background.sh: Checks the background environment's bookkeeping
#
# The supervisor's pid file is what attach and stop rely on to find the running
# environment, and acting on a wrong pid would signal an unrelated process, so
# the validation around it is covered here in detail.

# shellcheck disable=SC2154
# supervisor_pid is set by read_supervisor, loaded from the product script.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX
# shellcheck disable=SC1091
. "$TEST_SD"/debdroid_lib.sh

DEBDROIDMGR_PID="$TEST_TMP"/mnt.pid

# Loads read_supervisor from the product script
# shellcheck disable=SC2154 # supervisor_pid is set by the function loaded below
eval "$(sed -n '/^read_supervisor()/,/^}/p' "$TEST_SD"/debdroid_mgr.sh)"

# The namespace id of this very process, which a valid pid file has to match
own_mnt=$($BUSYBOX readlink /proc/self/ns/mnt | $BUSYBOX cut -d ':' -f 2)

t_section "read_supervisor rejects anything it cannot trust"

rm -f "$DEBDROIDMGR_PID"
t_false "no pid file" read_supervisor

: > "$DEBDROIDMGR_PID"
t_false "an empty pid file" read_supervisor

echo "notapid" > "$DEBDROIDMGR_PID"
t_false "a pid file without a namespace" read_supervisor

# NOTE: This is the case that matters: the kernel reuses pids, so a pid file
#       left by a crashed supervisor could name a live, unrelated process.
#       Signaling it would kill something the user never started.
echo "$$ 9999999999" > "$DEBDROIDMGR_PID"
t_false "a live pid whose namespace does not match" read_supervisor

echo "999999 $own_mnt" > "$DEBDROIDMGR_PID"
t_false "a pid that no longer exists" read_supervisor

t_section "read_supervisor accepts a valid pid file"

echo "$$ $own_mnt" > "$DEBDROIDMGR_PID"
if read_supervisor; then
    t_ok "a matching pid and namespace"
    t_eq "the pid is reported back" "$$" "$supervisor_pid"
else
    t_no "a matching pid and namespace" \
        "pid file: $(cat "$DEBDROIDMGR_PID")" "own namespace: $own_mnt"
fi

t_section "the frontend reports and refuses correctly"

rm -f "$DEBDROIDMGR_PID" "$TEST_TMP"/home/mnt.pid

# status must work with nothing running, rather than failing
output=$(sh "$TEST_SD"/debdroid.sh status 2>&1)
case "$output" in
    *"Background environment: stopped"*) t_ok "status reports a stopped environment" ;;
    *) t_no "status reports a stopped environment" "$output" ;;
esac

# attach and stop must explain themselves instead of acting on nothing
output=$(sh "$TEST_SD"/debdroid.sh attach 2>&1)
case "$output" in
    *"No background environment is running"*) t_ok "attach refuses when nothing runs" ;;
    *) t_no "attach refuses when nothing runs" "$output" ;;
esac

output=$(sh "$TEST_SD"/debdroid.sh stop 2>&1)
case "$output" in
    *"No background environment is running"*) t_ok "stop refuses when nothing runs" ;;
    *) t_no "stop refuses when nothing runs" "$output" ;;
esac

# A stale pid file must be cleared, or the next start would refuse to run
echo "999999 $own_mnt" > "$TEST_TMP"/home/mnt.pid
sh "$TEST_SD"/debdroid.sh stop > /dev/null 2>&1
if [ -f "$TEST_TMP"/home/mnt.pid ]; then
    t_no "stop clears a stale pid file" "the file was left behind"
else
    t_ok "stop clears a stale pid file"
fi

# NOTE: The manager sources no environment file, so BUSYBOX has to be passed in
#       when it is invoked directly rather than through the frontend.
output=$(BUSYBOX=$FAKE_BUSYBOX DEBDROIDMGR_MODE=bogus sh "$TEST_SD"/debdroid_mgr.sh \
    "$TEST_TMP"/img "$TEST_TMP"/home/mnt "$TEST_TMP"/home/bin "$TEST_TMP"/home/lib 2>&1)
case "$output" in
    *"Unknown mode"*) t_ok "an unknown mode is rejected" ;;
    *) t_no "an unknown mode is rejected" "$output" ;;
esac

t_section "the supervisor blocks without delaying its traps"

# NOTE: A POSIX shell runs no trap while a foreground command executes, so a
#       plain "sleep 60" would make "stop" wait up to a minute. The supervisor
#       backgrounds the sleep and calls "wait", which a signal interrupts.
#       This reproduces that loop and times how long a TERM takes to land.
cat > "$TEST_TMP"/supervise.sh <<'LOOP'
trap 'exit 1' TERM
trap 'echo stopped > "$1".done' EXIT
echo ready > "$1".ready
while :; do
    sleep 60 &
    wait
done
LOOP

sh "$TEST_TMP"/supervise.sh "$TEST_TMP"/loop &
loop_pid=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -f "$TEST_TMP"/loop.ready ] && break
    sleep 0.2 2>/dev/null || sleep 1
done

kill -TERM "$loop_pid" 2>/dev/null
stopped=no
for _ in 1 2 3 4 5; do
    if [ -f "$TEST_TMP"/loop.done ]; then
        stopped=yes
        break
    fi
    sleep 1
done
t_eq "a TERM reaches the blocking loop promptly" "yes" "$stopped"

t_summary
