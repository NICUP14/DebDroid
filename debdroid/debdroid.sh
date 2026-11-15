#!/system/bin/sh

# Checks for the required debdroid files
for file in debdroid_env.sh debdroid_ui.sh debdroid_lib.sh; do
    if [ ! -f /sdcard/debdroid/"$file" ]; then
        echo "Error: Missing required file: /sdcard/debdroid/$file" >&2
        exit 1
    fi
done

# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_env.sh
# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_ui.sh
# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_lib.sh
require_busybox
setup_colors

# Runs the manager, passing the user's command through unchanged
# NOTE: "$@" keeps each argument intact, so quotes and spaces survive
debdroid_run()
{
    sh "$DEBDROID_SDHOME"/debdroid_mgr.sh "$DEBDROID_IMG" "$DEBDROID_ENV" "$DEBDROID_BIN" "$DEBDROID_LIB" "$@"
}

# Prints the name of every command script in the given directory
list_scripts()
{
    for script in "$1"/*.sh; do
        [ -f "$script" ] || continue # Ensures that the script exists
        script=${script##*/}
        step "${script%.sh}"
    done
}

# Configures parameters
DEBDROID_OPT=$1
DEBDROID_SUBOPT=$2

# Prints HELP Usage
# Handles the "help" option
if [ "$DEBDROID_OPT" = "help" ]; then
  echo "DebDroid frontend (https://github.com/NICUP14/DebDroid)
Author: NICUP14
Version: $DEBDROID_VER

Usage:
  debdroid.sh [OPTION] [SUBOPTION] [ARGUMENTS]

Options:
  run [COMMAND...]
      Runs the default Debdroid environment.
      If COMMAND is provided, it executes that command inside the environment.
      If no command is given, an interactive shell is started.
      The environment is stopped again as soon as the session exits.

  start
      Starts the environment in the background and leaves it running.
      Servers started inside it keep running after the terminal is closed.

  attach [COMMAND...]
      Opens a session in the background environment started by 'start'.
      Attaching is fast, as the environment is already mounted.

  stop
      Stops the background environment and unmounts everything.

  status
      Reports whether an environment is running.

  probe
      Prints the installation's state as key=value lines, for programs.
      The format is stable, unlike the wording of 'status'.

  list
      Lists all command scripts in the command directory.

  command [COMMAND_NAME] [ARGUMENTS...]
      Executes the specified command script from the command directory.
      Arguments after the name are passed to the script.
      Example: debdroid.sh command user alice

  resize [SIZE]
      Resizes the debian image to the specified size.
      Example: debdroid.sh resize 5G

  swap [create [SIZE] | remove | status]
      Manages the swapfile sessions use. Runs on the android side.
      Example: debdroid.sh swap create 2G

  self-update [--check] [--rollback] [--offline FILE]
      Updates DebDroid itself. To update debian instead, run
      'debdroid.sh command update'.

  uninstall [--image] [--all] [--yes]
      Removes DebDroid. The debian image is kept unless --image is given.

Notes:
  - Unrecognized options are treated the same as the 'run' option."

# Handles the "run" option
elif [ "$DEBDROID_OPT" = "run" ]; then
    shift
    debdroid_run "$@"

# Handles the background environment options
# NOTE: The manager reads the mode from the environment, which keeps its
#       positional arguments compatible with earlier releases.
elif [ "$DEBDROID_OPT" = "start" ] || [ "$DEBDROID_OPT" = "stop" ] ||
        [ "$DEBDROID_OPT" = "status" ] || [ "$DEBDROID_OPT" = "attach" ]; then
    DEBDROIDMGR_MODE=$DEBDROID_OPT
    export DEBDROIDMGR_MODE
    shift
    debdroid_run "$@"

# Handles the "probe" option
# NOTE: Not a manager mode. The probe has to run where nothing is installed yet,
#       and the manager refuses to start without root and a built architecture,
#       which are among the facts the probe exists to report.
elif [ "$DEBDROID_OPT" = "probe" ]; then
    sh "$DEBDROID_SDHOME"/debdroid_probe.sh

# Handles the "list" option
elif [ "$DEBDROID_OPT" = "list" ]; then
    section "Available command scripts"
    list_scripts "$DEBDROID_CMD"
    exit

# Handles the "command" option
elif [ "$DEBDROID_OPT" = "command" ]; then
    if [ -z "$DEBDROID_SUBOPT" ]; then
        error "Missing command script name." \
            "Usage: $0 command [COMMAND_NAME] [ARGUMENTS...]"
        exit 1
    fi

    # Rejects path traversal attempts
    case "$DEBDROID_SUBOPT" in
        *[!A-Za-z0-9_-]*)
            error "Invalid command script name: \"$DEBDROID_SUBOPT\"" \
                "Names may only contain letters, digits, '_' and '-'."
            exit 1
            ;;
    esac

    CMD="$DEBDROID_CMD/$DEBDROID_SUBOPT.sh"
    if [ ! -f "$CMD" ]; then
        error "No such command script: \"$CMD\"" \
            "Run \"$0 list\" to see the available command scripts."
        exit 1
    fi

    # Runs inside the background environment when there is one
    # NOTE: Without this, "debdroid.sh start" followed by "command sshserver"
    #       was refused, because the command took the foreground path and the
    #       image was already in use. That sequence is the first example in the
    #       README, so it had never worked. Worse, where it did run, a
    #       foreground session stops the environment when the command exits,
    #       which killed the very server it had just started.
    if read_supervisor "$DEBDROID_PID" 2> /dev/null; then
        DEBDROIDMGR_MODE=attach
        export DEBDROIDMGR_MODE
    fi

    # Forwards anything after the script name, so a command script can be driven
    # without a prompt
    # NOTE: An exported variable cannot be used for this. The session starts
    #       through "su -", which resets the environment, so "DEBDROID_USER=x
    #       debdroid.sh command user" never reached the script and it prompted
    #       anyway. Arguments survive, as the manager quotes them for the
    #       single "su -c" string it runs.
    shift 2
    debdroid_run sh "$CMD" "$@"

# Handles the "resize" option
elif [ "$DEBDROID_OPT" = "resize" ]; then
    sh "$DEBDROID_SDHOME"/debdroid_resize.sh "$DEBDROID_SUBOPT"

# Handles the "swap" option
# NOTE: Dispatched here rather than through "command", whose scripts run inside
#       the chroot; swap belongs to the device and has to run outside it.
elif [ "$DEBDROID_OPT" = "swap" ]; then
    shift
    sh "$DEBDROID_SDHOME"/debdroid_swap.sh "$@"

# Handles the "self-update" option
# NOTE: Named apart from "command update", which updates debian rather than
#       debdroid itself. The two were too easy to confuse.
elif [ "$DEBDROID_OPT" = "self-update" ]; then
    shift
    sh "$DEBDROID_SDHOME"/debdroid_update.sh "$@"

# Handles the "uninstall" option
elif [ "$DEBDROID_OPT" = "uninstall" ]; then
    shift
    sh "$DEBDROID_SDHOME"/debdroid_uninstall.sh "$@"

# Handles the unknown option case
# Functions the same as "debdroid.sh run"
else
    debdroid_run "$@"
fi