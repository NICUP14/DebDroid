#!/bin/sh
# device_probe.sh: Runs inside a DebDroid session and reports what it finds
#
# Started by tests/device.sh through "debdroid.sh run", never on its own. Every
# finding is printed as a "DEVTEST:key=value" line, which the host side reads
# back; the marker keeps the values separable from the manager's own output.
#
# This runs in the debian userland, so the normal tools are available. It only
# reads: writing to /dev here would land in android's real /dev whenever the
# overlay fell back to a bind mount, which is the very thing being checked.

# Reports a finding to the host side of the suite
report()
{
    echo "DEVTEST:$1=$2"
}

# Reports whether a path is a mount point inside this session
report_mount()
{
    if awk -v target="$2" '$2 == target { found = 1 } END { exit !found }' /proc/self/mounts; then
        report "mount_$1" yes
    else
        report "mount_$1" no
    fi
}

report ready yes
report umask "$(umask)"

# The arguments arrive from the host through three hops and a "su -c" string,
# so joining them back together shows whether any quoting was lost
quoting=
for arg in "$@"; do
    quoting="$quoting$arg|"
done
report quoting "$quoting"

# A valid session has the image as its root, which /debdroid proves
if [ -d /debdroid ] && [ -f /etc/debian_version ]; then
    report rootfs yes
    report debian "$(cat /etc/debian_version 2>/dev/null)"
else
    report rootfs no
fi

report_mount proc   /proc
report_mount dev    /dev
report_mount devpts /dev/pts
report_mount tmp    /tmp
report_mount sys    /sys
report_mount sdcard /sdcard

# Distinguishes the three ways /dev can be set up, as a bind mount means the
# session shares android's real /dev instead of getting its own layer
dev_type=$(awk '$2 == "/dev" { print $3; exit }' /proc/self/mounts)
dev_options=$(awk '$2 == "/dev" { print $4; exit }' /proc/self/mounts)
case "$dev_type" in
    overlay) report dev_type "overlay (writable layer)" ;;
    tmpfs)
        # Android's own /dev carries these nodes, a fresh tmpfs does not
        if [ -e /dev/socket ] || [ -e /dev/binder ]; then
            report dev_type "bind (android /dev, shared)"
        else
            report dev_type "tmpfs (private)"
        fi
        ;;
    "") report dev_type "not mounted" ;;
    *)  report dev_type "$dev_type" ;;
esac
report dev_options "$dev_options"

# The standard streams and loop nodes the manager links in
for node in fd stdin stdout stderr null zero urandom tty ptmx; do
    [ -e /dev/"$node" ] || missing_nodes="$missing_nodes $node"
done
report dev_nodes "${missing_nodes:-complete}"

# The bundled binaries have to be reachable, as debinit lives among them
if [ -x /debdroid/bin/busybox ] && [ -f /debdroid/bin/debinit ]; then
    report debdroid_bin yes
else
    report debdroid_bin no
fi

# A non-root user must be able to read the preload libraries, or every command
# it runs fails with a linker error
if [ -s /etc/ld.so.preload ] && grep -q '^/debdroid/lib/' /etc/ld.so.preload; then
    report preload yes
    report preload_libs "$(tr '\n' ' ' < /etc/ld.so.preload)"

    unreadable=
    while IFS= read -r library; do
        [ -n "$library" ] || continue
        [ -r "$library" ] || unreadable="$unreadable $library"
    done < /etc/ld.so.preload
    report preload_readable "${unreadable:-yes}"
else
    report preload no
fi

# Networking inside the chroot depends on both of these being written
if [ -s /etc/resolv.conf ] && grep -q '^nameserver' /etc/resolv.conf; then
    report resolv yes
    report nameservers "$(awk '/^nameserver/ { printf "%s ", $2 }' /etc/resolv.conf)"
else
    report resolv no
fi

if [ -s /etc/hosts ] && grep -q 'localhost' /etc/hosts; then
    report hosts yes
else
    report hosts no
fi

# Android restricts sockets to members of the inet group, so debinit creates it
if getent group inet > /dev/null 2>&1; then
    report inet yes
else
    report inet no
fi

# apt breaks without this, which is the fix debinit applies
if id -nG _apt 2>/dev/null | grep -qw inet; then
    report apt_inet yes
else
    report apt_inet no
fi

report shmmax "$(cat /proc/sys/kernel/shmmax 2>/dev/null)"

# NOTE: Named "complete" rather than "done", which a shell would read as a
#       keyword when the host side calls "probe done" inside a substitution.
report complete yes
