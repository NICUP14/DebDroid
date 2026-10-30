#!/bin/sh
# vncserver.sh: Starts an XFCE4 desktop over VNC inside the environment
#
# Pair it with background mode, or the desktop stops when the session exits:
#
#   debdroid.sh start
#   debdroid.sh attach sh /sdcard/debdroid/command/vncserver.sh
#
# NOTE: The XFCE4 power manager plugin is disabled on purpose. It assumes a
#       desktop with a real battery subsystem, and on android it can consume
#       enough memory to trigger a device reboot. This is the cause behind the
#       "system reboot due to XFCE4" entry in the troubleshooting guide.

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: Missing required super-user permissions." >&2
    exit 1
fi

# shellcheck disable=SC1091
[ -f /sdcard/debdroid/environment ] && . /sdcard/debdroid/environment

VNC_DISPLAY=${DEBDROID_VNC_DISPLAY:-1}
VNC_GEOMETRY=${DEBDROID_VNC_GEOMETRY:-1280x720}
VNC_DEPTH=${DEBDROID_VNC_DEPTH:-24}
VNC_PORT=$((5900 + VNC_DISPLAY))

echo ":: Starting the vnc desktop on display :$VNC_DISPLAY"

if ! command -v vncserver > /dev/null 2>&1; then
    echo " -> Installing tigervnc and xfce4"
    if ! apt-get install -y tigervnc-standalone-server xfce4 xfce4-terminal dbus-x11; then
        echo "Error: Failed to install the desktop packages." >&2
        echo "    Run \"apt update\" first, then try again." >&2
        exit 1
    fi
fi

# A password is required, and vncserver prompts for it on first run
if [ ! -f "$HOME"/.vnc/passwd ]; then
    echo " -> Set a vnc password (8 characters maximum)"
    mkdir -p "$HOME"/.vnc
    vncpasswd
fi

# Starts an XFCE4 session rather than the default twm
if [ ! -f "$HOME"/.vnc/xstartup ]; then
    echo " -> Writing the session startup script"
    cat > "$HOME"/.vnc/xstartup <<'STARTUP'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
[ -r "$HOME/.Xresources" ] && xrdb "$HOME/.Xresources"
exec dbus-launch --exit-with-session startxfce4
STARTUP
    chmod 755 "$HOME"/.vnc/xstartup
fi

# Disabling the plugin is what keeps a low-memory device from rebooting
power_manager=/etc/xdg/autostart/xfce4-power-manager.desktop
if [ -f "$power_manager" ]; then
    echo " -> Disabling the xfce4 power manager (it can reboot the device)"
    mv "$power_manager" "$power_manager".disabled
fi

if [ -e /tmp/.X11-unix/X"$VNC_DISPLAY" ]; then
    echo " -> A server is already running on :$VNC_DISPLAY, restarting it"
    vncserver -kill :"$VNC_DISPLAY" > /dev/null 2>&1
fi

echo " -> Starting the vnc server at $VNC_GEOMETRY"
if ! vncserver :"$VNC_DISPLAY" -geometry "$VNC_GEOMETRY" -depth "$VNC_DEPTH" \
        -localhost no; then
    echo "Error: Failed to start the vnc server." >&2
    echo "    Inspect the log in \"$HOME/.vnc\"." >&2
    exit 1
fi

echo ":: The vnc desktop is running"

for address in $(ip -o -4 addr show 2>/dev/null | awk '{ print $4 }' | cut -d / -f 1); do
    case "$address" in
        127.*) continue ;;
    esac
    echo " -> Connect a vnc client to $address:$VNC_PORT"
done

echo
echo "NOTE: Low-memory devices benefit from swap before running a desktop:"
echo "      sh /sdcard/debdroid/command/swap.sh create 2G"
echo "NOTE: The desktop stops when the environment stops. Use"
echo "      \"debdroid.sh start\" so it survives closing the terminal."
