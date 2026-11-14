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

# NOTE: These four are the old-style module variables. Magisk reads them only on
#       the legacy install path and ignores them for a customize.sh module, so
#       they document intent rather than enforce it. What actually holds:
#       nothing is mounted over /system because the module ships no "system"
#       directory at all, and the boot hook runs because "service.sh" exists,
#       not because LATESTARTSERVICE is set. They are kept because other root
#       managers sharing this format may still honour them.
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
# NOTE: The installer is "debdroid-install.sh" inside the zip, not "install.sh".
#       A top-level install.sh makes Magisk treat the zip as an old-style module,
#       source that file instead of this one, and never reach customize.sh at
#       all. See the note in module/build.sh.
if [ ! -f "$MODPATH/debdroid-install.sh" ]; then
    abort "! The module is incomplete: debdroid-install.sh is missing."
fi

ui_print "- Running the DebDroid installer"
ui_print " "

# NOTE: The installer prompts for an image size on a fresh install, which it
#       cannot do here: the recovery console has no usable stdin. Skipping the
#       prompt keeps the default size, and the resizer can change it later.
# NOTE: stderr is merged into stdout deliberately. The root manager's console
#       shows only stdout, and every message from "error" and "warn" goes to
#       stderr, so a failed flash printed nothing but the abort line below: no
#       reason, and none of the guidance the installer gives for fixing it. The
#       commonest case is a device with no usable network and no bundled image,
#       where the installer says exactly where to put the image by hand.
cd "$MODPATH" || abort "! Could not enter $MODPATH"
if ! DEBDROID_NONINTERACTIVE=1 sh ./debdroid-install.sh 2>&1; then
    abort "! The DebDroid installer failed."
fi

# The module's own copy of the payload is not needed once it is installed
# NOTE: ${MODPATH:?} rather than $MODPATH, so an empty value aborts instead of
#       expanding these into /bin, /lib and friends on the live system.
rm -rf "${MODPATH:?}/img" "${MODPATH:?}/bin" "${MODPATH:?}/lib" \
       "${MODPATH:?}/debdroid" "${MODPATH:?}/command" "${MODPATH:?}/tests" \
       "${MODPATH:?}/debdroid-install.sh"

set_perm_recursive "$MODPATH" 0 0 0755 0644

ui_print " "
ui_print "- Enabling the boot hook"

# The boot hook becomes this module's service.sh, which the root manager runs
# late in boot
# NOTE: Installed after set_perm_recursive, not before it. That call sets every
#       file under the module to 0644, which silently undid the 0755 set here.
#       Magisk runs service scripts through "sh" so it tolerated the 0644, but
#       the explicit permission was doing nothing.
if [ -f "$MODPATH/boot/debdroid.service.sh" ]; then
    cp "$MODPATH/boot/debdroid.service.sh" "$MODPATH/service.sh"
    set_perm "$MODPATH/service.sh" 0 0 0755
    ui_print "  The environment will start at boot."
    ui_print "  Disable it by removing this module."
else
    ui_print "  ! The boot hook is missing, starting at boot is unavailable."
fi

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
