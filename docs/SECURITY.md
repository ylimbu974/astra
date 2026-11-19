# Threat check: what is and isn't exposed (Kanban T-31)

**Rule:** nothing is reachable from the public internet. No port forwarding, no public DNS, no third-party relay.
All traffic between phone, Pi and PC runs inside Tailscale (WireGuard, end-to-end encrypted).

## Listening ports

| Device | Port | Bound to | Who can reach it | What it allows |
|---|---|---|---|---|
| Pi | 8000/tcp | The Pi's Tailscale IP only | Devices on your tailnet | Read the device list and W1 page. Phones can start pairings. Host agents (with `HOST_API_KEY`) can check in, approve pairings and record sessions. |
| PC | 8765/tcp | The PC's Tailscale IP only (default), plus a firewall rule limited to `100.64.0.0/10` | Devices on your tailnet | The viewer page, `/health`, `/api/info`, pairing requests, and the session WebSocket. Every session still needs a pairing or PIN **and** Allow at the PC. |

Your home router needs **no** changes. Check it once (T-31): from a phone on mobile data **with Tailscale off**, neither
`http://<your-public-ip>:8000` nor `:8765` should answer, and neither should the Pi's or PC's LAN address from another LAN device.

## Layers of protection on a session
1. **Tailscale:** only your devices are on the network at all.
2. **Pairing:** QR (name and address, never the PIN) + 6-digit PIN that rotates every 10 minutes, 3 tries then a 5-minute lock.
3. **Phone token:** issued once at pairing. The Pi stores only its SHA-256 hash, and it's useless until the PC approves the pairing.
4. **Approval for every session (P2):** on by default. The popup auto-denies after 60 seconds. The one exception is
   `AUTO_ALLOW_PAIRED_PHONES=1` (set by `install-pi.sh` for the Pi-only setup, off everywhere else): a phone that was
   already paired with the PIN **and** Allow connects without the popup, so an unattended Pi can be reached. The red
   banner still shows, the Pi audit log records `auto_allowed`, and PIN-only browser sessions still need Allow.
5. **Visible session:** red banner on the PC and the P3 window; either side can end it.
6. **Input whitelist:** the PC accepts only mouse, scroll, text and a fixed list of keys. Anything else is rejected.
7. **Audit log** on the Pi: registrations, pairing requests, approvals, rejections, PIN failures, sessions.
8. **No screen content stored**, anywhere. Session rows hold times, reason and a byte count only.

## Known limits (accepted for the MVP)
* `HOST_API_KEY` is one shared secret for all your PCs. Someone who takes it from one PC's `.env` could pretend to be a host to the registry. They still couldn't control any PC without Allow on that PC.
* The W1 page and `GET /api/devices` are readable by anything on your tailnet (names and online status only). That's what makes the manual pairing path work.
* Without `tailscale cert`, traffic is plain HTTP **inside** Tailscale's encrypted tunnel. Turn on TLS (SETUP.md §3.6) to get the "TLS on the connection" item from the brief.
* If you share your tailnet with others, they're inside the first layer. Use Tailscale ACLs to limit who can reach ports 8000 and 8765.
* With `AUTO_ALLOW_PAIRED_PHONES=1`, a stolen, unlocked phone that still has its pairing can open the Pi's desktop
  with nobody there to say no. If a phone is lost, remove its pairing at once (RUNBOOK, "Remove a phone's access").
* The web viewer's PIN path lets anyone on the tailnet who can see the PC's screen start a session request. It still needs Allow.
