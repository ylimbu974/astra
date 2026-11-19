#!/usr/bin/env bash
# Start the Astra Host Agent on a Raspberry Pi (or any Linux desktop running X11).
# First run creates a virtual environment and installs the pinned libraries.
# install-pi.sh makes this start by itself every time the Pi's desktop logs in.
cd "$(dirname "$0")" || exit 1
LOG="${XDG_CACHE_HOME:-$HOME/.cache}/astra-host.log"
mkdir -p "$(dirname "$LOG")"

popup() {  # started from the desktop autostart (no terminal): show the message in a window
    if [ ! -t 1 ] && command -v zenity >/dev/null 2>&1; then
        zenity --error --title="Astra Host" --width=480 --text="$1" 2>/dev/null
    fi
}
say() { echo "$1"; popup "$1"; }

if [ ! -f .env ]; then
    say "No .env file yet in $(pwd). Run install-pi.sh first, or copy .env.example to .env and fill it in."
    exit 1
fi

if [ ! -x .venv/bin/python ]; then
    echo "Creating virtual environment (first run, takes a few minutes on a Pi)..."
    python3 -m venv .venv || { say "Could not create the virtual environment. Run: sudo apt install python3-venv"; exit 1; }
    .venv/bin/python -m pip install --upgrade pip
    .venv/bin/python -m pip install -r requirements.txt || { say "Installing the libraries failed. See the messages above."; rm -rf .venv; exit 1; }
fi

# Try a few times: right after boot, Tailscale or the Pi registry can take a minute to come up.
for attempt in 1 2 3 4 5 6; do
    .venv/bin/python -m host_agent 2>&1 | tee -a "$LOG"
    code=${PIPESTATUS[0]}
    [ "$code" -eq 0 ] && exit 0          # "Stop host" was pressed
    if [ "$code" -eq 3 ]; then           # Wayland desktop or no desktop: retrying won't help
        popup "$(tail -n 7 "$LOG")"
        exit 3
    fi
    echo "Astra stopped with an error (attempt $attempt of 6). Trying again in 20 seconds..." | tee -a "$LOG"
    sleep 20
done
say "Astra could not start. The last lines of $LOG were:
$(tail -n 6 "$LOG")"
exit 1
