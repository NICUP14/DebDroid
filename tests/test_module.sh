#!/bin/sh
# test_module.sh: Checks the flashable module against how root managers install it
#
# Magisk, KernelSU and APatch share one installer lineage, and its behaviour
# decides whether this module installs at all. None of it can be exercised on a
# build machine: there is no root manager here, so the rules it imposes are
# asserted against the files instead.
#
# Every check below is a bug that passed the whole suite and then failed on a
# real flash.

# shellcheck disable=SC2034
# BUSYBOX is read by the helpers this file calls.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX

MODULE_DIR=$REPO_DIR/module

t_section "the zip must not look like an old-style module"

# NOTE: This is the one that mattered most. The root manager decides a zip is a
#       legacy module purely on whether it contains a top-level "install.sh":
#
#           is_legacy_script() {
#               unzip -l "$ZIPFILE" install.sh | grep -q install.sh
#           }
#
#       On a match it extracts only module.prop, install.sh, uninstall.sh and
#       common/*, then SOURCES that install.sh as the module's own install
#       script and never runs customize.sh. DebDroid's installer ran with none
#       of its payload beside it, failed its own file check and called
#       "exit 1", which killed the whole installer because it had been sourced.
#       The module could not be flashed by any root manager.
if $BUSYBOX grep -q 'install\.sh "\$STAGING"/debdroid-install\.sh' \
        "$MODULE_DIR"/build.sh; then
    t_ok "the installer is staged under a name that is not install.sh"
else
    t_no "the installer is staged under a name that is not install.sh" \
        "a top-level install.sh makes the root manager source it instead of" \
        "customize.sh, and the module cannot install at all"
fi

# The build refuses rather than producing a zip that cannot be flashed
if $BUSYBOX grep -q 'if \[ -e "\$STAGING"/install\.sh \]' "$MODULE_DIR"/build.sh; then
    t_ok "the build refuses a zip with a top-level install.sh"
else
    t_no "the build refuses a zip with a top-level install.sh" \
        "the rename above is one edit away from being undone silently, so the" \
        "build has to check its own output"
fi

# customize.sh has to call the renamed file, and must not mention a bare one
if $BUSYBOX grep -q 'debdroid-install\.sh' "$MODULE_DIR"/customize.sh; then
    t_ok "customize.sh runs the renamed installer"
else
    t_no "customize.sh runs the renamed installer" \
        "it would abort, as the file it looks for is not in the zip"
fi

if $BUSYBOX grep -qE '(sh|-f) +"?[./$A-Za-z{}]*/?install\.sh' \
        "$MODULE_DIR"/customize.sh; then
    t_no "customize.sh never refers to a plain install.sh" \
        "the zip ships debdroid-install.sh, so a reference to install.sh is" \
        "either stale or reintroduces the legacy-module collision"
else
    t_ok "customize.sh never refers to a plain install.sh"
fi

t_section "what the root manager does to the payload before customize.sh"

# NOTE: The root manager applies its own default permissions, which set every
#       file to 0644, BEFORE sourcing customize.sh. The bundled busybox then
#       looks absent to "[ -x ]", and on a fresh device there is no installed
#       copy to fall back to, so the install died with "Cannot locate the
#       busybox binary". It only ever worked where a previous install had left
#       an executable busybox behind, which is why a fresh flash was the case
#       that failed.
if $BUSYBOX grep -q 'chmod 755 \./bin/busybox' "$REPO_DIR"/install.sh; then
    t_ok "install.sh restores the execute bit on the bundled busybox"
else
    t_no "install.sh restores the execute bit on the bundled busybox" \
        "a module flash delivers it at 0644, so a fresh install cannot run it"
fi

# The chmod is useless unless it happens before the test that reads it
chmod_line=$($BUSYBOX grep -n 'chmod 755 \./bin/busybox' "$REPO_DIR"/install.sh |
    $BUSYBOX cut -d: -f1)
test_line=$($BUSYBOX grep -n '\[ -x \./bin/busybox \]' "$REPO_DIR"/install.sh |
    $BUSYBOX cut -d: -f1)
if [ -n "$chmod_line" ] && [ -n "$test_line" ] && [ "$chmod_line" -lt "$test_line" ]; then
    t_ok "the execute bit is restored before it is tested"
else
    t_no "the execute bit is restored before it is tested" \
        "chmod at line ${chmod_line:-none}, test at line ${test_line:-none}"
fi

t_section "the flash console only shows stdout"

# NOTE: "error" and "warn" write to stderr, which the root manager's console
#       discards, so a failed flash printed the abort line and nothing else: no
#       reason and none of the installer's guidance. The commonest real case is
#       a device with no usable network and no bundled image, where the
#       installer says exactly where to put the image by hand.
if $BUSYBOX grep -q 'debdroid-install\.sh 2>&1' "$MODULE_DIR"/customize.sh; then
    t_ok "the installer's stderr reaches the flash console"
else
    t_no "the installer's stderr reaches the flash console" \
        "without 2>&1 every error and warning is invisible during a flash"
fi

t_section "the boot hook survives the permission pass"

# NOTE: customize.sh sets service.sh to 0755 and then ran
#       set_perm_recursive over the whole module, which sets every file to 0644
#       and undid it. Magisk tolerates 0644 because it runs service scripts
#       through "sh", so nothing broke, but the explicit permission was doing
#       nothing at all.
recursive_line=$($BUSYBOX grep -n 'set_perm_recursive "\$MODPATH"' \
    "$MODULE_DIR"/customize.sh | $BUSYBOX cut -d: -f1)
service_line=$($BUSYBOX grep -n 'set_perm "\$MODPATH/service\.sh"' \
    "$MODULE_DIR"/customize.sh | $BUSYBOX cut -d: -f1)
if [ -n "$recursive_line" ] && [ -n "$service_line" ] &&
        [ "$recursive_line" -lt "$service_line" ]; then
    t_ok "service.sh is given its mode after the recursive pass"
else
    t_no "service.sh is given its mode after the recursive pass" \
        "recursive at line ${recursive_line:-none}, service.sh at ${service_line:-none}:" \
        "the recursive call resets every file to 0644"
fi

t_section "the built zip"

# The checks above read the build script; this one reads what it produces, which
# is what a root manager actually sees
if ! command -v zip > /dev/null 2>&1; then
    t_skip "the built zip has no top-level install.sh" \
        "\"zip\" is not installed, so the archive cannot be built here"
    t_skip "the built zip carries customize.sh and module.prop" \
        "\"zip\" is not installed"
else
    zip_out=$TEST_TMP/DebDroid-module.zip
    if sh "$MODULE_DIR"/build.sh "$zip_out" > "$TEST_TMP"/build.log 2>&1; then
        entries=$(unzip -Z1 "$zip_out" 2> /dev/null)

        # NOTE: Matched anchored, as only a top-level install.sh triggers the
        #       legacy path; one nested in a directory is harmless.
        if echo "$entries" | $BUSYBOX grep -qx 'install.sh'; then
            t_no "the built zip has no top-level install.sh" \
                "the root manager would source it and never run customize.sh"
        else
            t_ok "the built zip has no top-level install.sh"
        fi

        missing=
        for required in customize.sh module.prop debdroid-install.sh; do
            echo "$entries" | $BUSYBOX grep -qx "$required" ||
                missing="$missing $required"
        done
        if [ -z "$missing" ]; then
            t_ok "the built zip carries customize.sh and module.prop"
        else
            t_no "the built zip carries customize.sh and module.prop" \
                "missing:$missing"
        fi
    else
        t_no "the built zip has no top-level install.sh" \
            "module/build.sh failed" "$($BUSYBOX tail -n 5 "$TEST_TMP"/build.log)"
        t_skip "the built zip carries customize.sh and module.prop" \
            "the build failed"
    fi
fi

t_summary
