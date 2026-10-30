# DebDroid test and check targets
#
# The scripts need no build step, so these only check and test them.
#
#   make check    Syntax and shellcheck, no device needed
#   make test     Host test suite, no root and no device needed
#   make all      Both of the above

SCRIPTS = install.sh $(wildcard debdroid/*.sh) $(wildcard command/*.sh) \
          $(wildcard tests/*.sh) $(wildcard boot/*.sh) bin/debinit

.POSIX:
.PHONY: all check syntax shellcheck test test-device help

all: check test

help:
	@echo 'make check        Syntax check and shellcheck every script'
	@echo 'make test         Run the host test suite'
	@echo 'make all          Both of the above'
	@echo 'make test-device  Print how to run the device suite'

check: syntax shellcheck

# Both shells are checked, as the product targets whatever /system/bin/sh is
syntax:
	@for file in $(SCRIPTS); do \
	    sh -n "$$file" || exit 1; \
	    bash --posix -n "$$file" || exit 1; \
	done
	@echo 'syntax: ok'

# Excluded checks, each a deliberate pattern rather than an oversight:
#   SC1090/SC1091  sourced files shellcheck cannot follow at parse time
#   SC2016         single-quoted awk programs, which must not expand
#   SC2153         distinct variables it mistakes for typos of each other
#   SC2059         the test helper forwarding a printf format it was handed
shellcheck:
	@if command -v shellcheck > /dev/null 2>&1; then \
	    shellcheck -s sh -e SC1090,SC1091,SC2016,SC2153,SC2059 $(SCRIPTS) \
	        && echo 'shellcheck: ok'; \
	else \
	    echo 'shellcheck: not installed, skipping'; \
	fi

test:
	@sh tests/run.sh

# The device suite has to run on the device, so this only prints the command
test-device:
	@echo 'Run the device suite on a connected device:'
	@echo
	@echo '  adb shell su -c "sh /sdcard/debdroid/tests/device.sh"'
	@echo
	@echo 'Add --session to also start one real session and verify cleanup:'
	@echo
	@echo '  adb shell su -c "sh /sdcard/debdroid/tests/device.sh --session"'
