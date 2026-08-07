#!/bin/sh
set -eu

# Indagium's UI toolkit (Java/AWT via Skiko) is X11-only and needs a display bound before
# the sandbox exists. Fail loudly here instead of letting the app die mid-composition with
# a bare java.awt.HeadlessException stack trace that gives the user no next step.
#
# Note: `flatpak run --env=DISPLAY=…` cannot fix this after the fact. Flatpak decides which
# /tmp/.X11-unix/X<n> socket to bind by reading DISPLAY on the HOST, before the sandbox is
# created — setting the variable inside the sandbox is too late to change that bind decision.
if [ -z "${DISPLAY:-}" ]; then
  echo "Indagium needs an X11 display (Java/AWT has no Wayland backend; XWayland covers Wayland sessions)." >&2
  echo "DISPLAY is not set. Set it on the HOST before running flatpak, e.g.:" >&2
  echo "  DISPLAY=:0 flatpak run com.indagium.Indagium" >&2
  echo "(On a remote/VNC/xrdp/x2go session, use that session's own display number, e.g. :1.0.)" >&2
  exit 1
fi

exec /app/indagium/bin/Indagium "$@"
