# Phone to phone: control one Android phone from another

Both phones run the Astra app. One phone **shares** its screen (call it **Phone B**), the other **controls** it
(**Phone A**). The Raspberry Pi is the server in the middle: it keeps the device list and pairings, and passes the
picture and the touches between the two phones. Both phones only need Tailscale; they can be anywhere.

```
 Phone A (controls)              Raspberry Pi (yoyopi)                Phone B (shared)
 Astra app, My devices  ──────▶  registry + relay  ◀────────────────  Astra app, Share this phone
 sees B's screen, taps   ◀─────  passes frames and touches ─────────▶ screen capture + Astra control
```

What Phone A can do on Phone B: tap, long-press, swipe (drag with one finger), scroll (two fingers), type into
the selected text box, and press **Back ◁, Home ○, Recents ▢** and **Notifications ↧** on the bar under the picture.

Everything stays inside Tailscale. The picture goes through the Pi's memory only and is never stored.

---

## Step 1. Get the new files (on your Fedora laptop)

Download the new `astra-mvp.zip` from the project, then in a terminal (this assumes your copy of the repo is `~/astra`):

```bash
cd ~ && unzip -o ~/Downloads/astra-mvp.zip
cd ~/astra
git add -A
git commit -m "Phone to phone: share and control Android phones through the Pi"
git push
```

## Step 2. Build the APK on GitHub (no Android Studio needed)

The push starts a build by itself (the file `.github/workflows/build-android-apk.yml` asks GitHub to do it).

1. Open your repo on github.com, then the **Actions** tab.
2. Click the newest **Build Android APK** run. Wait for the green tick (about 5 to 8 minutes).
3. At the bottom, under **Artifacts**, click **astra-apk**. You get `astra-apk.zip` with `app-debug.apk` inside.

No run showing? Click **Build Android APK** on the left, then **Run workflow**. Red cross instead of a green tick?
Open the run, click the failed step, and copy the red error lines into the project chat so they can be fixed.

## Step 3. Install the app on both phones

On **each** phone, sign in to github.com in the browser and download **astra-apk** from the same run (or download it
on the laptop and send `app-debug.apk` to the phones by USB, Google Drive or email).

1. Open the zip in the **Files** app and tap `app-debug.apk`.
2. Android asks to allow installing from this source: tap **Settings**, turn **Allow from this source** on, go back, tap **Install**.
3. If Play Protect warns about an unknown app, tap **More details > Install anyway**. (It's your own app, built from your code.)

Already have an older Astra installed? The new APK installs over it and keeps your pairings.

## Step 4. Update the Pi (from the Fedora laptop)

```bash
scp -r ~/astra/registry ~/astra/web-viewer ~/astra/show-phone-key.sh yoyo@yoyopi:~/astra/
ssh -t yoyo@yoyopi 'cd ~/astra/registry && sudo docker compose up -d --build'
```

This keeps the Pi's own settings (`registry/.env`) and database. Check it worked: on any phone with Tailscale on,
`http://yoyopi:8000/health` should show `{"ok":true,...}`.

## Step 5. Set up Phone B (the one to be controlled), once

1. Open **Astra**. If the first screen says the Pi registry is unreachable, tap it and enter `http://yoyopi:8000`.
2. Tap the **share icon** (top right of My devices) to open **Share this phone**.
3. **Step 1, Connect to the Pi.** On the Pi (its own screen, or over SSH from the laptop) run:
   ```bash
   bash ~/astra/show-phone-key.sh
   ```
   It draws a QR code in the terminal. On Phone B tap **Scan key QR** and point it at the code.
   (No camera? Paste the key it prints under the QR instead.)
4. **Step 2, Allow control.** Tap **Open Accessibility settings**, find **Astra control** (often under
   *Installed apps* or *Downloaded apps*), turn it on and tap **Allow**.
   *Greyed out, "Restricted setting"?* Back in Astra tap **Open Astra's App info**, tap **⋮** (top right) >
   **Allow restricted settings**, confirm, then try the Accessibility switch again. Android does this for every app
   installed from an APK.
5. **Step 3, Show Allow requests.** Tap **Allow notifications** and say Allow.

## Step 6. Share Phone B

1. Tap **Start sharing**. Android asks what to share: choose **Entire screen** (not a single app), then **Start**.
2. The card turns green: **Sharing · ready**. It shows Phone B's **name** and a **6-digit PIN**.
3. You can now leave Astra and use the phone normally. Sharing keeps going until you tap **Stop sharing**
   (in Astra or in the notification). Android shows its own screen-sharing icon the whole time.

## Step 7. Pair Phone A with Phone B (once)

On **Phone A**: open Astra > **Add device** > **Enter code manually**. Type Phone B's **name** and **PIN**
exactly as Phone B shows them, then tap **Pair**. On **Phone B**, tap **Allow** on the pop-up.

## Step 8. Control Phone B

On Phone A, Phone B is now in **My devices** with "Android … · Online now" while it shares. Tap **Connect**.
Phone B asks **Allow** each time (turn on *Let paired phones in without asking* on Phone B's Share screen if nobody
will be holding it). Then:

* **Tap** = tap. **Hold** = long-press. **Drag with one finger** = swipe (it plays back on Phone B when you lift your finger).
* **Two fingers** = scroll. **Pinch** = zoom the picture on Phone A only.
* The bar at the bottom: **◁ Back, ○ Home, ▢ Recents, ↧ Notifications, ☰ menu** (keyboard, zoom, disconnect).
* **Keyboard:** tap a text box on Phone B first, then ☰ > Keyboard on Phone A and type.
* Phone A's own Back button ends the session.

## If something goes wrong

| What you see | What to do |
|---|---|
| Phone B: "Can't reach the Pi" | Tailscale on Phone B is off, or the Pi address is wrong. In Astra's first screen use `http://yoyopi:8000`. |
| Phone B: "The Pi did not accept this phone's key" | Run `bash ~/astra/show-phone-key.sh` on the Pi and scan again. |
| Phone A: "… is not sharing" | On Phone B, open Astra and tap **Start sharing**. |
| Phone A sees the screen but taps do nothing | **Astra control** is off on Phone B (Step 5.4). Phone A also shows a note saying so. |
| Black picture | Some apps (banking, Netflix) block screen capture on purpose. Go Home on Phone B. |
| Picture stops when Phone B's screen turns off | Astra wakes the screen during a session, but it can't skip a PIN lock. Unlock Phone B first, or set a longer screen timeout. |
| Phone B stops sharing by itself after a while | Settings > Apps > Astra > Battery > **Unrestricted**. Some phones (Samsung, Xiaomi) stop background apps hard. |
| Android 14+: "Start sharing" fails again after a reboot | Normal: Android asks for screen-share permission each time sharing starts. Tap Start sharing again. |

## How it works (for the curious)

* `registry/registry/relay.py` adds the relay to the Pi registry. Phone B dials *out* to the Pi
  (`/relay/host`), so it needs no open port. When Phone A connects to `/relay/d/<id>/stream`, the Pi asks Phone B to
  open a matching socket and joins the two. Pairing requests (`/relay/d/<id>/api/pair`) are passed the same way.
* Phone B speaks the same protocol as the Python host agent, so Phone A's viewer is the same one used for the Pi
  and PCs. The page is served by the Pi at `/relay/d/<id>/`.
* In the app, `host/ShareService.kt` (screen capture, foreground service), `host/HostLink.kt` (link to the Pi,
  PIN, Allow, sessions), `host/ControlService.kt` (taps and typing) and `ui/ShareScreen.kt` (the Share screen).
* Phone B uses the Pi's `HOST_API_KEY`, like a PC's host agent does. That's why it scans the key from the Pi once.
