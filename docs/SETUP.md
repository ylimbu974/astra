# Setup: Pi, PC and phone

There are two ways to run Astra. Pick one.

* **Pi only (no PC).** The Raspberry Pi is the server **and** the computer you control from the phone,
  like OSLink. Follow **[Pi only](#pi-only-no-windows-pc)** right below and skip §1 to §3.
* **Pi + Windows PC.** The Pi keeps the device list and you control a Windows PC. Follow §1 to §5.

Throughout this guide, `pi-server` and `desktop-pc` stand for your own device names.

---

## Pi only (no Windows PC)

You need: the Raspberry Pi 4B (Ethernet is best, Wi-Fi works), a microSD card (16 GB or more), the Android phone,
one Tailscale account, and **for the first setup only** a monitor, keyboard and mouse on the Pi.

**1. Put Raspberry Pi OS *with desktop* on the card.** Astra shows the Pi's desktop, so **Lite won't work**.
In Raspberry Pi Imager choose *Raspberry Pi 4*, then *Raspberry Pi OS (64-bit)* (the one with desktop).
In Imager's settings set the hostname to `pi-server`, your username and password, your Wi-Fi if you don't use
Ethernet, and turn on SSH. Write the card, put it in the Pi and start it with the monitor plugged in.
*Already running Raspberry Pi OS Lite?* You can add the desktop instead of starting over:
`sudo apt update && sudo apt install -y raspberrypi-ui-mods`, then reboot.

**2. Tailscale on the Pi and the phone** (skip what you've already done). On the Pi, open **Terminal** and run:
```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```
Open the link it prints and sign in. On the phone, install Tailscale from the Play Store, sign in with the same
account and turn the VPN on. In the Tailscale admin console (login.tailscale.com) check that both are listed and
that **MagicDNS** is on under **DNS**.

**3. Two Pi settings, once.** In the Pi's Terminal run `sudo raspi-config` and change:
* **Advanced Options > Wayland > X11** (it may show as *W1 X11*). Astra needs X11: the newer Wayland desktop
  doesn't let apps see the screen or move the mouse. If Astra is started on Wayland, it shows these same steps.
* **System Options > Boot / Auto Login > Desktop Autologin**, so the desktop (and Astra) starts by itself after a reboot.

Choose **Finish** and let it reboot.

**4. Copy Astra to the Pi.** Download `astra-mvp.zip` onto the Pi (or copy it over with a USB stick), then in Terminal:
```bash
cd ~ && unzip astra-mvp.zip
```
You now have a folder `~/astra` with `registry`, `host-agent`, `web-viewer` and `install-pi.sh` in it.

**5. Run the installer.**
```bash
cd ~/astra && bash install-pi.sh
```
It asks for your password once, then takes 10 to 20 minutes. It installs Docker and starts the registry, creates
both `.env` files with a new secret key filled in, installs the host agent's libraries, and makes Astra start when
the desktop logs in. You can run it again any time; it keeps your settings. At the end it prints any setting
you still need to change (from step 3).

**6. Reboot and check.** Run `sudo reboot`. After the desktop appears, the **Astra Host** window opens by itself with a
QR code and a 6-digit PIN. Its status line should read `TAILSCALE: CONNECTED · PI REGISTRY: REACHABLE`.
On the phone (Tailscale on), open `http://pi-server:8000/` and the Pi should be listed as **Online**.

**7. Pair the phone (once, while you can see the Pi's screen).**
* Quick test in the phone's browser: open `http://pi-server:8765/`, type the PIN, and click **Allow** on the Pi.
  You should see the Pi's desktop on the phone.
* In the app (§4 below explains how to install it): **Add device**, scan the QR in the Astra window, type the PIN,
  tap **Pair**, then click **Allow** on the Pi.

After pairing, the app connects **without** anyone clicking Allow on the Pi, because the installer sets
`AUTO_ALLOW_PAIRED_PHONES=1` in `host-agent/.env`. That is what lets you use the Pi from anywhere with nobody at it.
Only phones you paired with the PIN get in. A red banner still shows on the Pi's screen during a session.
Set it to `0` (and reboot) if you'd rather click Allow every time.

**8. Done test.** With the phone on mobile data, open the app, tap the Pi, and click around its desktop. After that you can unplug
the monitor, keyboard and mouse.

Good to know for the Pi-only setup:
* **No monitor attached:** if the phone then shows a black or very small screen, set a fixed size in
  `sudo raspi-config` > **Display Options** > **VNC Resolution** (1280x720 is a good choice) and reboot.
  If that doesn't help, a cheap HDMI "dummy plug" makes the Pi think a monitor is attached.
* **Speed:** the Pi now encodes the video itself. 1280x720 on the Pi's desktop feels much smoother than 1920x1080.
  The installer sets `MAX_FPS=10`; you can try 15 in `~/astra/host-agent/.env`.
* **Stop and start:** **Stop host** in the Astra window stops it until the next reboot. To start it again without
  rebooting, run `~/astra/host-agent/start-host.sh` in Terminal.
* **If something goes wrong:** the log is `~/.cache/astra-host.log`, and the registry log is
  `cd ~/astra/registry && sudo docker compose logs --tail 50`.

---
## 1. Tailscale on all three (Kanban T-05, T-06)

*Pi + Windows PC setup from here on. For the Pi-only setup, use the section above and then §4 for the app.*

1. **Pi:** `curl -fsSL https://tailscale.com/install.sh | sh`, then `sudo tailscale up`, then open the link it prints and sign in.
2. **PC:** install Tailscale for Windows from tailscale.com and sign in with the same account.
3. **Phone:** install Tailscale from the Play Store and sign in with the same account. Turn the VPN on.
4. In the Tailscale admin console (login.tailscale.com), check that all three show up with a `100.x.y.z` address.
5. In the admin console under **DNS**, make sure **MagicDNS** is on. Then devices can reach each other by name (`pi-server`, `desktop-pc`).
6. *(Optional, for TLS)* On the same DNS page, turn on **HTTPS Certificates**. You need this for §3.6.

**Check (T-07):** turn off Wi-Fi on the phone (mobile data only) and open `http://pi-server` in its browser after §2. If it loads, anywhere access works.

## 2. Raspberry Pi registry (T-08, T-18 to T-21)

On Raspberry Pi OS Lite (64-bit), wired by Ethernet:

```bash
# Stable hostname (T-08)
sudo hostnamectl set-hostname pi-server && sudo reboot

# Docker + Compose
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # log out and back in after this

# Copy the registry folder to the Pi, for example from your PC:
#   scp -r registry pi@pi-server:~/astra-registry
cd ~/astra-registry
cp .env.example .env
nano .env
```

In `.env` set:
* `HOST_API_KEY`: a long random secret. Create one with `python3 -c "import secrets; print(secrets.token_urlsafe(32))"`. Keep it; each PC needs the same value.
* `PI_TAILSCALE_IP`: the output of `tailscale ip -4`.
* `OWNER_EMAIL`: your email. It's only stored as the single owner row.

Start it:
```bash
docker compose up -d --build
docker compose logs -f        # Ctrl+C to stop watching
curl http://$(tailscale ip -4):8000/health    # -> {"ok":true,...}
```

* The registry listens **only on the Pi's Tailscale address**, port 8000. Your home LAN and the internet can't reach it.
* `restart: unless-stopped` brings it back after a reboot. If Tailscale comes up after Docker, the container retries until the address exists. Test it once with `sudo reboot` (T-21).
* The database is `~/astra-registry/data/registry.db`.
* **Nightly backup to a PC** (optional): set up SSH keys from the Pi to the PC, then `crontab -e` and add
  `15 3 * * * /home/pi/astra-registry/backup.sh you@desktop-pc:astra-backups/` (needs `sudo apt install sqlite3`).

**W1 page:** from any device on your tailnet, open `http://pi-server:8000/`. It stays empty until a PC checks in.

## 3. Windows PC host agent (T-10 to T-17, T-32)

1. Install **Python 3.12** from python.org. Tick "Add python.exe to PATH" and keep the default "tcl/tk" option, which the PC window needs.
2. Copy the `host-agent` **and** `web-viewer` folders next to each other, for example `C:\Astra\host-agent` and `C:\Astra\web-viewer`.
3. In `C:\Astra\host-agent`, copy `.env.example` to `.env` and set:
   * `DEVICE_NAME`: what the phone shows, for example `DESKTOP-PC`
   * `REGISTRY_URL=http://pi-server:8000`
   * `HOST_API_KEY`: the same secret as on the Pi
4. **Firewall:** open PowerShell **as administrator** and allow the agent's port for Tailscale addresses only:
   ```powershell
   New-NetFirewallRule -DisplayName "Astra Host Agent" -Direction Inbound -Protocol TCP -LocalPort 8765 -RemoteAddress 100.64.0.0/10 -Action Allow
   ```
   If Windows also pops up a firewall question the first time the agent starts, choose **Cancel**. The rule above already covers it.
5. Double-click **`start-host.bat`**. The first run installs the libraries, which takes a minute.
   The **P1 window** opens with a QR code, a 6-digit PIN and the PC's tailnet name. The status line should read
   `TAILSCALE: CONNECTED · PI REGISTRY: REACHABLE`, and the PC should show **Online** on the W1 page within 30 seconds.
6. *(Optional, recommended) TLS:* in PowerShell, run
   `tailscale cert desktop-pc.<your-tailnet>.ts.net` (use the full name shown in P1 under ADDRESS).
   That creates a `.crt` and a `.key` file. Put their full paths in `TLS_CERT_FILE` and `TLS_KEY_FILE` in `.env` and restart the agent.
   The phone then connects over `https://` / `wss://`. The certificates last 90 days, so run the same command again before they expire.
7. **Start with Windows** (optional): press Win+R, type `shell:startup`, and put a shortcut to `start-host.bat` there.

**Quick test from a phone browser (the usable MVP, T-22 to T-25):** on the phone (Tailscale on), open
`http://desktop-pc:8765/`, type the PIN from P1, then tap **Allow** on the PC. You should see the desktop.
Tap to click, long-press to right-click, two-finger drag to scroll, pinch to zoom. Use the ☰ button for the keyboard.

> Display scaling (125 %, 150 %) is handled: the agent makes itself DPI-aware so taps land in the right spot.
> Only the primary display is streamed in the MVP.

## 4. Android app (T-26 to T-30)

1. Install a recent **Android Studio** (Ladybug 2024.2 or newer; the project uses AGP 8.7 and Kotlin 2.1).
2. **File > Open** the `android-app` folder and let Gradle sync. It downloads the wrapper and libraries.
3. If your Pi isn't called `pi-server`, change `astra.registryUrl` in `android-app/gradle.properties`. You can also change it later in the app by tapping the red "Pi registry" row on the first screen.
4. On the phone, turn on **Developer options > USB debugging**, plug it in, and press **Run**.
   * Or build an APK with **Build > Build App Bundle(s) / APK(s) > Build APK(s)** and copy `app/build/outputs/apk/debug/app-debug.apk` to the phone to install it (direct APK, no Play Store in the MVP).
5. This code was written without access to the Android SDK, so it has **never been compiled**. If the first build shows errors, they will most likely be small API mismatches. Paste them into this thread and they can be fixed.

**First run:** A1 checks Tailscale and the Pi and moves on by itself. On A2, tap **Add device**, scan the QR in the PC's P1 window, type the PIN and tap **Pair**. Then tap **Allow** on the PC. After that the PC is in your list. Tap **Connect**, then **Allow** on the PC again (every session needs it).

## 5. Done test (from the brief, T-33)

With the phone on **mobile data** away from home: open the app, see the PC listed as online, pair once with QR + PIN,
then type, click and scroll on the PC with low enough lag for normal desktop work. If that works, the MVP is done.
Then try a second PC and a second phone (T-34), and note latency on Wi-Fi vs mobile data (T-35). If the stream stutters, lower
`FRAME_QUALITY` or `MAX_FPS` in the PC's `.env`, or choose **Low** under Stream quality in the app's Settings.
