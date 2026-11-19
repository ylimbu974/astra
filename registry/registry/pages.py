"""W1 - the Pi device registry page. Read-only in the MVP: no pairing or settings here."""
from datetime import datetime
from html import escape


def _ago(iso_text: str | None, now: datetime) -> str:
    if not iso_text:
        return "never"
    seen = datetime.strptime(iso_text, "%Y-%m-%dT%H:%M:%SZ")
    secs = int((now - seen).total_seconds())
    if secs < 60:
        return "now"
    if secs < 3600:
        return f"{secs // 60} min ago"
    if secs < 86400:
        return f"{secs // 3600} h ago"
    return f"{secs // 86400} d ago"


def render_device_page(devices: list[dict], now: datetime) -> str:
    if devices:
        rows = []
        for d in devices:
            online = d["status"] == "online"
            link = (f'<a class="open" href="{escape(d["viewer_url"])}">Open viewer</a>'
                    if online else '<span class="muted">&mdash;</span>')
            rows.append(
                "<tr>"
                f'<td><strong>{escape(d["name"])}</strong><br><span class="muted">{escape(d["hostname"])}</span></td>'
                f'<td><span class="pill {"on" if online else "off"}">{"Online" if online else "Offline"}</span></td>'
                f'<td>{_ago(d["last_seen_at"], now)}</td>'
                f"<td>{link}</td>"
                "</tr>"
            )
        body = ("<table><thead><tr><th>Device</th><th>Status</th><th>Last seen</th><th></th></tr></thead>"
                f"<tbody>{''.join(rows)}</tbody></table>")
    else:
        body = ('<p class="empty">No PCs have checked in yet. Start the Host Agent on a PC and it '
                "appears here within 30 seconds.</p>")

    return f"""<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="30">
<title>My Devices - Astra</title>
<style>
  :root {{ color-scheme: light dark; --bg:#f6f7f9; --card:#fff; --fg:#14171c; --muted:#69707d; --line:#e2e5ea;
          --on:#127a3e; --onbg:#dff5e7; --off:#6b7280; --offbg:#eceef1; --accent:#2457d6; }}
  @media (prefers-color-scheme: dark) {{ :root {{ --bg:#0f1115; --card:#181b21; --fg:#e8eaee; --muted:#9aa1ad;
          --line:#2a2f38; --on:#5fd08b; --onbg:#14301f; --off:#a0a6b1; --offbg:#24282f; --accent:#7ea2ff; }} }}
  body {{ margin:0; font:15px/1.45 system-ui, sans-serif; background:var(--bg); color:var(--fg); }}
  main {{ max-width:720px; margin:0 auto; padding:24px 16px; }}
  header {{ display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:16px; }}
  h1 {{ font-size:20px; margin:0; }}
  button {{ font:inherit; padding:8px 14px; border-radius:8px; border:1px solid var(--line);
           background:var(--card); color:var(--fg); cursor:pointer; }}
  table {{ width:100%; border-collapse:collapse; background:var(--card); border:1px solid var(--line); border-radius:10px; overflow:hidden; }}
  th, td {{ text-align:left; padding:12px; border-bottom:1px solid var(--line); vertical-align:middle; }}
  th {{ font-size:12px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); }}
  tr:last-child td {{ border-bottom:0; }}
  .muted {{ color:var(--muted); font-size:13px; }}
  .pill {{ display:inline-block; padding:2px 10px; border-radius:999px; font-size:13px; font-weight:600; }}
  .pill.on {{ background:var(--onbg); color:var(--on); }} .pill.off {{ background:var(--offbg); color:var(--off); }}
  a.open {{ color:var(--accent); font-weight:600; text-decoration:none; white-space:nowrap; }}
  .empty {{ background:var(--card); border:1px dashed var(--line); border-radius:10px; padding:24px; }}
  footer {{ margin-top:14px; }}
</style></head>
<body><main>
<header><h1>My devices &middot; Pi registry</h1><button onclick="location.reload()">Refresh</button></header>
{body}
<footer class="muted">Read-only. Pair phones from the Astra app. Updates every 30 seconds.</footer>
</main></body></html>"""
