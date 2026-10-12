#!/system/bin/sh

# Checks for the required debdroid files
for file in debdroid_env.sh debdroid_ui.sh; do
    if [ ! -f /sdcard/debdroid/"$file" ]; then
        echo "Error: Missing required file: /sdcard/debdroid/$file" >&2
        exit 1
    fi
done

# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_env.sh
# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_ui.sh
setup_colors

debdroid_run() {
    # shellcheck disable=SC2068
    sh "$DEBDROID_SDHOME"/debdroid_mgr.sh "$DEBDROID_IMG" "$DEBDROID_ENV" "$DEBDROID_BIN" "$DEBDROID_LIB" $@
}

list_scripts() {
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

  list
      Lists all command scripts in the command directory.

  command [COMMAND_NAME]
      Executes the specified command script from the command directory.
      Example: debdroid.sh command setup_user

  resize [SIZE]
      Resizes the debian image to the specified size.
      Example: debdroid.sh resize 5G

Notes:
  - Unrecognized options are treated the same as the 'run' option."

# Handles the "run" option
elif [ "$DEBDROID_OPT" = "run" ]; then
    shift
    # shellcheck disable=SC2068
    debdroid_run $@

# Handles the "list" option
elif [ "$DEBDROID_OPT" = "list" ]; then
    section "Available command scripts"
    list_scripts "$DEBDROID_CMD"
    exit

# Handles the "command" option
elif [ "$DEBDROID_OPT" = "command" ]; then
    if [ -z "$DEBDROID_SUBOPT" ]; then
        error "Missing command script name." \
            "Usage: $0 command [COMMAND_NAME]"
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

    # shellcheck disable=SC2068
    sh "$DEBDROID_SDHOME"/debdroid_mgr.sh "$DEBDROID_IMG" "$DEBDROID_ENV" "$DEBDROID_BIN" "$DEBDROID_LIB" \
        sh "$CMD"

# Handles the "resize" option
elif [ "$DEBDROID_OPT" = "resize" ]; then
    sh "$DEBDROID_SDHOME"/debdroid_resize.sh "$DEBDROID_SUBOPT"

# Handles the unknown option case
# Functions the same as "debdroid.sh run"
else
    # shellcheck disable=SC2068
    debdroid_run $@
fi