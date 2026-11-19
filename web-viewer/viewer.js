/* Astra web viewer (Kanban T-22 to T-25). Served by the Host Agent; also embedded in the app.
 *
 * Screens: PIN (browser only) -> C1 Connecting -> C2 Viewer (+C3 toolbar) -> C4 Ended,
 * with E4 (not approved) and E5 (connection lost) as plain-language failure states.
 *
 * URL options:
 *   ?mode=direct|trackpad   default mouse mode (A3 setting in the app)
 *   ?q=auto|high|low        stream quality (A3 setting in the app)
 *   ?app=1                  running inside the Android app (uses the AstraBridge JS interface)
 *   ?registry=<url>         where "Back to devices" goes in a plain browser (the W1 page)
 *   #token=...&name=...     the app's paired-phone token; kept in the fragment so it never hits server logs
 *
 * The same page also runs from the Pi at /relay/d/<id>/ for an Android phone that shares its
 * screen (phone to phone). All URLs below are relative to the page for that reason. When the
 * host is a phone, a Back / Home / Recents bar replaces the PC-only buttons.
 */
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const frag = new URLSearchParams(location.hash.slice(1));
  // Do not keep the token in the address bar or history.
  if (location.hash) history.replaceState(null, "", location.pathname + location.search);

  const cfg = {
    token: frag.get("token"),
    phoneName: frag.get("name") || params.get("name") || "Browser",
    mode: params.get("mode") === "trackpad" ? "trackpad" : "direct",
    quality: ["auto", "high", "low"].includes(params.get("q")) ? params.get("q") : "auto",
    inApp: params.get("app") === "1" && typeof window.AstraBridge !== "undefined",
    registry: params.get("registry"),
  };

  let pcName = "this PC";
  let hostWord = "the PC";   // "the phone" when the host is an Android phone
  let isAndroid = false;
  let ws = null;
  let pin = null;
  let state = "idle"; // idle | connecting | waiting | live | closing
  let sessionStart = 0;
  let lastMessageAt = 0;
  let watchdog = null;
  let pingTimer = null;
  let autoRetryUsed = false;
  let lostTimer = null;
  let wakeLock = null;

  // ---------------- screens ----------------

  const screens = ["pin", "connecting", "viewer", "ended", "problem"];
  function show(name) {
    for (const s of screens) $("screen-" + s).hidden = s !== name;
  }
  function setPcName(name) {
    pcName = name || pcName;
    document.querySelectorAll(".pc-name").forEach((el) => (el.textContent = pcName));
    document.title = `${pcName} · Astra`;
  }

  function backToDevices() {
    closeSocket("bye");
    if (cfg.inApp) window.AstraBridge.backToDevices();
    else if (cfg.registry) location.href = cfg.registry;
    else history.length > 1 ? history.back() : show("pin");
  }
  document.querySelectorAll(".btn-back").forEach((b) => b.addEventListener("click", backToDevices));

  function problem(title, detail, retry = true) {
    stopTimers();
    show("problem");
    $("problem-title").textContent = title;
    $("problem-detail").textContent = detail;
    $("btn-try-again").hidden = !retry;
  }

  function ended(title, detail) {
    stopTimers();
    releaseWakeLock();
    show("ended");
    $("ended-title").textContent = title;
    const mins = sessionStart ? Math.max(0, Math.round((Date.now() - sessionStart) / 60000)) : 0;
    $("ended-detail").textContent = detail || (sessionStart ? `Session lasted ${mins} min` : "");
    sessionStart = 0;
  }

  // ---------------- connection ----------------

  function wsUrl() {
    const u = new URL("stream", location.href); // /stream on a PC, /relay/d/<id>/stream on the Pi
    u.protocol = location.protocol === "https:" ? "wss:" : "ws:";
    u.search = u.hash = "";
    return u.toString();
  }

  function setPlatform(platform) {
    isAndroid = platform === "android";
    hostWord = isAndroid ? "the phone" : "the PC";
    document.body.classList.toggle("android", isAndroid);
    // Ctrl+Alt+Del and the trackpad mode mean nothing on a phone.
    document.querySelectorAll("[data-act=cad], [data-act=mode], #keys [data-key=win]").forEach((b) => (b.hidden = isAndroid));
    if (isAndroid) cfg.mode = "direct";
  }

  function connect() {
    closeSocket();
    show("connecting");
    $("connecting-status").textContent = `Reaching ${hostWord}…`;
    state = "connecting";
    const sock = (ws = new WebSocket(wsUrl()));
    sock.binaryType = "blob";

    sock.onopen = () => {
      const hello = { type: "hello", quality: cfg.quality, phone_name: cfg.phoneName };
      if (cfg.token) hello.token = cfg.token;
      else hello.pin = pin;
      sock.send(JSON.stringify(hello));
    };

    sock.onmessage = (ev) => {
      if (sock !== ws) return;
      lastMessageAt = Date.now();
      if (typeof ev.data !== "string") return onFrame(ev.data);
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      onControl(msg);
    };

    sock.onclose = () => {
      if (sock !== ws) return;
      ws = null;
      if (state === "live") return connectionLost();
      if (state === "connecting" || state === "waiting") {
        problem(`Could not reach ${hostWord}`, isAndroid
          ? `${pcName} did not answer. Check that Astra on it still says Sharing.`
          : `${pcName} did not answer. It may be asleep, or its Host Agent stopped.`);
      }
      state = "idle";
    };
  }

  function closeSocket(sendType) {
    const sock = ws;
    ws = null;
    state = "idle";
    if (!sock) return;
    try {
      if (sendType && sock.readyState === WebSocket.OPEN) sock.send(JSON.stringify({ type: sendType }));
      sock.close(1000);
    } catch { /* already closed */ }
  }

  function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN && state === "live") ws.send(JSON.stringify(obj));
  }

  function onControl(msg) {
    switch (msg.type) {
      case "waiting_approval":
        state = "waiting";
        setPcName(msg.name);
        $("connecting-status").textContent = `Waiting for approval on ${hostWord}…`;
        break;
      case "approved":
        state = "live";
        setPcName(msg.name);
        if (msg.platform) setPlatform(msg.platform);
        sessionStart = sessionStart || Date.now();
        autoRetryUsed = false;
        enterViewer();
        break;
      case "not_approved": // E4
        state = "closing";
        problem(`${isAndroid ? "The phone" : "The PC"} did not approve`,
          msg.reason === "timeout" ? `Nobody answered on ${hostWord} within 60 seconds.`
                                   : `Deny was tapped on ${hostWord}, or the prompt was closed.`);
        break;
      case "ended":
        state = "closing";
        ended(`Ended on ${hostWord}`, null);
        break;
      case "error":
        state = "closing";
        onError(msg);
        break;
      case "pong":
      case "warn":
        break;
    }
  }

  function onError(msg) {
    switch (msg.error) {
      case "wrong_pin":
        pin = null;
        show("pin");
        $("pin-error").hidden = false;
        $("pin-error").textContent = `That PIN did not match. Attempt ${3 - (msg.attempts_left ?? 0)} of 3. Check the PIN on ${hostWord}; it changes every 10 minutes.`;
        break;
      case "locked":
        pin = null;
        show("pin");
        $("pin-error").hidden = false;
        $("pin-error").textContent = "3 wrong PINs. The PC made a new PIN and locked PIN entry for 5 minutes.";
        break;
      case "busy":
        problem("The PC is busy", `${pcName} is already in a session or showing a prompt. Try again in a moment.`);
        break;
      case "not_paired":
      case "bad_token":
        problem("This phone is not paired", `Pair with ${pcName} again from Add device.`, false);
        break;
      case "host_offline":
        problem(`${pcName} is not sharing`, msg.detail || "Open Astra on that phone and tap Start sharing.");
        break;
      case "registry_unreachable":
        problem("The Pi registry is unreachable", "The PC cannot check this phone's pairing right now. Make sure the Pi is on.");
        break;
      default:
        problem("Something went wrong", msg.detail || "The PC closed the connection.");
    }
  }

  // ---------------- watchdog + E5 ----------------

  function startTimers() {
    stopTimers();
    lastMessageAt = Date.now();
    // The PC resends a picture at least every 2 s, so 8 s of silence means the link is gone.
    watchdog = setInterval(() => {
      if (state === "live" && Date.now() - lastMessageAt > 8000) {
        const sock = ws;
        ws = null;
        try { sock && sock.close(); } catch { /* ignore */ }
        connectionLost();
      }
    }, 1000);
    pingTimer = setInterval(() => send({ type: "ping", t: Date.now() }), 3000);
  }
  function stopTimers() {
    clearInterval(watchdog); clearInterval(pingTimer); clearInterval(lostTimer);
    watchdog = pingTimer = lostTimer = null;
  }

  function connectionLost() {
    state = "idle";
    stopTimers();
    $("screen-viewer").classList.add("frozen");
    $("lost").hidden = false;
    $("toolbar").hidden = true;
    hideKeyboard();
    let left = 10;
    $("lost-count").textContent = left;
    if (autoRetryUsed) { $("lost-count").parentElement.textContent = "Tap Reconnect to try again."; return; }
    lostTimer = setInterval(() => {
      left -= 1;
      $("lost-count").textContent = left;
      if (left <= 0) { clearInterval(lostTimer); autoRetryUsed = true; reconnectFromLost(); }
    }, 1000);
  }
  function reconnectFromLost() {
    $("lost").hidden = true;
    $("screen-viewer").classList.remove("frozen");
    connect(); // needs a fresh Allow on the PC (P2)
  }
  $("btn-lost-reconnect").onclick = () => { autoRetryUsed = true; reconnectFromLost(); };
  $("btn-lost-end").onclick = () => {
    $("lost").hidden = true;
    $("screen-viewer").classList.remove("frozen");
    ended(`Connection to ${pcName} lost`, null);
  };

  // ---------------- C2: frames ----------------

  const canvas = $("screen");
  const ctx = canvas.getContext("2d", { alpha: false });
  const stage = $("stage");
  let img = { w: 0, h: 0 };
  let decoding = false;
  let pendingBlob = null;

  async function onFrame(blob) {
    if (decoding) { pendingBlob = blob; return; } // keep only the newest frame
    decoding = true;
    try {
      const bmp = await createImageBitmap(blob);
      if (bmp.width !== img.w || bmp.height !== img.h) {
        canvas.width = img.w = bmp.width;
        canvas.height = img.h = bmp.height;
        fitView();
      }
      ctx.drawImage(bmp, 0, 0);
      bmp.close && bmp.close();
    } catch { /* corrupt frame: skip */ }
    decoding = false;
    if (pendingBlob) { const b = pendingBlob; pendingBlob = null; onFrame(b); }
  }

  function enterViewer() {
    show("viewer");
    $("lost").hidden = true;
    $("screen-viewer").classList.remove("frozen");
    setMode(cfg.mode, false);
    startTimers();
    requestWakeLock();
  }

  // ---------------- view transform: fit / 100% / pinch ----------------

  const view = { s: 1, tx: 0, ty: 0, fit: 1, zoomed: false };

  function fitScaleFor() {
    const r = stage.getBoundingClientRect();
    return img.w ? Math.min(r.width / img.w, r.height / img.h) : 1;
  }
  function fitView() {
    view.fit = fitScaleFor();
    view.s = view.fit;
    view.zoomed = false;
    clampPan(true);
    applyView();
  }
  function clampPan(center) {
    const r = stage.getBoundingClientRect();
    const w = img.w * view.s, h = img.h * view.s;
    if (w <= r.width || center) view.tx = w <= r.width ? (r.width - w) / 2 : Math.min(0, Math.max(r.width - w, view.tx));
    else view.tx = Math.min(0, Math.max(r.width - w, view.tx));
    if (h <= r.height) view.ty = (r.height - h) / 2;
    else view.ty = Math.min(0, Math.max(r.height - h, view.ty));
  }
  function applyView() {
    canvas.style.transform = `translate(${view.tx}px, ${view.ty}px) scale(${view.s})`;
    $("zoom-label").textContent = view.zoomed ? `${Math.round(view.s * 100)}%` : "Fit";
  }
  function zoomAt(newScale, cx, cy) {
    const min = view.fit, max = Math.max(view.fit * 6, 2);
    newScale = Math.min(max, Math.max(min, newScale));
    const r = stage.getBoundingClientRect();
    const px = cx - r.left, py = cy - r.top;
    view.tx = px - ((px - view.tx) * newScale) / view.s;
    view.ty = py - ((py - view.ty) * newScale) / view.s;
    view.s = newScale;
    view.zoomed = newScale > view.fit * 1.01;
    clampPan(false);
    applyView();
  }
  window.addEventListener("resize", () => { if (img.w) { view.zoomed ? (view.fit = fitScaleFor(), clampPan(false), applyView()) : fitView(); } });

  // Phone screen point -> normalized 0-1 PC point.
  function toNorm(clientX, clientY) {
    const r = stage.getBoundingClientRect();
    const x = (clientX - r.left - view.tx) / view.s / img.w;
    const y = (clientY - r.top - view.ty) / view.s / img.h;
    return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)), inside: x >= 0 && x <= 1 && y >= 0 && y <= 1 };
  }

  // ---------------- C2: gestures (see wireframe gesture map) ----------------

  const LONG_PRESS_MS = 500;
  const MOVE_SLOP = 10;
  const pointers = new Map();
  let g = null;           // current gesture
  let cursor = { x: 0.5, y: 0.5 }; // trackpad mode cursor (normalized)
  let moveQueued = null;

  function queueMove(p) {
    const first = !moveQueued;
    moveQueued = p;
    if (first) requestAnimationFrame(() => { if (moveQueued) send({ type: "move", x: moveQueued.x, y: moveQueued.y }); moveQueued = null; });
  }
  function buzz() { try { navigator.vibrate && navigator.vibrate(25); } catch { /* ignore */ } }

  stage.addEventListener("pointerdown", (e) => {
    if (state !== "live") return;
    stage.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    hideToolbar();
    if (pointers.size === 1) {
      g = { kind: "one", x0: e.clientX, y0: e.clientY, lx: e.clientX, ly: e.clientY, t0: Date.now(), moved: false, dragging: false, long: false };
      g.timer = setTimeout(() => {
        if (!g || g.kind !== "one" || g.moved) return;
        g.long = true;
        buzz();
        const at = cfg.mode === "direct" ? toNorm(g.x0, g.y0) : cursor;
        send({ type: "tap", x: at.x, y: at.y, button: "right" });
      }, LONG_PRESS_MS);
    } else if (pointers.size === 2) {
      if (g && g.kind === "one") {
        clearTimeout(g.timer);
        if (g.dragging) { const p = toNorm(g.lx, g.ly); send({ type: "up", x: p.x, y: p.y, button: "left" }); }
      }
      const [a, b] = [...pointers.values()];
      g = { kind: "two", d0: Math.hypot(a.x - b.x, a.y - b.y), mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2,
            t0: Date.now(), mode: null, s0: view.s, lastD: Math.hypot(a.x - b.x, a.y - b.y), acc: { x: 0, y: 0 } };
    }
  });

  stage.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId) || !g) return;
    const prev = pointers.get(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

    if (g.kind === "one") {
      const dist = Math.hypot(e.clientX - g.x0, e.clientY - g.y0);
      if (!g.moved && dist > MOVE_SLOP && !g.long) { g.moved = true; clearTimeout(g.timer); }
      if (!g.moved) return;
      if (cfg.mode === "direct") {
        if (!g.dragging) {
          g.dragging = true;
          const p0 = toNorm(g.x0, g.y0);
          send({ type: "down", x: p0.x, y: p0.y, button: "left" });
        }
        queueMove(toNorm(e.clientX, e.clientY));
      } else {
        // Trackpad: finger movement moves the cursor relative to where it is.
        const scale = 1.6 / (img.w * view.s);
        cursor.x = Math.min(1, Math.max(0, cursor.x + (e.clientX - prev.x) * scale));
        cursor.y = Math.min(1, Math.max(0, cursor.y + (e.clientY - prev.y) * scale * (img.w / img.h)));
        queueMove(cursor);
      }
      g.lx = e.clientX; g.ly = e.clientY;
    } else if (g.kind === "two" && pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      if (!g.mode) {
        if (Math.abs(d - g.d0) > 28) g.mode = "pinch";
        else if (Math.hypot(mx - g.mx, my - g.my) > 12) g.mode = "scroll";
        else return;
      }
      if (g.mode === "pinch") {
        // Pinch zooms the picture only, never the PC's own display scaling. Moving both fingers pans.
        view.tx += mx - g.mx; view.ty += my - g.my;
        zoomAt(view.s * (d / g.lastD), mx, my);
        g.lastD = d;
      } else {
        // Two-finger drag = scroll wheel (natural direction, like the phone itself).
        g.acc.y += (my - g.my) / 40;
        g.acc.x += -(mx - g.mx) / 40;
        if (Math.abs(g.acc.y) >= 0.25 || Math.abs(g.acc.x) >= 0.25) {
          send({ type: "scroll", dx: g.acc.x, dy: g.acc.y });
          g.acc = { x: 0, y: 0 };
        }
      }
      g.mx = mx; g.my = my;
    }
  });

  function endPointer(e) {
    if (!pointers.has(e.pointerId)) return;
    pointers.delete(e.pointerId);
    if (!g) return;
    if (g.kind === "one") {
      clearTimeout(g.timer);
      if (g.long) { /* right-click already sent */ }
      else if (g.dragging) { const p = toNorm(e.clientX, e.clientY); send({ type: "up", x: p.x, y: p.y, button: "left" }); }
      else if (!g.moved && e.type === "pointerup") {
        if (cfg.mode === "direct") {
          const p = toNorm(e.clientX, e.clientY);
          if (p.inside) send({ type: "tap", x: p.x, y: p.y, button: "left" });
        } else {
          send({ type: "tap", x: cursor.x, y: cursor.y, button: "left" });
        }
      }
      g = null;
    } else if (g.kind === "two") {
      // Quick two-finger tap with no movement = right-click (Kanban T-23).
      if (!g.mode && Date.now() - g.t0 < 300 && e.type === "pointerup") {
        const at = cfg.mode === "direct" ? toNorm(g.mx, g.my) : cursor;
        send({ type: "tap", x: at.x, y: at.y, button: "right" });
        buzz();
        g.mode = "done";
      }
      if (pointers.size === 0) g = null;
    }
  }
  stage.addEventListener("pointerup", endPointer);
  stage.addEventListener("pointercancel", endPointer);
  stage.addEventListener("contextmenu", (e) => e.preventDefault());

  // Desktop browsers: mouse wheel scrolls the PC.
  stage.addEventListener("wheel", (e) => {
    e.preventDefault();
    if (e.ctrlKey) return zoomAt(view.s * (e.deltaY < 0 ? 1.1 : 0.9), e.clientX, e.clientY);
    send({ type: "scroll", dx: e.deltaX / 100, dy: -e.deltaY / 100 });
  }, { passive: false });

  // ---------------- C3: toolbar ----------------

  let toolbarTimer = null;
  function showToolbar() {
    $("toolbar").hidden = false;
    $("btn-menu").hidden = true;
    armToolbarHide();
  }
  function armToolbarHide() {
    clearTimeout(toolbarTimer);
    toolbarTimer = setTimeout(hideToolbar, 3000); // auto-hides after 3 seconds
  }
  function hideToolbar() {
    clearTimeout(toolbarTimer);
    $("toolbar").hidden = true;
    $("btn-menu").hidden = false;
  }
  $("btn-menu").onclick = showToolbar;

  function toast(text) {
    const t = $("toast");
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => (t.hidden = true), 1600);
  }

  function setMode(mode, announce = true) {
    cfg.mode = mode;
    $("mode-label").textContent = mode === "direct" ? "Direct" : "Trackpad";
    if (announce) toast(mode === "direct" ? "Direct touch: tap where you want to click" : "Trackpad: drag moves the cursor, tap clicks");
  }

  $("toolbar").addEventListener("click", (e) => {
    const btn = e.target.closest("button");
    if (!btn) return;
    armToolbarHide();
    switch (btn.dataset.act) {
      case "keyboard": hideToolbar(); showKeyboard(); break;
      case "mode": setMode(cfg.mode === "direct" ? "trackpad" : "direct"); break;
      case "cad": send({ type: "cad" }); toast("Sent Ctrl+Alt+Del (opens Task Manager)"); break;
      case "zoom": {
        // Fit -> 100% (one PC pixel per screen pixel) -> Fit. Pinch for anything in between.
        const r = stage.getBoundingClientRect();
        if (view.zoomed) fitView();
        else zoomAt(view.fit < 0.99 ? 1 : view.fit * 2, r.left + r.width / 2, r.top + r.height / 2);
        break;
      }
      case "disconnect":
        hideToolbar();
        closeSocket("bye");
        ended(`Disconnected from ${pcName}`, null);
        break;
    }
  });

  // ---------------- C3: keyboard ----------------
  // Phone keyboards rarely report real key codes, so the hidden textarea is diffed after every
  // input event: removed characters become Backspace, added characters become typed text.

  const kbd = $("kbd");
  const SENTINEL = "    ";
  let kbdValue = SENTINEL;
  let composing = false;
  const mods = new Set();

  function showKeyboard() {
    $("keys").hidden = false;
    kbd.value = kbdValue = SENTINEL;
    kbd.focus();
  }
  function hideKeyboard() {
    $("keys").hidden = true;
    kbd.blur();
    mods.clear();
    document.querySelectorAll("#keys [data-mod]").forEach((b) => b.classList.remove("on"));
  }
  function takeMods() {
    const m = [...mods];
    mods.clear();
    document.querySelectorAll("#keys [data-mod]").forEach((b) => b.classList.remove("on"));
    return m;
  }
  function sendText(text) {
    if (!text) return;
    const parts = text.split("\n");
    parts.forEach((part, i) => {
      if (part) {
        if (mods.size) {
          const m = takeMods();
          for (const ch of part) send({ type: "key", key: ch.toLowerCase(), mods: m });
        } else {
          for (let k = 0; k < part.length; k += 400) send({ type: "text", text: part.slice(k, k + 400) });
        }
      }
      if (i < parts.length - 1) send({ type: "key", key: "enter", mods: takeMods() });
    });
  }
  function syncFromTextarea() {
    const now = kbd.value;
    let p = 0;
    while (p < now.length && p < kbdValue.length && now[p] === kbdValue[p]) p++;
    const removed = kbdValue.length - p;
    for (let i = 0; i < removed; i++) send({ type: "key", key: "backspace", mods: [] });
    sendText(now.slice(p));
    kbdValue = now;
    if (!composing && (now.length < 2 || now.length > 200)) kbd.value = kbdValue = SENTINEL;
  }
  kbd.addEventListener("compositionstart", () => (composing = true));
  kbd.addEventListener("compositionend", () => { composing = false; syncFromTextarea(); });
  kbd.addEventListener("input", syncFromTextarea);
  const KEYMAP = { Enter: "enter", Backspace: "backspace", Tab: "tab", Escape: "esc", Delete: "delete",
                   ArrowLeft: "left", ArrowRight: "right", ArrowUp: "up", ArrowDown: "down", Home: "home", End: "end" };
  kbd.addEventListener("keydown", (e) => {
    if (e.isComposing || e.keyCode === 229) return; // the IME is in charge; diffing handles it
    const key = KEYMAP[e.key];
    if (key) {
      e.preventDefault();
      send({ type: "key", key, mods: takeMods() });
    }
  });
  $("keys").addEventListener("pointerdown", (e) => e.preventDefault()); // keep the phone keyboard open
  $("keys").addEventListener("click", (e) => {
    const btn = e.target.closest("button");
    if (!btn) return;
    if (btn.dataset.key === "hide") return hideKeyboard();
    if (btn.dataset.mod) {
      mods.has(btn.dataset.mod) ? mods.delete(btn.dataset.mod) : mods.add(btn.dataset.mod);
      btn.classList.toggle("on", mods.has(btn.dataset.mod));
      return;
    }
    send({ type: "key", key: btn.dataset.key, mods: takeMods() });
  });

  // ---------------- Android hosts: Back / Home / Recents ----------------

  $("navbar").addEventListener("click", (e) => {
    const btn = e.target.closest("button");
    if (!btn) return;
    if (btn.dataset.nav === "menu") return showToolbar();
    send({ type: "nav", action: btn.dataset.nav });
    buzz();
  });

  // ---------------- misc ----------------

  async function requestWakeLock() {
    try { if (navigator.wakeLock) wakeLock = await navigator.wakeLock.request("screen"); } catch { /* optional */ }
  }
  function releaseWakeLock() { try { wakeLock && wakeLock.release(); } catch { /* ignore */ } wakeLock = null; }

  $("btn-cancel").onclick = () => {
    // C1 Cancel always works and closes the request on the PC too.
    closeSocket("cancel");
    backToDevices();
  };
  $("btn-reconnect").onclick = () => (cfg.token || pin ? connect() : start());
  $("btn-try-again").onclick = () => (cfg.token || pin ? connect() : start());

  $("pin-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const v = $("pin-input").value.replace(/\D/g, "");
    if (v.length !== 6) {
      $("pin-error").hidden = false;
      $("pin-error").textContent = "The PIN has 6 digits.";
      return;
    }
    pin = v;
    $("pin-input").value = "";
    $("pin-error").hidden = true;
    if (!cfg.inApp && document.documentElement.requestFullscreen) {
      document.documentElement.requestFullscreen().catch(() => {});
    }
    connect();
  });

  window.addEventListener("beforeunload", () => closeSocket("bye"));

  async function start() {
    try {
      const info = await (await fetch("api/info", { cache: "no-store" })).json();
      setPcName(info.name);
      if (info.platform) setPlatform(info.platform);
    } catch { /* name stays generic */ }
    if (cfg.token) connect();
    else { show("pin"); $("pin-input").focus(); }
  }

  // Exposed for the Android app (system back button = disconnect, as in C3).
  window.astraDisconnect = () => {
    if (state === "live") { closeSocket("bye"); ended(`Disconnected from ${pcName}`, null); return true; }
    return false;
  };

  start();
})();
