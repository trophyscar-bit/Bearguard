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
            text_detection_model_name="PP-OCRv5_mobile_det",
            text_recognition_model_name="PP-OCRv5_mobile_rec",
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

    lines = []
    for result in reader(lang).predict(np.array(image)):
        for text, score, poly in zip(result["rec_texts"], result["rec_scores"],
                                     result["rec_polys"]):
            if not text.strip():
                continue
            xs = [int(p[0]) for p in poly]
            ys = [int(p[1]) for p in poly]
            lines.append({
                "text": text,
                "left": min(xs) + ox,
                "top": min(ys) + oy,
                "width": max(xs) - min(xs),
                "height": max(ys) - min(ys),
                "conf": round(float(score), 4),
            })
    lines.sort(key=lambda line: (line["top"], line["left"]))
    return {"lines": lines}


if __name__ == "__main__":
    import uvicorn
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 6975
    # Warmed at startup so the first capture of a run is not the one that pays for the models.
    reader("en")
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning")
