#!/bin/sh
# test_envfiles.sh: Checks the configuration files written into the image
#
# These are written from the host side, pointing into the mounted image. If the
# image ships one of them as an absolute symlink, which systemd-resolved does
# for /etc/resolv.conf, a naive write follows it out of the image and lands on
# the host's real /etc instead.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX

# Stands in for the mounted image
DEBDROIDMGR_ENV="$TEST_TMP"/envfiles
mkdir -p "$DEBDROIDMGR_ENV"/etc

eval "$(sed -n '/^write_env_file()/,/^}/p' "$TEST_SD"/debdroid_mgr.sh)"

t_section "write_env_file prepares a file for writing"

write_env_file etc/hosts
t_true "it creates a missing file" test -f "$DEBDROIDMGR_ENV"/etc/hosts
t_mode "it is world-readable" 644 "$DEBDROIDMGR_ENV"/etc/hosts

# A file that already exists keeps its mode when merely truncated, so the
# explicit chmod has to bring it back to 644
echo "stale content" > "$DEBDROIDMGR_ENV"/etc/hosts
chmod 600 "$DEBDROIDMGR_ENV"/etc/hosts
write_env_file etc/hosts
t_eq "it empties an existing file" "" "$(cat "$DEBDROIDMGR_ENV"/etc/hosts)"
t_mode "it fixes a restrictive mode" 644 "$DEBDROIDMGR_ENV"/etc/hosts

t_section "an absolute symlink cannot escape the image"

# NOTE: This is the regression being guarded. The image's /etc/resolv.conf is a
#       symlink to an absolute path, which on a real device resolves against
#       android's root, not the image's.
outside="$TEST_TMP"/outside-the-image
echo "host file, must not be touched" > "$outside"

ln -sf "$outside" "$DEBDROIDMGR_ENV"/etc/resolv.conf
write_env_file etc/resolv.conf
echo "nameserver 1.1.1.1" >> "$DEBDROIDMGR_ENV"/etc/resolv.conf

t_eq "the file outside the image is untouched" \
    "host file, must not be touched" "$(cat "$outside")"

if [ -L "$DEBDROIDMGR_ENV"/etc/resolv.conf ]; then
    t_no "the symlink was replaced by a regular file" \
        "it is still a symlink, so writes follow it out of the image"
else
    t_ok "the symlink was replaced by a regular file"
fi

t_eq "the content landed inside the image" \
    "nameserver 1.1.1.1" "$(cat "$DEBDROIDMGR_ENV"/etc/resolv.conf)"

# A relative symlink is just as dangerous, as it can still climb out with ..
ln -sf ../../outside-the-image "$DEBDROIDMGR_ENV"/etc/ld.so.preload
write_env_file etc/ld.so.preload
echo "/debdroid/lib/librandom.so" >> "$DEBDROIDMGR_ENV"/etc/ld.so.preload
t_eq "a relative symlink is handled too" \
    "host file, must not be touched" "$(cat "$outside")"

t_section "the /dev setup never writes to the host's /dev"

# NOTE: Every node the manager creates must be rooted at the environment. A
#       bare "/dev/..." target in populate_dev would write into android itself,
#       which is the bug this guards against.
if sed -n '/^populate_dev()/,/^}/p' "$TEST_SD"/debdroid_mgr.sh |
        $BUSYBOX grep -nE '(ln -s|mkdir|cp) .*[^v]/dev/' |
        $BUSYBOX grep -v 'dev_root' |
        $BUSYBOX grep -vE 'ln -s /(proc|dev)/'; then
    t_no "populate_dev only creates nodes under the environment" \
        "a path above does not start from \$dev_root"
else
    t_ok "populate_dev only creates nodes under the environment"
fi

# The overlay's upper directory becomes /dev, so it must not be private
upper_mode=$(sed -n '/^mount_dev()/,/^}/p' "$TEST_SD"/debdroid_mgr.sh |
    $BUSYBOX grep -A1 'dev-ovl/upper$' | $BUSYBOX grep -o 'chmod 755')
t_eq "the overlay upper directory is 755, not 700" "chmod 755" "$upper_mode"

# Both overlay directories have to be on one filesystem or the kernel refuses
if sed -n '/^mount_dev()/,/^}/p' "$TEST_SD"/debdroid_mgr.sh |
        $BUSYBOX grep -q 'upperdir=.*dev-ovl/upper,workdir=.*dev-ovl/work'; then
    t_ok "the overlay upper and work directories share one tmpfs"
else
    t_no "the overlay upper and work directories share one tmpfs" \
        "the kernel rejects an overlay whose upperdir and workdir differ"
fi

t_summary
