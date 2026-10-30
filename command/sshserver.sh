#!/bin/sh
# sshserver.sh: Starts an ssh server inside the environment
#
# Pair it with background mode, or the server stops when the session exits:
#
#   debdroid.sh start
#   debdroid.sh attach sh /sdcard/debdroid/command/sshserver.sh
#
# NOTE: sshd is started directly rather than through systemd, which does not
#       run in a chroot. Port 8022 is the default, as binding 22 clashes with
#       anything android already listens on and needs no privilege here anyway.

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: Missing required super-user permissions." >&2
    exit 1
fi

# shellcheck disable=SC1091
[ -f /sdcard/debdroid/environment ] && . /sdcard/debdroid/environment

SSH_PORT=${DEBDROID_SSH_PORT:-8022}

echo ":: Starting the ssh server on port $SSH_PORT"

if ! command -v sshd > /dev/null 2>&1; then
    echo " -> Installing openssh-server"
    if ! apt-get install -y openssh-server; then
        echo "Error: Failed to install openssh-server." >&2
        echo "    Run \"apt update\" first, then try again." >&2
        exit 1
    fi
fi

# sshd refuses to start without its runtime directory and host keys
if [ ! -d /run/sshd ]; then
    echo " -> Creating /run/sshd"
    mkdir -p /run/sshd
    chmod 755 /run/sshd
fi

if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then
    echo " -> Generating host keys"
    ssh-keygen -A
fi

# Root login over ssh with a password is a poor default, so point at the helper
if ! getent passwd | awk -F: '$3 >= 1000 && $3 < 65534 { found = 1 } END { exit !found }'; then
    echo " -> No regular user exists yet"
    echo "    Create one first: debdroid.sh command user"
fi

if pgrep -x sshd > /dev/null 2>&1; then
    echo " -> An ssh server is already running, restarting it"
    pkill -x sshd
    sleep 1
fi

echo " -> Starting sshd"
if ! sshd -p "$SSH_PORT"; then
    echo "Error: Failed to start sshd." >&2
    echo "    Check the configuration: \"sshd -t\"" >&2
    exit 1
fi

echo ":: The ssh server is running"

# Reports every address the device can be reached on
for address in $(ip -o -4 addr show 2>/dev/null | awk '{ print $4 }' | cut -d / -f 1); do
    case "$address" in
        127.*) continue ;;
    esac
    echo " -> ssh -p $SSH_PORT <user>@$address"
done

echo
echo "NOTE: The server stops when the environment stops. Use \"debdroid.sh start\""
echo "      so it keeps running after you close the terminal."
