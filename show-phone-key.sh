#!/usr/bin/env bash
# Shows a QR code for "Share this phone" in the Astra app. Run it on the Pi:
#     bash ~/astra/show-phone-key.sh
# The phone scans it once to learn the Pi's address and the key that lets it share its screen.
# Treat the QR like a password: anyone on your tailnet with it could add a phone.
set -euo pipefail
cd "$(dirname "$0")"
ENV=registry/.env
[ -f "$ENV" ] || { echo "No $ENV yet. Run install-pi.sh first."; exit 1; }
val() { grep -E "^$1=" "$ENV" | head -n1 | cut -d= -f2- | sed 's/ #.*//' | tr -d '[:space:]'; }
KEY="$(val HOST_API_KEY)"
IP="$(val PI_TAILSCALE_IP)"
[ -n "$KEY" ] && [ "$KEY" != "change-me" ] || { echo "HOST_API_KEY in $ENV is not set."; exit 1; }
URL="http://$IP:8000"
PAYLOAD="{\"astra_host\":1,\"registry\":\"$URL\",\"key\":\"$KEY\"}"

PY=python3
[ -x host-agent/.venv/bin/python ] && PY=host-agent/.venv/bin/python
if "$PY" -c "import qrcode" 2>/dev/null; then
    "$PY" - "$PAYLOAD" <<'PYEOF'
import sys, qrcode
qr = qrcode.QRCode(border=2)
qr.add_data(sys.argv[1])
qr.print_ascii(invert=True)
PYEOF
else
    echo "(Could not draw the QR: the qrcode library is missing. Use the key below instead.)"
fi
echo
echo "In the Astra app on the phone you want to share: Share this phone > Scan key QR."
echo "No camera? Paste this key instead:  $KEY"
echo "Pi address in the app: $URL"
