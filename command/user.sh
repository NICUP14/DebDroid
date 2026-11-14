#!/bin/sh
# user.sh: Creates a non-root user that can actually use the network
#
# Android restricts network sockets to members of the "inet" group, so a user
# created by hand cannot ping, apt or ssh until it is added. Doing that is the
# single most common thing new users miss, so it happens here automatically.
#
# Usage: debdroid.sh command user          (prompts for the name)
#        debdroid.sh command user <name>   (no prompt)
#
# A name can also be set as DEBDROID_USER in /sdcard/debdroid/environment, which
# this script reads below. Exporting DEBDROID_USER in the calling shell does not
# work: the session starts through "su -", which resets the environment.

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: Missing required super-user permissions." >&2
    exit 1
fi

# shellcheck disable=SC1091
[ -f /sdcard/debdroid/environment ] && . /sdcard/debdroid/environment

# The argument wins, then the environment file, then a prompt
USER_NAME=${1:-$DEBDROID_USER}

if [ -z "$USER_NAME" ]; then
    printf 'Enter a name for the new user: '
    read -r USER_NAME
fi

# Keeps the name within what useradd accepts, rather than letting it fail later
case "$USER_NAME" in
    '')
        echo "Error: No user name given." >&2
        exit 1
        ;;
    *[!a-z0-9_-]*|[!a-z_]*)
        echo "Error: Invalid user name: \"$USER_NAME\"" >&2
        echo "    Use lowercase letters, digits, '_' and '-', starting with a letter." >&2
        exit 1
        ;;
esac

echo ":: Setting up the user \"$USER_NAME\""

if id "$USER_NAME" > /dev/null 2>&1; then
    echo " -> The user already exists, updating its groups"
else
    echo " -> Creating the user"
    if ! useradd -m -s /bin/bash "$USER_NAME"; then
        echo "Error: Failed to create the user." >&2
        exit 1
    fi

    # NOTE: Reported rather than ignored. Without a terminal, as when the
    #       command is driven from a script or over adb, passwd aborts and
    #       useradd's locked password stays in place. That is the safe outcome,
    #       so it is not an error, but the closing "is ready" line used to claim
    #       an account the user could not log in to.
    echo " -> Set a password for \"$USER_NAME\""
    if ! passwd "$USER_NAME"; then
        PASSWORD_SET=no
    fi
fi

# The inet group is what android checks before allowing a socket
if ! getent group inet > /dev/null 2>&1; then
    echo " -> Creating the inet group"
    groupadd -g 3003 inet
fi

echo " -> Adding the user to the inet group (needed for networking)"
usermod -aG inet "$USER_NAME"

# sudo is only useful if it is actually installed
if getent group sudo > /dev/null 2>&1; then
    echo " -> Adding the user to the sudo group"
    usermod -aG sudo "$USER_NAME"
fi

echo ":: The user \"$USER_NAME\" is ready"
echo " -> Switch to it with: su - $USER_NAME"

# The account works, but nothing can authenticate as it until a password exists
if [ "$PASSWORD_SET" = "no" ]; then
    echo " -> No password was set, so the account stays locked: \"su - $USER_NAME\""
    echo "    works from root, but ssh and login do not. Set one with:"
    echo "    debdroid.sh run passwd $USER_NAME"
fi
