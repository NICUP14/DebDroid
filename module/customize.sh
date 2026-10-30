# customize.sh: Installs DebDroid from the flashed module
#
# Sourced by the Magisk, KernelSU and APatch installers, all of which read this
# module format, so one zip covers every root solution.
#
# NOTE: This module is an installer and a boot hook, nothing more. It mounts
#       nothing over /system and replaces no system file, which keeps it well
#       clear of the one change that can leave a device unbootable.

# shellcheck shell=sh
# The installer provides ui_print, abort, set_perm, MODPATH, ARCH and API, and
# reads the four flags set below back out of this script
# shellcheck disable=SC2154,SC2034

SKIPMOUNT=true
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=true

ui_print " "
ui_print "  DebDroid"
ui_print "  Debian on rooted Android"
ui_print " "

# DebDroid itself only supports arm64 binaries so far
if [ "$ARCH" != "arm64" ]; then
    ui_print "- Detected architecture: $ARCH"
    abort "! DebDroid currently ships arm64 binaries only."
fi

# Android 5 is the floor, as the bundled binaries target api 21
if [ "$API" -lt 21 ]; then
    abort "! Android 5.0 (api 21) or later is required, found api $API."
fi

ui_print "- Architecture: $ARCH (api $API)"

# The installer needs the repository layout, which the zip reproduces
if [ ! -f "$MODPATH/install.sh" ]; then
    abort "! The module is incomplete: install.sh is missing."
fi

ui_print "- Running the DebDroid installer"
ui_print " "

# NOTE: The installer prompts for an image size on a fresh install, which it
#       cannot do here: the recovery console has no usable stdin. Skipping the
#       prompt keeps the default size, and the resizer can change it later.
cd "$MODPATH" || abort "! Could not enter $MODPATH"
if ! DEBDROID_NONINTERACTIVE=1 sh ./install.sh; then
    abort "! The DebDroid installer failed."
fi

ui_print " "
ui_print "- Enabling the boot hook"

# The boot hook is this module's service.sh, which Magisk runs at late_start
if [ -f "$MODPATH/boot/debdroid.service.sh" ]; then
    cp "$MODPATH/boot/debdroid.service.sh" "$MODPATH/service.sh"
    set_perm "$MODPATH/service.sh" 0 0 0755
    ui_print "  The environment will start at boot."
    ui_print "  Disable it by removing this module."
else
    ui_print "  ! The boot hook is missing, starting at boot is unavailable."
fi

# The module's own copy of the payload is not needed once it is installed
# NOTE: ${MODPATH:?} rather than $MODPATH, so an empty value aborts instead of
#       expanding these into /bin, /lib and friends on the live system.
rm -rf "${MODPATH:?}/img" "${MODPATH:?}/bin" "${MODPATH:?}/lib" \
       "${MODPATH:?}/debdroid" "${MODPATH:?}/command" "${MODPATH:?}/tests" \
       "${MODPATH:?}/install.sh"

set_perm_recursive "$MODPATH" 0 0 0755 0644

ui_print " "
ui_print "- Installed"
ui_print " "
ui_print "  Start it now:    debdroid.sh start"
ui_print "  Open a session:  debdroid.sh attach"
ui_print "  Check the setup: sh /sdcard/debdroid/tests/device.sh"
ui_print " "
ui_print "  Scripts: /sdcard/debdroid"
ui_print "  Binaries: /data/local/debdroid"
ui_print " "
