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


def runs(text):
    key = re.sub(r"[^a-z0-9]", "", text.lower())
    return {key[i:i + 4] for i in range(len(key) - 3)}


def translated_anything(english, original):
    """Whether the rendering is actually different text rather than the source with a word broken."""
    if english.lower() == original.lower():
        return False
    if len(english) < 16 or len(original) < 16:
        return True
    a, b = runs(english), runs(original)
    if not a or not b:
        return True
    shared = len(a & b) / float(min(len(a), len(b)))
    return shared < 0.40


def main():
    msgs = [m for m in load() if (m.get("body") or "").strip()]
    # Alliance only. World chat is a different conversation between strangers, and mixing the two
    # is what the channel tabs exist to undo -- a sample meant to show what the alliance is saying
    # should not be half filled with people nobody here knows.
    msgs = [m for m in msgs if m.get("channel") == "alliance"]
    msgs = [m for m in msgs if not is_chatter(m.get("body"))]
    msgs = [m for m in msgs if (m.get("author") or "").strip()]
    recent = msgs[-HOW_MANY:]

    rows = []
    for m in recent:
        english = (m.get("en") or "").strip()
        original = (m.get("body") or "").strip()
        # The same judgement the capture now makes, applied here too. Messages stored before that
        # guard existed still carry translations that translated nothing -- "bana garezi var
        # rhaegarin" kept as "bana garage var rhegarin" -- and showing one under a badge saying it
        # is English is the part that misleads.
        if english and not translated_anything(english, original):
            english = ""
        body = english or original
        rows.append({
            "who": m.get("author", "?"),
            "tag": m.get("tag", ""),
            "body": body,
            # Kept so the page can show that a translation happened rather than asserting it.
            "translated": bool(english),
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
