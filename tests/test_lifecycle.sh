#!/bin/sh
# test_lifecycle.sh: Checks the uninstaller's guards and the updater's verifying
#
# The uninstaller runs as root with paths that come from a sourced configuration
# file, and the updater decides whether code that will run as root is genuine.
# Both are covered here by the cases where getting it wrong does damage.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX
# shellcheck disable=SC1091
. "$TEST_SD"/debdroid_lib.sh
# The extracted functions report through the output helpers
# shellcheck disable=SC1091
. "$TEST_SD"/debdroid_ui.sh

t_section "safe_remove refuses dangerous paths"

eval "$(sed -n '/^safe_remove()/,/^}/p' "$TEST_SD"/debdroid_uninstall.sh)"

# NOTE: These are the cases that turn an unset variable into a disaster. The
#       uninstaller builds its paths from debdroid_env.sh, so an empty or
#       truncated value must never reach rm -rf.
for path in '' '/' '/data' '/data/local' '/sdcard' '/usr' '/etc' 'relative/path'; do
    t_false "refuses \"$path\"" safe_remove "$path"
done

# A path deep enough to be a real debdroid directory is accepted
mkdir -p "$TEST_TMP"/victim/a/b
t_true "accepts a deep path" safe_remove "$TEST_TMP"/victim/a/b
t_false "the deep path is gone" test -e "$TEST_TMP"/victim/a/b

# A path that does not exist is not an error, as uninstalling is idempotent
t_true "a missing path is not an error" safe_remove "$TEST_TMP"/victim/does/not/exist

t_section "the uninstaller refuses to run over a mounted image"

# NOTE: Deleting the mount point while the image is mounted there would delete
#       the contents of the user's debian installation. The guard is a
#       mountpoint check, so assert it is actually present and precedes the
#       removals rather than trusting the ordering by eye.
guard_line=$($BUSYBOX grep -n 'mountpoint -q' "$TEST_SD"/debdroid_uninstall.sh |
    $BUSYBOX head -n 1 | $BUSYBOX cut -d : -f 1)
remove_line=$($BUSYBOX grep -n 'safe_remove "\$DEBDROID_HOME"' "$TEST_SD"/debdroid_uninstall.sh |
    $BUSYBOX head -n 1 | $BUSYBOX cut -d : -f 1)

if [ -n "$guard_line" ] && [ -n "$remove_line" ] && [ "$guard_line" -lt "$remove_line" ]; then
    t_ok "the mountpoint guard comes before any removal"
else
    t_no "the mountpoint guard comes before any removal" \
        "guard at line ${guard_line:-none}, removal at line ${remove_line:-none}"
fi

if $BUSYBOX grep -q 'flock -n 9' "$TEST_SD"/debdroid_uninstall.sh; then
    t_ok "it refuses to run while a session holds the lock"
else
    t_no "it refuses to run while a session holds the lock" "no lock check found"
fi

t_section "the uninstaller keeps the user's data by default"

# The image is the user's data, so removing it must take an explicit flag
if $BUSYBOX grep -q 'REMOVE_IMAGE=no' "$TEST_SD"/debdroid_uninstall.sh; then
    t_ok "the image is kept unless --image is given"
else
    t_no "the image is kept unless --image is given" "no default found"
fi

output=$(sh "$TEST_SD"/debdroid_uninstall.sh --bogus 2>&1)
case "$output" in
    *"Unknown option"*) t_ok "an unknown option is rejected" ;;
    *) t_no "an unknown option is rejected" "$output" ;;
esac

t_section "the updater verifies before it trusts"

eval "$(sed -n '/^verify()/,/^}/p' "$TEST_SD"/debdroid_update.sh)"

echo "payload" > "$TEST_TMP"/payload
real_sum=$($BUSYBOX sha256sum "$TEST_TMP"/payload)
real_sum=${real_sum%% *}

t_true "a matching checksum passes" verify "$TEST_TMP"/payload "$real_sum"

# NOTE: A flipped byte, a truncation and an entirely different file must all be
#       rejected. This is the code path deciding whether something that will run
#       as root is genuine.
t_false "a wrong checksum is rejected" verify "$TEST_TMP"/payload \
    "0000000000000000000000000000000000000000000000000000000000000000"

echo "payload tampered" > "$TEST_TMP"/payload
t_false "a modified payload is rejected" verify "$TEST_TMP"/payload "$real_sum"

printf 'pay' > "$TEST_TMP"/payload
t_false "a truncated payload is rejected" verify "$TEST_TMP"/payload "$real_sum"

t_false "a missing file is rejected" verify "$TEST_TMP"/absent "$real_sum"

t_section "the updater refuses a bundle that is not a release"

# A bundle is unpacked into staging and inspected before anything is installed
if $BUSYBOX grep -q 'DEBDROID_STAGING' "$TEST_SD"/debdroid_update.sh &&
        $BUSYBOX grep -q 'does not contain install.sh' "$TEST_SD"/debdroid_update.sh; then
    t_ok "a bundle is staged and checked before installing"
else
    t_no "a bundle is staged and checked before installing" "no staging check found"
fi

# The backup is what makes --rollback possible, so it must precede the install
backup_line=$($BUSYBOX grep -n 'backup_installation$' "$TEST_SD"/debdroid_update.sh |
    $BUSYBOX tail -n 1 | $BUSYBOX cut -d : -f 1)
install_line=$($BUSYBOX grep -n 'sh ./install.sh' "$TEST_SD"/debdroid_update.sh |
    $BUSYBOX head -n 1 | $BUSYBOX cut -d : -f 1)
if [ -n "$backup_line" ] && [ -n "$install_line" ] && [ "$backup_line" -lt "$install_line" ]; then
    t_ok "the backup is taken before the installer runs"
else
    t_no "the backup is taken before the installer runs" \
        "backup at line ${backup_line:-none}, install at line ${install_line:-none}"
fi

output=$(sh "$TEST_SD"/debdroid_update.sh --offline 2>&1)
case "$output" in
    *"needs a path"*) t_ok "--offline without a path is rejected" ;;
    *) t_no "--offline without a path is rejected" "$output" ;;
esac

output=$(sh "$TEST_SD"/debdroid_update.sh --offline "$TEST_TMP"/absent-bundle 2>&1)
case "$output" in
    *"No such release bundle"*) t_ok "a missing bundle is reported" ;;
    *) t_no "a missing bundle is reported" "$output" ;;
esac

t_summary
