#!/bin/sh
# test_update.sh: Checks what survives an update, and what must not
#
# Three things an update used to get wrong, each of which looked fine on a fresh
# install and only went wrong on the second one:
#
#   - the manager flags were edited in debdroid_mgr.sh, which an update replaces
#   - the version lived in debdroid_env.sh, which an update preserves, so it
#     froze at whatever was first installed
#   - rollback picked its backup with a lexical sort
#
# The first of those is now structural: debdroid.conf is not shipped, so the
# installer has no code path that writes it. The assertions below are what hold
# that in place, since there is no preserve list entry to read.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX

REPO_ROOT=${TESTS_DIR%/tests}

t_section "the conf file is never part of the payload"

# NOTE: This is the whole guarantee. A file that is never copied cannot be
#       overwritten by someone forgetting to list it as preserved.
t_false "no debdroid.conf ships in debdroid/" \
    test -e "$REPO_ROOT"/debdroid/debdroid.conf

if $BUSYBOX grep -qE '^\s*(environment\|debdroid_env\.sh|.*debdroid\.conf.*)\)' \
        "$REPO_ROOT"/install.sh &&
        $BUSYBOX grep -A3 'case "\$name" in' "$REPO_ROOT"/install.sh |
            "$BUSYBOX" grep -q 'debdroid\.conf'; then
    t_no "the preserve list does not mention debdroid.conf" \
        "listing it implies it ships, which is what this design avoids"
else
    t_ok "the preserve list does not mention debdroid.conf"
fi

# Any copy into the conf would reintroduce the overwrite, so only the migration
# may write it, and only with a redirection
if $BUSYBOX grep -nE '(cp|mv|install) [^|]*debdroid\.conf' "$REPO_ROOT"/install.sh; then
    t_no "the installer never copies anything onto debdroid.conf" \
        "a path above would overwrite the user's flags"
else
    t_ok "the installer never copies anything onto debdroid.conf"
fi

t_section "the manager reads the conf, but only when it exists"

if $BUSYBOX grep -q 'if \[ -f "\$DEBDROIDMGR_DIR/debdroid.conf" \]' \
        "$TEST_SD"/debdroid_mgr.sh; then
    t_ok "the manager sources debdroid.conf conditionally"
else
    t_no "the manager sources debdroid.conf conditionally" \
        "an unconditional source fails on every device without one"
fi

# The defaults have to stay in the manager, or it cannot run standalone, which
# is exactly how this suite invokes it
t_true "the defaults are still in the manager" \
    "$BUSYBOX" grep -q '^CONFIG_ENV_SWAP=no' "$TEST_SD"/debdroid_mgr.sh

# The conf is read after the defaults, or it could not override them
defaults_line=$($BUSYBOX grep -n '^CONFIG_DEV_FD=' "$TEST_SD"/debdroid_mgr.sh |
    $BUSYBOX cut -d: -f1)
conf_line=$($BUSYBOX grep -n 'DEBDROIDMGR_DIR/debdroid.conf' "$TEST_SD"/debdroid_mgr.sh |
    $BUSYBOX head -n 1 | $BUSYBOX cut -d: -f1)
if [ -n "$defaults_line" ] && [ -n "$conf_line" ] &&
        [ "$conf_line" -gt "$defaults_line" ]; then
    t_ok "the conf is read after the defaults, so it overrides them"
else
    t_no "the conf is read after the defaults, so it overrides them" \
        "defaults at line $defaults_line, conf at line $conf_line"
fi

t_section "migrating flags out of an older manager"

eval "$(sed -n '/^migrate_manager_flags()/,/^}/p' "$REPO_ROOT"/install.sh)"

MIG="$TEST_TMP"/migrate
mkdir -p "$MIG"

# An installed manager from before the split, with one flag changed
cat > "$MIG"/old_mgr.sh << 'OLDMGR'
#!/system/bin/sh
# Debdroid Manager configuration parameters
CONFIG_DEV_FD=yes
CONFIG_ENV_SWAP=yes
CONFIG_ENV_SWAPPINESS=10
# not a flag
start_environment() { :; }
OLDMGR

migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/old_mgr.sh > /dev/null 2>&1

t_true "it creates the conf" test -f "$MIG"/debdroid.conf
t_true "it carries the changed flag over" \
    "$BUSYBOX" grep -q '^CONFIG_ENV_SWAP=yes' "$MIG"/debdroid.conf
t_true "it carries every flag over" \
    "$BUSYBOX" grep -q '^CONFIG_ENV_SWAPPINESS=10' "$MIG"/debdroid.conf
t_false "it copies nothing but flags" \
    "$BUSYBOX" grep -q 'start_environment' "$MIG"/debdroid.conf

# Running again must not touch what the user has since edited
echo 'CONFIG_ENV_SWAP=no' > "$MIG"/debdroid.conf
migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/old_mgr.sh > /dev/null 2>&1
t_eq "an existing conf is left alone" \
    "CONFIG_ENV_SWAP=no" "$(cat "$MIG"/debdroid.conf)"

# A fresh install has no old manager to read
rm -f "$MIG"/debdroid.conf
migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/absent.sh > /dev/null 2>&1
t_false "a fresh install produces no conf" test -e "$MIG"/debdroid.conf

# A manager with no flags at all leaves nothing behind
echo '#!/system/bin/sh' > "$MIG"/flagless.sh
migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/flagless.sh > /dev/null 2>&1
t_false "a manager with no flags produces no conf" test -e "$MIG"/debdroid.conf

t_section "the version is not in the file the installer preserves"

t_true "the version is in debdroid_lib.sh" \
    "$BUSYBOX" grep -q "^export DEBDROID_VER='" "$REPO_ROOT"/debdroid/debdroid_lib.sh
t_false "the version is not in debdroid_env.sh" \
    "$BUSYBOX" grep -q '^export DEBDROID_VER=' "$REPO_ROOT"/debdroid/debdroid_env.sh

# NOTE: A device updated from an older release still has the old value in its
#       preserved debdroid_env.sh. Every entry point sources env before lib, so
#       the live value must be the one from lib.
# NOTE: Run as a separate process rather than a subshell, so neither the stale
#       file nor the library can leak into this one, and so the result is read
#       from the child's output instead of from "$?", which is easy to read off
#       the wrong command.
stale_env="$TEST_TMP"/stale_env.sh
echo "export DEBDROID_VER='1.0.0'" > "$stale_env"

shipped_version=$("$BUSYBOX" sed -n "s/^export DEBDROID_VER='\(.*\)'\$/\1/p" \
    "$REPO_ROOT"/debdroid/debdroid_lib.sh)
live_version=$(sh -c '. "$1" && . "$2" && echo "$DEBDROID_VER"' \
    sh "$stale_env" "$REPO_ROOT"/debdroid/debdroid_lib.sh 2>/dev/null)

t_eq "lib overrides a stale version left in a preserved env file" \
    "$shipped_version" "$live_version"

for entry in debdroid.sh debdroid_update.sh debdroid_uninstall.sh \
    debdroid_resize.sh debdroid_swap.sh debdroid_image.sh; do
    order=$($BUSYBOX grep -n 'debdroid_env\.sh\|debdroid_lib\.sh' \
        "$REPO_ROOT"/debdroid/"$entry" |
        $BUSYBOX grep '\. ' | $BUSYBOX sed -n 's/.*\(debdroid_[a-z]*\)\.sh.*/\1/p' |
        $BUSYBOX tr '\n' ' ')
    case "$order" in
        *debdroid_env*debdroid_lib*)
            t_ok "$entry sources env before lib" ;;
        *)
            t_no "$entry sources env before lib" \
                "got: $order, so a stale version would win" ;;
    esac
done

t_summary
