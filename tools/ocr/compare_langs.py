"""Reads one screen with two language models and prints what each was sure of.

The reader is asked for a script, and it answers in that script whether or not the screen was
written in it. Shown Russian, the English model returns Latin lookalikes -- "9 To>ke yKe nouTn
Hayana" for "я тоже уже почти начала" -- confidently enough to look like a reading. This prints
both answers side by side so the confidence each model reports can be compared on the same rows,
which is the thing Tesseract never offered and the only honest way to choose between them.

    python compare_langs.py <frame.png> [lang-a] [lang-b]
"""
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
os.environ["FLAGS_use_mkldnn"] = "0"
os.environ.setdefault("PADDLE_PDX_CACHE_HOME",
                      os.path.join(os.path.dirname(os.path.abspath(__file__)), "models"))

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402
from paddleocr import PaddleOCR  # noqa: E402

FEED = (132, 250, 700, 1160)


def main():
    frame = sys.argv[1]
    langs = sys.argv[2:] or ["en", "ru"]
    crop = np.array(Image.open(frame).convert("RGB").crop(FEED))
    for lang in langs:
        reader = PaddleOCR(lang=lang, use_doc_orientation_classify=False,
                           use_doc_unwarping=False, use_textline_orientation=False,
                           enable_mkldnn=False, cpu_threads=4)
        print("--- lang=%s ---" % lang)
        for result in reader.predict(crop):
            scores = [s for t, s in zip(result["rec_texts"], result["rec_scores"]) if t.strip()]
            mean = sum(scores) / len(scores) if scores else 0
            print("    mean confidence over %d rows: %.3f" % (len(scores), mean))
            for text, score in zip(result["rec_texts"], result["rec_scores"]):
                if text.strip() and len(text) > 14:
                    print("    %.2f | %s" % (score, text[:66]))


if __name__ == "__main__":
    main()
