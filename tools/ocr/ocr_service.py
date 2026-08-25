"""Reads text off Bearguard's screen captures, over HTTP, on this machine only.

Tesseract does page segmentation: it guesses a layout and then reads it. That is the wrong shape
for a chat feed, where bubbles sit at arbitrary positions, carry ornaments and emoji that are not
text at all, and mix Latin, Cyrillic and CJK on one screen. Measured over twenty live alliance
screens, it read "VIPS" for "VIP5", "jdes na druhy ?cet" for "jdes na druhy ucet", turned bubble
decoration into letters, and lost a message outright.

PaddleOCR detects text regions with a model first and recognises each one after, which is the same
order a person reads in. On those twenty screens it returned the diacritics correctly, found every
quoted-reply strip, and scored the decoration it could not read at 0.00-0.18 against 0.94-1.00 for
real writing -- so the junk separates itself on confidence alone.

It runs as its own process because the models take several seconds to load and must be loaded once,
not per capture. Nothing leaves the machine: no API key, no account, no network call. The bot talks
to it over the loopback interface and carries on with Tesseract if it is not answering.

    POST /ocr      body: PNG bytes           -> {"lines": [{text, left, top, width, height, conf}]}
    GET  /health   -> {"ok": true, "lang": "en"}

Query parameters on /ocr: lang (default "en"), and left/top/right/bottom to read one region.
"""
import io
import os
import sys
import time

import numpy as np
from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse
from PIL import Image

# Set before paddle is imported. This build hits an unimplemented oneDNN attribute conversion at
# inference time, and the failure is a hard crash rather than a fallback.
os.environ.setdefault("FLAGS_use_mkldnn", "0")

# Models live beside this file rather than in the user profile, so the whole reader -- code,
# environment and weights -- sits in one directory under the bot it belongs to and can be moved,
# backed up or deleted as one thing.
os.environ.setdefault("PADDLE_PDX_CACHE_HOME",
                      os.path.join(os.path.dirname(os.path.abspath(__file__)), "models"))

from paddleocr import PaddleOCR  # noqa: E402  (must follow the flag above)

app = FastAPI(title="Bearguard OCR")

CPU_THREADS = 4

# One reader per language, built on first use. Each costs a few seconds to construct and about
# 100MB resident, so they are made only when something actually asks for that script.
_readers = {}


# The recogniser each language needs, named rather than inferred.
#
# Letting PaddleOCR choose from the language code alone looked right and was not: asking for "ru"
# built a reader that answered in Latin. Cyrillic came back as its Latin lookalikes -- "Bcem npnBet"
# for a greeting -- which is not a failure anything downstream can see, because it is well-formed
# Latin text that no language test can place and no translator can render. The East Slavic model
# was on disk the whole time, downloaded and never used.
RECOGNISERS = {
    # Latin and Chinese share one model: its dictionary carries both, which is why English chat
    # reads correctly out of a recogniser named for Chinese.
    "en": {"text_recognition_model_name": "PP-OCRv5_mobile_rec"},
    "ru": {"text_recognition_model_name": "eslav_PP-OCRv5_mobile_rec"},
    # The rest of what the game is played in. Every one of these is named rather than inferred:
    # asking for a language PaddleOCR does not recognise does not fail, it quietly hands back the
    # Latin recogniser, and Korean read by a Latin model returns nothing at all while reporting
    # itself loaded.
    "korean": {"text_recognition_model_name": "korean_PP-OCRv5_mobile_rec"},
        # Japanese and traditional Chinese never got a v5 recogniser; v3 is what exists.
    "japan": {"text_recognition_model_name": "japan_PP-OCRv3_mobile_rec"},
    "chinese_cht": {"text_recognition_model_name": "chinese_cht_PP-OCRv3_mobile_rec"},
    "arabic": {"text_recognition_model_name": "arabic_PP-OCRv5_mobile_rec"},
    "th": {"text_recognition_model_name": "th_PP-OCRv5_mobile_rec"},
    "latin": {"text_recognition_model_name": "latin_PP-OCRv5_mobile_rec"},
    "el": {"text_recognition_model_name": "el_PP-OCRv5_mobile_rec"},
}


def reader(lang: str) -> PaddleOCR:
    if lang not in _readers:
        started = time.time()
        _readers[lang] = PaddleOCR(
            lang=lang,
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
            enable_mkldnn=False,
            # Mobile, not medium, and four threads. Benchmarked on three live screens: the medium
            # models take 9.9-14.3s a frame and find 19 lines, these take 5.0s and find 24, with
            # both scoring full marks on the diacritics and digits Tesseract was getting wrong.
            # Bigger is not better here -- the crop is 568x910, and past four threads the work is
            # too small to divide, so eight and sixteen both measured slower than two.
            cpu_threads=CPU_THREADS,
            # The detector is language-agnostic -- it finds where text is, not what it says -- so it
            # is pinned for speed. The RECOGNISER must follow the language, and pinning it was a
            # real bug: with the Latin recogniser named explicitly, asking for "ru" quietly kept
            # reading Latin, so every Cyrillic message came back as lookalikes and the language
            # setting appeared to do nothing at all.
            text_detection_model_name="PP-OCRv5_mobile_det",
            **RECOGNISERS.get(lang, {}),
        )
        print("loaded %s reader in %.1fs" % (lang, time.time() - started), flush=True)
    return _readers[lang]


@app.get("/health")
def health():
    return {"ok": True, "loaded": sorted(_readers.keys())}


@app.post("/ocr")
async def ocr(request: Request,
              lang: str = Query("en"),
              left: int = Query(0), top: int = Query(0),
              right: int = Query(0), bottom: int = Query(0)):
    raw = await request.body()
    if not raw:
        return JSONResponse({"lines": [], "error": "empty body"}, status_code=400)

    image = Image.open(io.BytesIO(raw)).convert("RGB")
    # A region of zero size means the whole frame, so a caller that does not care about regions
    # does not have to send four numbers it would have to look up.
    if right > left and bottom > top:
        image = image.crop((left, top, right, bottom))
        ox, oy = left, top
    else:
        ox, oy = 0, 0

    # More than one language may be asked for at once, comma separated. Each model answers in the
    # script it knows whether or not the screen was written in it -- shown Russian, the English
    # model returns confident Latin lookalikes -- so neither answer can be trusted on its own. Asked
    # together, the same row is read by each and the reading the model was surest of is kept, which
    # is a comparison the reader itself supports and the previous engine never offered.
    found = []
    for one in [l.strip() for l in lang.split(",") if l.strip()]:
        for result in reader(one).predict(np.array(image)):
            for text, score, poly in zip(result["rec_texts"], result["rec_scores"],
                                         result["rec_polys"]):
                if not text.strip():
                    continue
                xs = [int(p[0]) for p in poly]
                ys = [int(p[1]) for p in poly]
                found.append({
                    "text": text,
                    "left": min(xs) + ox,
                    "top": min(ys) + oy,
                    "width": max(xs) - min(xs),
                    "height": max(ys) - min(ys),
                    "conf": round(float(score), 4),
                    "lang": one,
                })

    lines = best_per_row(found)
    lines.sort(key=lambda line: (line["top"], line["left"]))
    return {"lines": lines}


def overlaps(a, b):
    """Whether two readings are of the same row.

    Both models run the same detector, so the boxes for one row land in almost the same place; the
    test is generous rather than exact because a row's box shifts a pixel or two with the glyphs
    each model thinks it sees.
    """
    ay0, ay1 = a["top"], a["top"] + a["height"]
    by0, by1 = b["top"], b["top"] + b["height"]
    shared = min(ay1, by1) - max(ay0, by0)
    if shared <= 0 or shared < 0.5 * min(a["height"], b["height"]):
        return False
    ax0, ax1 = a["left"], a["left"] + a["width"]
    bx0, bx1 = b["left"], b["left"] + b["width"]
    across = min(ax1, bx1) - max(ax0, bx0)
    return across > 0 and across >= 0.5 * min(a["width"], b["width"])


def mangled_words(text):
    """How many words carry a capital in the middle of them.

    This is what a reader does to a script it was not given. Half the Cyrillic alphabet has Latin
    lookalikes, so an English model shown Russian returns "9 To>ke yKe nouTn Hayana" -- matching
    letterforms one at a time, with no idea where a capital belongs. It does so *confidently*,
    because the shapes really are those letters, which is why the score it reports cannot be used
    to choose between two readings on its own.
    """
    odd = 0
    for word in text.split():
        if len(word) < 3 or word == word.upper():
            continue
        if any(c.isupper() for c in word[1:]):
            odd += 1
    return odd


def better_reading(a, b):
    """Which of two readings of the same row to keep.

    Shape first, confidence second. Both models are sure of themselves; only one of them is
    producing words.
    """
    if mangled_words(a["text"]) != mangled_words(b["text"]):
        return a if mangled_words(a["text"]) < mangled_words(b["text"]) else b
    return a if a["conf"] >= b["conf"] else b


def best_per_row(found):
    """One reading per row: whichever model produced words rather than letterforms."""
    # Only readings from DIFFERENT models are ever compared. Two rows from the same model are two
    # rows -- a wrapped bubble puts them close enough to overlap, and grouping them threw one away,
    # which silently cost whole lines of every message that ran to a second line.
    groups = []
    for item in found:
        for group in groups:
            if all(g["lang"] != item["lang"] for g in group) and overlaps(group[0], item):
                group.append(item)
                break
        else:
            groups.append([item])
    kept = []
    for group in groups:
        best = group[0]
        for other in group[1:]:
            best = better_reading(best, other)
        kept.append(best)
    return kept


if __name__ == "__main__":
    import uvicorn
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 6975
    # Warmed at startup so the first capture of a run is not the one that pays for the models.
    reader("en")
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning")
