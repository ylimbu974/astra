#!/usr/bin/env bash
# Astra "Pi only" setup: the Raspberry Pi is both the registry server AND the desktop
# you control from the phone. No Windows PC needed.
#
# Run it on the Pi, from the Pi's desktop terminal or over SSH, as your normal user:
#     cd ~/astra && bash install-pi.sh
# It is safe to run again: it keeps existing .env files and only fills in what's missing.
set -euo pipefail
cd "$(dirname "$0")"
ASTRA="$(pwd)"
step() { printf '\n\033[1;34m== %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$1"; }
die()  { printf '\033[1;31mXX %s\033[0m\n' "$1"; exit 1; }

[ "$(id -u)" -ne 0 ] || die "Run this as your normal user (without sudo). It asks for sudo itself when needed."
[ -d registry ] && [ -d host-agent ] && [ -d web-viewer ] || die "Run this from the astra folder (it needs registry/, host-agent/ and web-viewer/ next to it)."

step "1/6 Checking Tailscale"
command -v tailscale >/dev/null || die "Tailscale is not installed. Run:  curl -fsSL https://tailscale.com/install.sh | sh  then  sudo tailscale up"
PI_IP="$(tailscale ip -4 2>/dev/null | head -n1 || true)"
[ -n "$PI_IP" ] || die "Tailscale is not connected. Run:  sudo tailscale up  and sign in, then run this again."
echo "Pi Tailscale address: $PI_IP"

step "2/6 Installing system packages (python3-venv, python3-tk, build tools)"
sudo apt-get update -qq
sudo apt-get install -y python3-venv python3-tk python3-dev gcc sqlite3 zenity

step "3/6 Docker for the registry"
if ! command -v docker >/dev/null; then
    curl -fsSL https://get.docker.com | sh
fi
sudo usermod -aG docker "$USER" || true

step "4/6 Registry settings and start"
if [ ! -f registry/.env ]; then
    KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
    sed -e "s|^HOST_API_KEY=.*|HOST_API_KEY=$KEY|" \
        -e "s|^PI_TAILSCALE_IP=.*|PI_TAILSCALE_IP=$PI_IP|" registry/.env.example > registry/.env
    chmod 600 registry/.env
    echo "Created registry/.env with a new secret key."
else
    sed -i "s|^PI_TAILSCALE_IP=.*|PI_TAILSCALE_IP=$PI_IP|" registry/.env
    echo "Kept your existing registry/.env (updated PI_TAILSCALE_IP)."
fi
KEY="$(grep -E '^HOST_API_KEY=' registry/.env | head -n1 | cut -d= -f2- | sed 's/ #.*//' | tr -d '[:space:]')"
[ -n "$KEY" ] && [ "$KEY" != "change-me" ] || die "HOST_API_KEY in registry/.env is empty or still change-me. Fix it and run this again."
(cd registry && sudo docker compose up -d --build)
echo -n "Waiting for the registry"
for _ in $(seq 1 30); do
    if curl -fsS "http://$PI_IP:8000/health" >/dev/null 2>&1; then echo " ok"; break; fi
    echo -n "."; sleep 2
done
curl -fsS "http://$PI_IP:8000/health" >/dev/null 2>&1 || warn "Registry is not answering yet. Check: cd registry && sudo docker compose logs"

step "5/6 Host agent settings and libraries"
if [ ! -f host-agent/.env ]; then
    sed -e "s|^DEVICE_NAME=.*|DEVICE_NAME=$(hostname)|" \
        -e "s|^REGISTRY_URL=.*|REGISTRY_URL=http://$PI_IP:8000|" \
        -e "s|^HOST_API_KEY=.*|HOST_API_KEY=$KEY|" \
        -e "s|^MAX_FPS=.*|MAX_FPS=10              # a Pi 4 copes better with 10 than 15|" \
        -e "s|^AUTO_ALLOW_PAIRED_PHONES=.*|AUTO_ALLOW_PAIRED_PHONES=1|" \
        host-agent/.env.example > host-agent/.env
    chmod 600 host-agent/.env
    echo "Created host-agent/.env (device name: $(hostname))."
else
    echo "Kept your existing host-agent/.env."
fi
chmod +x host-agent/start-host.sh
if [ ! -x host-agent/.venv/bin/python ]; then
    python3 -m venv host-agent/.venv
    host-agent/.venv/bin/python -m pip install --upgrade pip
    host-agent/.venv/bin/python -m pip install -r host-agent/requirements.txt
fi

step "6/6 Start Astra automatically when the desktop logs in"
mkdir -p "$HOME/.config/autostart"
cat > "$HOME/.config/autostart/astra-host.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Astra Host
Comment=Lets your phone see and control this Pi's desktop (asks Allow every time)
Exec=$ASTRA/host-agent/start-host.sh
Terminal=false
X-GNOME-Autostart-enabled=true
EOF
echo "Added $HOME/.config/autostart/astra-host.desktop"

# ---- Checks that need you to change a Pi setting -------------------------------------------
NEED_REBOOT=0
if [ "$(systemctl get-default 2>/dev/null)" != "graphical.target" ]; then
    warn "This Pi does not boot to a desktop. Astra needs Raspberry Pi OS *with desktop* (not Lite).
   If you have the desktop installed: sudo raspi-config > System Options > Boot / Auto Login > Desktop Autologin."
fi
if pgrep -x labwc >/dev/null || pgrep -x wayfire >/dev/null || [ "${XDG_SESSION_TYPE:-}" = "wayland" ]; then
    warn "The desktop is running Wayland. Astra needs X11. Switch once:
   sudo raspi-config > Advanced Options > Wayland > X11 (W1), then Finish and reboot."
    NEED_REBOOT=1
fi
if ! grep -qsE '^\s*autologin-user\s*=' /etc/lightdm/lightdm.conf; then
    warn "Desktop autologin may be off. If the Pi's desktop does not log in by itself after a reboot, Astra won't start.
   Turn it on: sudo raspi-config > System Options > Boot / Auto Login > Desktop Autologin."
fi

step "Done"
cat <<EOF
Registry (W1 page):  http://$PI_IP:8000/
Viewer on the phone: http://$PI_IP:8765/   (or http://$(hostname):8765/ with MagicDNS)

Next: reboot the Pi (sudo reboot). After it logs in, the Astra window opens on the Pi's desktop
with a QR code and a PIN. Then pair from the phone (docs/SETUP.md, "Pi only", step 6).
EOF
[ "$NEED_REBOOT" -eq 0 ] || warn "Remember to switch to X11 first (see above), then reboot."
