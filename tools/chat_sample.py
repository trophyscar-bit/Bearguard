"""Publishes a sample of the Java reader's output for the uplink page.

The Chat OCR tab already shows the reader field by field, which is the right shape for judging
whether a line was read correctly and the wrong shape for judging whether the result is worth
reading. This writes the same messages as the panel actually draws them -- names, colours, quotes,
folded walls of text -- so the question "does this look like a conversation" can be answered by
looking rather than by imagining.
"""
import io
import json
import os
import re

SOURCE = os.path.join("C:\\", "Bearguard", "telemetry", "chat-java")
OUT_NAME = "chat-java-sample.js"
TARGETS = [
    os.path.join("C:\\", "Bearguard", OUT_NAME),
    os.path.join(os.path.expanduser("~"), "OneDrive - Elucid Systems", "Desktop", "lol", OUT_NAME),
]

HOW_MANY = 40


def load():
    out = []
    if not os.path.isdir(SOURCE):
        return out
    for name in sorted(os.listdir(SOURCE)):
        if not name.endswith(".jsonl"):
            continue
        for line in io.open(os.path.join(SOURCE, name), encoding="utf-8", errors="replace"):
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
    return out


def is_chatter(body):
    """The same game-generated noise the panel hides by default."""
    return bool(re.search(
        r"(?i)\b(hold(ing)? a rally|ra[gq]ing bear|rally together now|has joined the alliance"
        r"|share (layout|coordinates)|new message\(s\))\b", body or ""))


def main():
    msgs = [m for m in load() if (m.get("body") or "").strip()]
    msgs = [m for m in msgs if not is_chatter(m.get("body"))]
    msgs = [m for m in msgs if (m.get("author") or "").strip()]
    recent = msgs[-HOW_MANY:]

    rows = []
    for m in recent:
        body = (m.get("en") or m.get("body") or "").strip()
        rows.append({
            "who": m.get("author", "?"),
            "tag": m.get("tag", ""),
            "body": body,
            # Kept so the page can show that a translation happened rather than asserting it.
            "translated": bool((m.get("en") or "").strip()),
            "quoted": (m.get("quoted") or "").strip(),
            "at": (m.get("at") or "")[11:16],
        })

    payload = {
        "captured": max((m.get("at") or "") for m in recent)[:16].replace("T", " ")
        if recent else "",
        "total": len(msgs),
        "shown": len(rows),
        "translated": sum(1 for r in rows if r["translated"]),
        "rows": rows,
    }
    js = "window.CHAT_JAVA_SAMPLE = " + json.dumps(payload, ensure_ascii=False) + ";\n"
    for path in TARGETS:
        try:
            io.open(path, "w", encoding="utf-8").write(js)
        except OSError:
            pass
    print("wrote %s -- %d of %d messages, %d translated"
          % (OUT_NAME, payload["shown"], payload["total"], payload["translated"]))


if __name__ == "__main__":
    main()
