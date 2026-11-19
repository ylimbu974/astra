# Runbook: start, pair, troubleshoot (Kanban T-36)

## Start
* **Pi:** starts by itself. Check with `docker compose ps` in `~/astra-registry`, or `curl http://pi-server:8000/health` from any tailnet device.
* **PC:** double-click `host-agent\start-host.bat`. The P1 window shows **TAILSCALE: CONNECTED · PI REGISTRY: REACHABLE** in green when everything is fine.
* **Pi only:** the registry and the Astra window both start by themselves after the Pi's desktop logs in. Start the window by hand with `~/astra/host-agent/start-host.sh`. Registry: `cd ~/astra/registry && sudo docker compose ps`.
* **Stop a PC:** **Stop host** in P1 (or close the window). The PC shows offline on the Pi right away.

## Pair a phone (once per phone and PC)
App: **Add device > Scan QR code**, point at the QR in P1, type the PIN shown in P1, tap **Pair**, then tap **Allow** on the PC.
No camera: **Enter code manually**, then type the PC name (as in `DEVICE_NAME`) and the PIN.
Browser without the app: open `http://<pc-name>:8765/` and type the PIN. Each session needs the PIN and Allow.

## Remove a phone's access
In the app: **Settings > Remove a device**. That also revokes the pairing on the Pi.
If the phone is lost: on the Pi, run `sqlite3 data/registry.db "DELETE FROM pairings WHERE phone_id=(SELECT id FROM phones WHERE name='Lost phone');"`.
Even with a valid pairing, nothing happens until someone taps Allow on the PC.

## The five most common failures

| What you see | Likely cause | Fix |
|---|---|---|
| **A1 / E2: "Tailscale is not connected"** | The Tailscale VPN is off on the phone | Tap **Open Tailscale app**, switch it on, come back. A1 re-runs by itself. |
| **A1: "Pi registry unreachable"** | Pi off, container stopped, or wrong address | On the Pi: `docker compose ps` and `docker compose logs`. Check `PI_TAILSCALE_IP` matches `tailscale ip -4`. In the app, tap the red row to fix the address (`http://pi-server:8000`). |
| **PC shows Offline (E1)** | Host agent not running, PC asleep, or wrong `HOST_API_KEY` | Look at P1's status line. "PI REGISTRY: UNREACHABLE" means a wrong `REGISTRY_URL` or key, so compare `.env` with the Pi's `.env`. Disable sleep while you need remote access. |
| **"Could not reach the PC" / spinner forever on C1** | Windows firewall is blocking port 8765 | Add the firewall rule from SETUP.md §3.4. Test from the phone browser at `http://<pc-name>:8765/health`. |
| **"That PIN did not match" / locked (E3)** | Old PIN (it changes every 10 minutes) or 3 wrong tries | Read the PIN fresh from P1. After 3 wrong tries, wait 5 minutes (P1 shows the countdown) or press **New PIN**. |

Also:
* **"The PC did not approve" (E4):** Deny was tapped, or nobody answered within 60 seconds. Someone must be at the PC; that's by design.
* **"This phone is not paired":** the pairing was removed, or the token went unused longer than `SESSION_TOKEN_TTL_HOURS` (30 days). Pair again.
* **Clicks land in the wrong place:** start the agent fresh after changing display scaling. Only the primary monitor is supported.
* **Ctrl+Alt+Del opens Task Manager:** Windows doesn't let normal apps send the real Ctrl+Alt+Del, so the button sends Ctrl+Shift+Esc instead. You can't use it to unlock a locked PC remotely in the MVP.
* **Can't control an admin window (UAC prompt, Task Manager running as admin):** Windows blocks input from a normal-user app into elevated windows. Run `start-host.bat` as administrator if you need that.
* **Choppy picture on mobile data:** pick **Low** under Stream quality in Settings, or lower `MAX_FPS` / `FRAME_QUALITY` in the PC's `.env`.

Pi only:
* **Astra says the desktop is "running Wayland":** `sudo raspi-config` > Advanced Options > Wayland > X11, then reboot.
* **The Astra window doesn't appear after a reboot:** turn on Desktop Autologin (`sudo raspi-config` > System Options > Boot / Auto Login), and read `~/.cache/astra-host.log`.
* **Black or tiny picture with no monitor plugged in:** set `sudo raspi-config` > Display Options > VNC Resolution to 1280x720 and reboot, or use an HDMI dummy plug.
* **The app connects without asking on the Pi:** that's `AUTO_ALLOW_PAIRED_PHONES=1` in `~/astra/host-agent/.env`, set by `install-pi.sh` so an unattended Pi can be reached. Set it to `0` to require Allow every time.

## Logs
* Pi: `docker compose logs --tail 100`. The audit trail is in the `audit_log` table: `sqlite3 data/registry.db "SELECT created_at,event,detail FROM audit_log ORDER BY id DESC LIMIT 30;"`
* PC: the console window behind `start-host.bat`.
* Pi only: `~/.cache/astra-host.log` for the host agent.
