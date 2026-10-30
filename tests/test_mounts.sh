#!/bin/sh
# test_mounts.sh: Checks the mount-point listing used to unmount an environment

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX

# Builds a mounts file shaped like /proc/self/mounts
# NOTE: "mntother" shares a prefix with the environment but is a sibling, so it
#       must not be unmounted. That is what the index() check in the function is
#       for, and getting it wrong would unmount unrelated filesystems.
cat > "$TEST_TMP"/mounts <<'EOF'
rootfs / rootfs rw 0 0
tmpfs /some/other/place tmpfs rw 0 0
/dev/block/loop0 /data/local/debdroid/mnt ext2 rw,relatime 0 0
proc /data/local/debdroid/mnt/proc proc rw,relatime 0 0
devpts /data/local/debdroid/mnt/dev/pts devpts rw,relatime 0 0
tmpfs /data/local/debdroid/mnt/tmp tmpfs rw,relatime 0 0
/dev/block/loop9 /data/local/debdroid/mntother ext4 rw,relatime 0 0
EOF

# Loads the function from the product script, reading the fixture instead of proc
eval "$(
    sed -n '/^environment_mounts()/,/^}/p' "$TEST_SD"/debdroid_mgr.sh |
        sed "s#/proc/self/mounts#$TEST_TMP/mounts#"
)"

t_section "environment_mounts lists the environment's mounts, deepest first"

DEBDROIDMGR_ENV=/data/local/debdroid/mnt
actual=$(environment_mounts | tr '\n' ' ')

t_eq "nested mounts come before their parent" \
    "/data/local/debdroid/mnt/tmp /data/local/debdroid/mnt/dev/pts /data/local/debdroid/mnt/proc /data/local/debdroid/mnt " \
    "$actual"

case "$actual" in
    *mntother*) t_no "a sibling sharing the prefix is excluded" "listed: $actual" ;;
    *)          t_ok "a sibling sharing the prefix is excluded" ;;
esac

case "$actual" in
    *"/some/other/place"*) t_no "unrelated mounts are excluded" "listed: $actual" ;;
    *)                     t_ok "unrelated mounts are excluded" ;;
esac

# An environment with nothing mounted must produce no output at all,
# otherwise the unmount loop would run against an empty path
DEBDROIDMGR_ENV=/data/local/debdroid/absent
t_eq "an unmounted environment lists nothing" "" "$(environment_mounts)"

t_summary
