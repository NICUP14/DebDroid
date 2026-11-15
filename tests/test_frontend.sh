#!/bin/sh
# test_frontend.sh: Checks that the frontend and manager pass commands through
# unchanged, which is what keeps quotes, spaces and globs intact

# shellcheck disable=SC2034
# BUSYBOX is read by the product scripts this file runs, not here.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX

# Replaces the manager with a stub that reports the argv it was handed
cp "$TEST_SD"/debdroid_mgr.sh "$TEST_TMP"/debdroid_mgr.sh.real
cat > "$TEST_SD"/debdroid_mgr.sh <<'STUB'
#!/bin/sh
shift 4
for a in "$@"; do printf '[%s]' "$a"; done
STUB

# Runs the frontend and collects the argv the manager received
frontend_argv()
{
    sh "$TEST_SD"/debdroid.sh "$@" 2>/dev/null
}

t_section "the frontend hands the manager an unchanged argv"

t_eq "a plain command" \
    '[apt][update]' "$(frontend_argv run apt update)"

t_eq "an argument containing a space stays one argument" \
    '[apt][install][foo bar]' "$(frontend_argv run apt install 'foo bar')"

t_eq "a single quote survives" \
    "[echo][it's]" "$(frontend_argv run echo "it's")"

# NOTE: These two are the regression the quoting fix was written for: an
#       unquoted "$@" expanded globs against the host's current directory and
#       let the shell substitute variables before the chroot ever started.
t_eq "a glob is not expanded" \
    '[echo][*]' "$(frontend_argv run echo '*')"

t_eq "a variable is not substituted" \
    '[echo][$HOME]' "$(frontend_argv run echo '$HOME')"

t_eq "an empty argument is preserved" \
    '[echo][][x]' "$(frontend_argv run echo '' x)"

t_eq "an unknown option is treated as a command" \
    '[apt][install][a b]' "$(frontend_argv apt install 'a b')"

t_section "the command subcommand validates its name"

mkdir -p "$TEST_SD"/command
echo 'true' > "$TEST_SD"/command/update.sh

t_eq "a known command script runs through the manager" \
    "[sh][$TEST_SD/command/update.sh]" "$(frontend_argv command update)"

# A name with a path separator must never reach the filesystem lookup
for name in '../../etc/passwd' '/etc/passwd' 'a/b' 'a;b' 'a b' 'a$b'; do
    output=$(sh "$TEST_SD"/debdroid.sh command "$name" 2>&1)
    case "$output" in
        *"Invalid command script name"*) t_ok "rejects \"$name\"" ;;
        *)                               t_no "rejects \"$name\"" "$output" ;;
    esac
done

output=$(sh "$TEST_SD"/debdroid.sh command absent 2>&1)
case "$output" in
    *"No such command script"*) t_ok "reports a missing command script" ;;
    *)                          t_no "reports a missing command script" "$output" ;;
esac

output=$(sh "$TEST_SD"/debdroid.sh command 2>&1)
case "$output" in
    *"Missing command script name"*) t_ok "reports a missing name" ;;
    *)                               t_no "reports a missing name" "$output" ;;
esac

t_section "command runs inside a background environment when there is one"

# NOTE: This is the README's first example: "start" and then
#       "command sshserver". The command used to take the foreground path, so
#       the manager refused it because the image was already in use, and where
#       it did run the session shut down the server as it exited.
cat > "$TEST_SD"/debdroid_mgr.sh <<'MODESTUB'
#!/bin/sh
printf 'mode=%s' "${DEBDROIDMGR_MODE:-unset}"
MODESTUB

echo 'true' > "$TEST_SD"/command/sshserver.sh
mkdir -p "$TEST_TMP"/home

rm -f "$TEST_TMP"/home/mnt.pid
t_eq "with nothing running it takes a foreground session" \
    "mode=unset" "$(frontend_argv command sshserver)"

# A pid file naming this process, whose namespace id therefore matches
own_mnt=$($BUSYBOX readlink /proc/self/ns/mnt | $BUSYBOX cut -d ':' -f 2)
echo "$$ $own_mnt" > "$TEST_TMP"/home/mnt.pid
t_eq "with a background environment it attaches to it" \
    "mode=attach" "$(frontend_argv command sshserver)"

# A stale pid file must not send it down the attach path
echo "999999 $own_mnt" > "$TEST_TMP"/home/mnt.pid
t_eq "a stale pid file is ignored" \
    "mode=unset" "$(frontend_argv command sshserver)"

# "run" is unaffected, as a foreground session is what it is for
rm -f "$TEST_TMP"/home/mnt.pid
t_eq "run is left alone" "mode=unset" "$(frontend_argv run true)"

rm -f "$TEST_TMP"/home/mnt.pid

t_section "the manager quotes the command for its su -c string"

# shellcheck disable=SC1091
. "$TEST_SD"/debdroid_lib.sh

# The chroot line evaluates the quoted string once, so a round-trip through
# "sh -c" has to reproduce the original argv exactly
shell_quote apt install 'foo bar' "it's" '$HOME' '*'
actual=$(sh -c "for a in $DEBDROID_QUOTED; do printf '[%s]' \"\$a\"; done")
t_eq "the su -c string round-trips" \
    "[apt][install][foo bar][it's][\$HOME][*]" "$actual"

# Restores the real manager for any later test file
cp "$TEST_TMP"/debdroid_mgr.sh.real "$TEST_SD"/debdroid_mgr.sh

t_summary
