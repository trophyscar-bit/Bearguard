# Offline chat translation

Chat is translated into English on the machine it was captured on. Nothing is sent anywhere.

## Why this exists

Translation used to call four public front-ends run by volunteers as a favour. That arrangement had
already failed twice at a single user: one endpoint answered 429, and the other exhausted its
anonymous daily allowance and began returning a quota notice *in place of a translation*. Neither
failure announced itself -- the transcript simply stopped being English. An install base does not
make that safer; it makes it somebody else's outage.

## What ships

| Directory | Reads | Size |
|---|---|---|
| `lib/translate` | every language the game ships in except CJK, plus several it does not | 109 MB |
| `lib/translate-ko` | Korean | 110 MB |
| `lib/translate-ja` | Japanese | 106 MB |
| `lib/translate-zh` | Chinese | 110 MB |

The general model is Marian, many languages into English, quantised to int8. The three CJK ones are
separate because the general model is not merely weaker on them, it is wrong: handed "everyone open
rallies now" in Korean it answered "Oh, my God". With its own model that line reads "Everyone,
please open the meeting."

Routing is by script, which is a fact about the characters rather than a guess.

No new runtime. Chat is already read with ONNX Runtime, so translating with it costs weights only.

## Rebuilding

    MT_MODEL=Helsinki-NLP/opus-mt-mul-en MT_OUT=model     python export_model.py
    MT_MODEL=Helsinki-NLP/opus-mt-ko-en  MT_OUT=model-ko  python export_model.py
    MT_MODEL=Helsinki-NLP/opus-mt-ja-en  MT_OUT=model-ja  python export_model.py
    MT_MODEL=Helsinki-NLP/opus-mt-zh-en  MT_OUT=model-zh  python export_model.py

Delete the `*.onnx.data` files afterwards; they are fp32 leftovers from the export and the
quantised graphs do not reference them.

`check.py` drives a model the way Java does and prints what it says. Use it before wiring anything.

## The one thing that will waste an afternoon

The decode loop must forbid the padding token at every step. This model's own generation settings
ban it (`bad_words_ids: [[pad]]`), and without that ban the first token it wants to emit is padding
-- for every sentence, in every language. A loop missing it emits a row of `<pad>` and looks exactly
like a broken export. It is not; it is a missing ban.
