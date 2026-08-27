"""Drives the exported model exactly the way Java will, and prints what it says.

The point is to prove the ONNX pair works on its own -- tokenise, encode, greedy-decode -- before
any of that is rewritten in Java. If this produces English, the Java port has a known-good answer
to be checked against. If it does not, the problem is the export, and no amount of Java will fix it.
"""
import json
import os
import sys

import numpy as np
import onnxruntime as ort

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model")


def load_pieces():
    """The SentencePiece inventory and the model's own id space, kept apart on purpose."""
    scores = {}
    for line in open(os.path.join(HERE, "vocab.tsv"), encoding="utf-8"):
        piece, _, score = line.rstrip("\n").rpartition("\t")
        if piece:
            scores[piece] = float(score)
    ids = {}
    for line in open(os.path.join(HERE, "tokens.tsv"), encoding="utf-8"):
        piece, _, idx = line.rstrip("\n").rpartition("\t")
        ids[piece] = int(idx)
    return scores, ids


def encode(text, scores, ids, meta):
    """Unigram SentencePiece by Viterbi, the way the model was trained to be fed."""
    text = "▁" + text.strip().replace(" ", "▁")
    n = len(text)
    best = [-1e30] * (n + 1)
    back = [None] * (n + 1)
    best[0] = 0.0
    for i in range(n):
        if best[i] <= -1e29:
            continue
        for j in range(i + 1, min(n, i + 24) + 1):
            piece = text[i:j]
            if piece in scores and best[i] + scores[piece] > best[j]:
                best[j] = best[i] + scores[piece]
                back[j] = (i, piece)
        # every position must stay reachable, so a byte nothing covers becomes unknown
        if back[i + 1] is None and best[i] - 20.0 > best[i + 1]:
            best[i + 1] = best[i] - 20.0
            back[i + 1] = (i, text[i:i + 1])
    out, k = [], n
    while k > 0:
        i, piece = back[k]
        out.append(piece)
        k = i
    out.reverse()
    return [ids.get(p, meta["unk_id"]) for p in out] + [meta["eos_token_id"]]


def translate(text, enc, dec, scores, ids, rev, meta, limit=64):
    tokens = np.array([encode(text, scores, ids, meta)], dtype=np.int64)
    mask = np.ones_like(tokens)
    hidden = enc.run(None, {"input_ids": tokens, "attention_mask": mask})[0]

    produced = [meta["decoder_start_token_id"]]
    for _ in range(limit):
        logits = dec.run(None, {
            "decoder_input_ids": np.array([produced], dtype=np.int64),
            "hidden": hidden,
            "attention_mask": mask})[0]
        step = logits[0, -1].copy()
        # Pad is banned at every step. The model's own generation config bans it -- bad_words_ids
        # is [[pad]] -- and without that ban the very first token it wants to emit is pad, every
        # time, for every language. A decode loop that does not copy this ban produces nothing but
        # padding and looks like a broken export.
        step[meta["pad_token_id"]] = -1e30
        nxt = int(np.argmax(step))
        if nxt == meta["eos_token_id"]:
            break
        produced.append(nxt)

    pieces = [rev.get(t, "") for t in produced[1:]]
    return "".join(pieces).replace("▁", " ").strip()


def main():
    meta = json.load(open(os.path.join(HERE, "meta.json")))
    scores, ids = load_pieces()
    rev = {v: k for k, v in ids.items()}
    enc = ort.InferenceSession(os.path.join(HERE, "encoder.onnx"),
                               providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(os.path.join(HERE, "decoder.onnx"),
                               providers=["CPUExecutionProvider"])

    # Real lines out of the captured transcript, one per language the game ships in that has
    # actually turned up, plus the two it does not ship but people write anyway.
    samples = [
        ("Spanish", "Llenen los rally abiertos"),
        ("Spanish", "@Mojorisinfans posicion 37 aca jajaja pesimo"),
        ("Spanish", "no te preocupes"),
        ("Portuguese", "Feliz aniversario!!!"),
        ("Czech", "Vsem dobrou noc"),
        ("Czech", "Tak vsechno nejlepsi k narozeninam"),
        ("Turkish", "Bir arkadasi ziyarete gittim"),
        ("Russian", "Всем спокойной ночи!!!"),
        ("Russian", "у меня хорошо"),
        ("Korean", "지금 다들 집결 열어주세요"),
        ("German", "Guten Morgen zusammen"),
        ("French", "Bonne nuit a tous"),
        ("Italian", "Buongiorno a tutti"),
        ("Polish", "Dobranoc wszystkim"),
        ("Dutch", "wat wordt er bedoeld met fragmenten"),
        ("Indonesian", "selamat pagi semuanya"),
        ("Arabic", "صباح الخير للجميع"),
        ("Thai", "สวัสดีตอนเช้า"),
        ("Japanese", "みなさんおはようございます"),
        ("Chinese", "大家早上好"),
    ]
    import time
    started = time.time()
    for lang, text in samples:
        print("  %-11s %-46s -> %s" % (lang, text[:46], translate(text, enc, dec, scores, ids, rev, meta)))
    spent = time.time() - started
    print("\n  %d messages in %.1fs  (%.2fs each)" % (len(samples), spent, spent / len(samples)))


if __name__ == "__main__":
    sys.exit(main())
