"""Builds the overnight chat-capture report from the log and the transcripts.

Reads what actually happened rather than what was intended: every pass, how long it held the bot,
how many screens it photographed, whether it caught up with history it already had, and how many
messages came out. Anything that went wrong is listed rather than summarised away, because a report
that only shows the good passes is worse than no report.
"""
import io
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime

BEARGUARD = r"C:\Bearguard"
LOG = os.path.join(BEARGUARD, "logs", "frostguard.log")
OUT = os.path.join(BEARGUARD, "chat-report.html")

# Where else the finished page is dropped. The hub serves the desktop folder, so writing there too
# means the page on :6969 is this page rather than whatever was left from the last manual run.
SERVED_COPIES = [
    os.path.join(os.path.expanduser("~"), "OneDrive - Elucid Systems", "Desktop", "lol",
                 "chat-report.html"),
]

TRANSCRIPTS = {
    "Java (in-process)": os.path.join(BEARGUARD, "telemetry", "chat-java"),
    "Python (service)": os.path.join(BEARGUARD, "telemetry", "chat"),
}


def parse_log():
    """Every chat pass in the log, with what it did."""
    passes = []
    current = None
    for line in io.open(LOG, encoding="utf-8", errors="replace"):
        stamp = line[:19]
        if "Executing: Chat Capture" in line:
            current = {"start": stamp, "end": None, "reader": None, "channels": [],
                       "problems": []}
            passes.append(current)
        if current is None:
            continue
        m = re.search(r"Reader: (.+?)\s*$", line)
        if m:
            current["reader"] = m.group(1)
        m = re.search(r"(\w+): photographed (\d+) screen", line)
        if m:
            current["channels"].append({"name": m.group(1), "shot": int(m.group(2)),
                                        "read": None, "caught": None, "stored": None})
        m = re.search(r"(\w+): reached already-captured history after (\d+) of (\d+)", line)
        if m:
            for c in current["channels"]:
                if c["name"] == m.group(1) and c["caught"] is None:
                    c["read"], c["caught"] = int(m.group(2)), True
        m = re.search(r"(\w+): read all (\d+) photographed", line)
        if m:
            for c in current["channels"]:
                if c["name"] == m.group(1) and c["caught"] is None:
                    c["read"], c["caught"] = int(m.group(2)), False
        m = re.search(r"(\w+): reading finished, (\d+) new message", line)
        if m:
            for c in current["channels"]:
                if c["name"] == m.group(1) and c["stored"] is None:
                    c["stored"] = int(m.group(2))
        if "Completed: Chat Capture" in line:
            current["end"] = stamp
            current = None
        for signal, label in (("not scrolling", "feed would not scroll"),
                              ("folded the pinned poll", "folded a pinned poll"),
                              ("reader is still working", "reader fell behind"),
                              ("OCR service returned nothing", "reader returned nothing"),
                              ("Could not capture a frame", "frame capture failed")):
            if signal in line and label not in current["problems"]:
                current["problems"].append(label)
    return [p for p in passes if p["channels"]]


def minutes(pass_):
    if not pass_["end"]:
        return None
    fmt = "%Y-%m-%dT%H:%M:%S"
    return (datetime.strptime(pass_["end"], fmt)
            - datetime.strptime(pass_["start"], fmt)).total_seconds() / 60.0


def transcript_stats(folder):
    """What is actually in a reader's transcript."""
    if not os.path.isdir(folder):
        return None
    messages, authors, translated, cyrillic = 0, Counter(), 0, 0
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".jsonl"):
            continue
        for line in io.open(os.path.join(folder, name), encoding="utf-8", errors="replace"):
            line = line.strip()
            if not line:
                continue
            try:
                m = json.loads(line)
            except ValueError:
                continue
            messages += 1
            if m.get("author"):
                authors[m["author"]] += 1
            if (m.get("en") or "").strip():
                translated += 1
            if re.search(r"[\u0400-\u04FF]", m.get("body", "")):
                cyrillic += 1
    return {"messages": messages, "authors": authors, "translated": translated,
            "cyrillic": cyrillic}


def recent_messages(folder, limit=40):
    out = []
    if not os.path.isdir(folder):
        return out
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".jsonl"):
            continue
        for line in io.open(os.path.join(folder, name), encoding="utf-8", errors="replace"):
            line = line.strip()
            if line:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass
    return out[-limit:]


def esc(t):
    return (str(t).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def build():
    passes = parse_log()
    overnight = passes[-40:]
    html = []
    html.append(PAGE_HEAD)
    html.append("<h1>Chat capture &mdash; overnight</h1>")
    html.append('<p class="sub">Generated %s from the running log and the stored transcripts. '
                'Every pass is listed, including the ones that went wrong.</p>'
                % datetime.now().strftime("%d %b %Y, %H:%M"))

    # Headline numbers.
    done = [p for p in overnight if p["end"]]
    held = [minutes(p) for p in done if minutes(p) is not None]
    stored = sum(c["stored"] or 0 for p in overnight for c in p["channels"])
    ran_out = sum(1 for p in overnight for c in p["channels"] if c["caught"] is False)
    troubled = sum(1 for p in overnight if p["problems"])
    html.append('<div class="cards">')
    for label, value, note in (
            ("Passes", len(overnight), "%d finished" % len(done)),
            ("Messages stored", stored, "across all channels"),
            ("Longest pass", "%.1f min" % max(held) if held else "&mdash;",
             "median %.1f min" % (sorted(held)[len(held) // 2] if held else 0)),
            ("Ran out of screens", ran_out, "channel-passes that did not catch up"),
            ("Passes with a problem", troubled, "listed below" if troubled else "none"),
    ):
        html.append('<div class="card"><div class="v">%s</div><div class="k">%s</div>'
                    '<div class="n">%s</div></div>' % (value, label, note))
    html.append("</div>")

    # Per-pass detail.
    html.append("<h2>Every pass</h2>")
    html.append('<table><tr><th>Started</th><th>Held the bot</th><th>Reader</th>'
                '<th>Channel</th><th>Photographed</th><th>Read</th><th>Caught up</th>'
                '<th>Stored</th><th>Notes</th></tr>')
    for p in reversed(overnight):
        span = minutes(p)
        rows = p["channels"] or [{"name": "&mdash;", "shot": "", "read": "", "caught": None,
                                  "stored": ""}]
        for i, c in enumerate(rows):
            html.append("<tr>")
            if i == 0:
                html.append('<td rowspan="%d">%s</td>' % (len(rows), esc(p["start"][11:16])))
                html.append('<td rowspan="%d" class="%s">%s</td>'
                            % (len(rows), "bad" if span and span > 8 else "",
                               ("%.1f min" % span) if span else "running"))
                html.append('<td rowspan="%d">%s</td>' % (len(rows), esc(p["reader"] or "?")))
            caught = c["caught"]
            html.append("<td>%s</td><td>%s</td><td>%s</td>"
                        % (esc(c["name"]), esc(c["shot"]), esc(c["read"] if c["read"] else "")))
            html.append('<td class="%s">%s</td>'
                        % ("good" if caught else ("bad" if caught is False else ""),
                           "yes" if caught else ("ran out" if caught is False else "")))
            html.append("<td>%s</td>" % esc(c["stored"] if c["stored"] is not None else ""))
            if i == 0:
                html.append('<td rowspan="%d">%s</td>'
                            % (len(rows), esc(", ".join(p["problems"])) or ""))
            html.append("</tr>")
    html.append("</table>")

    # What each reader has collected.
    html.append("<h2>Transcripts</h2><div class='cards'>")
    for label, folder in TRANSCRIPTS.items():
        s = transcript_stats(folder)
        if not s:
            html.append('<div class="card"><div class="v">&mdash;</div><div class="k">%s</div>'
                        '<div class="n">nothing captured</div></div>' % esc(label))
            continue
        html.append('<div class="card"><div class="v">%d</div><div class="k">%s</div>'
                    '<div class="n">%d translated &middot; %d Cyrillic &middot; %d people</div>'
                    "</div>" % (s["messages"], esc(label), s["translated"], s["cyrillic"],
                                len(s["authors"])))
    html.append("</div>")

    # The most recent chat, so there is something human to read.
    for label, folder in TRANSCRIPTS.items():
        recent = recent_messages(folder)
        if not recent:
            continue
        html.append("<h2>Latest &mdash; %s</h2>" % esc(label))
        html.append('<div class="feed">')
        for m in recent:
            body = (m.get("en") or m.get("body") or "").strip()
            if not body:
                continue
            quoted = (m.get("quoted") or "").strip()
            html.append('<div class="msg">')
            html.append('<div class="who">%s <span class="tag">%s</span></div>'
                        % (esc(m.get("author", "?")), esc(m.get("tag", ""))))
            if quoted:
                html.append('<div class="q">%s</div>' % esc(quoted[:160]))
            html.append('<div class="body">%s</div></div>' % esc(body[:400]))
        html.append("</div>")

    html.append("</body>")
    page = "\n".join(html)
    io.open(OUT, "w", encoding="utf-8").write(page)
    # And where the hub serves from, so the page on :6969 is not a stale copy of this one.
    for extra in SERVED_COPIES:
        try:
            io.open(extra, "w", encoding="utf-8").write(page)
        except OSError:
            pass
    print("wrote %s (%d passes)" % (OUT, len(overnight)))


PAGE_HEAD = """<title>Chat capture overnight</title>
<meta http-equiv="refresh" content="120">
<style>
:root{--bg:#1e2024;--card:#24272c;--edge:#31353b;--body:#dce0e5;--mute:#868d97;
      --good:#57f287;--bad:#ed4245;--accent:#7aa2f7}
body{margin:0;background:var(--bg);color:var(--body);
     font:14px/1.55 "Segoe UI",system-ui,sans-serif;padding:26px;max-width:1180px}
h1{font-size:19px;margin:0 0 4px}
h2{font-size:14px;color:var(--mute);text-transform:uppercase;letter-spacing:.07em;
   margin:30px 0 10px;font-weight:600}
p.sub{color:var(--mute);font-size:12.5px;margin:0 0 20px}
.cards{display:flex;flex-wrap:wrap;gap:10px}
.card{background:var(--card);border:1px solid var(--edge);border-radius:10px;
      padding:13px 16px;min-width:150px}
.card .v{font-size:23px;font-weight:700}
.card .k{font-size:12px;color:var(--body);margin-top:2px}
.card .n{font-size:11px;color:var(--mute);margin-top:3px}
table{border-collapse:collapse;width:100%;font-size:12.5px;margin-top:6px}
th{text-align:left;color:var(--mute);font-weight:600;font-size:11px;
   text-transform:uppercase;letter-spacing:.05em;padding:7px 9px;border-bottom:1px solid var(--edge)}
td{padding:6px 9px;border-bottom:1px solid #292c31;vertical-align:top}
td.good{color:var(--good)} td.bad{color:var(--bad);font-weight:600}
.feed{max-width:820px}
.msg{background:var(--card);border:1px solid var(--edge);border-radius:9px;
     padding:9px 13px;margin-bottom:7px}
.who{font-weight:700;font-size:12.5px;color:var(--accent)}
.tag{font-size:9.5px;background:#3a3d43;color:#c3c8d0;border-radius:4px;padding:1px 5px;
     font-weight:400}
.q{font-size:11px;color:#9aa3b2;border-left:3px solid #6b7a99;padding-left:8px;margin:4px 0}
.body{font-size:13px;word-wrap:break-word}
</style>
<body>"""


if __name__ == "__main__":
    build()
