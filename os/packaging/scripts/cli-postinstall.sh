#!/bin/sh
cat <<'MSG'
TermBridge is installed. As your normal user (never with sudo):
  termbridge pair                             # show a QR code, scan it with the app
  systemctl --user enable --now termbridge    # optional: serve phones from every login
MSG
exit 0
