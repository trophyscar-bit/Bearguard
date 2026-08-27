"""Converts the many-languages-to-English translation model into what the app can load.

The app already carries ONNX Runtime for reading chat, so translation costs weights and nothing
else -- no Python beside the installed game, no service, no key, and no dependence on somebody
else's server staying up.

Three things come out of this, into tools/translate/model:

  encoder.onnx / decoder.onnx   the model, quantised to int8
  vocab.tsv                     every subword piece and its score, for the tokeniser in Java
  meta.json                     the handful of ids the decode loop needs

Marian is an encoder-decoder. The encoder reads the foreign sentence once; the decoder then emits
English one token at a time, each step seeing everything it has already written. That loop lives in
Java -- see OfflineTranslator -- because it has to run without Python present.
"""
import json
import os
import shutil
import sys

# The general model reads every Latin, Cyrillic, Arabic, Greek and Thai language the game ships
# in. It is poor at the three CJK ones -- Korean "everyone open rallies now" came back as "Oh, my
# God" -- so those get a dedicated model each and are routed by script at read time.
MODEL = os.environ.get("MT_MODEL", "Helsinki-NLP/opus-mt-mul-en")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   os.environ.get("MT_OUT", "model"))


def main():
    from transformers import MarianMTModel, MarianTokenizer
    import torch

    os.makedirs(OUT, exist_ok=True)
    print("loading %s ..." % MODEL)
    tok = MarianTokenizer.from_pretrained(MODEL)
    model = MarianMTModel.from_pretrained(MODEL).eval()

    # ---- the pieces the Java tokeniser needs ------------------------------------------------
    # SentencePiece scores decide how a word is split. Greedy longest-match gets valid pieces but
    # the wrong split, and a wrong split is a wrong translation, so the scores travel with them.
    import sentencepiece as spm
    from huggingface_hub import hf_hub_download
    # Fetched by name rather than read off the tokenizer object, whose attributes move between
    # transformers releases. source.spm is the piece inventory for the FOREIGN side, which is the
    # only side being tokenised here.
    spm_path = hf_hub_download(MODEL, "source.spm")
    sp = spm.SentencePieceProcessor()
    sp.Load(spm_path)
    with open(os.path.join(OUT, "vocab.tsv"), "w", encoding="utf-8") as f:
        for i in range(sp.GetPieceSize()):
            f.write("%s\t%.6f\n" % (sp.IdToPiece(i), sp.GetScore(i)))
    print("  vocab.tsv: %d pieces" % sp.GetPieceSize())

    # The model's own id space is vocab.json, which is NOT the .spm order -- the tokeniser maps
    # piece text to id through this. Getting the two confused yields fluent nonsense.
    with open(os.path.join(OUT, "tokens.tsv"), "w", encoding="utf-8") as f:
        for piece, idx in sorted(tok.get_vocab().items(), key=lambda kv: kv[1]):
            f.write("%s\t%d\n" % (piece, idx))
    print("  tokens.tsv: %d ids" % len(tok.get_vocab()))

    cfg = model.config
    json.dump({
        "decoder_start_token_id": cfg.decoder_start_token_id,
        "eos_token_id": cfg.eos_token_id,
        "pad_token_id": cfg.pad_token_id,
        "vocab_size": cfg.vocab_size,
        "d_model": cfg.d_model,
        "max_length": 512,
        "unk_id": tok.unk_token_id,
    }, open(os.path.join(OUT, "meta.json"), "w"), indent=2)

    # ---- encoder ------------------------------------------------------------------------------
    ids = torch.ones(1, 8, dtype=torch.long)
    mask = torch.ones(1, 8, dtype=torch.long)

    class Enc(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, input_ids, attention_mask):
            return self.m.model.encoder(input_ids=input_ids,
                                        attention_mask=attention_mask).last_hidden_state

    torch.onnx.export(
        Enc(model), (ids, mask), os.path.join(OUT, "encoder.onnx"),
        input_names=["input_ids", "attention_mask"], output_names=["hidden"],
        dynamic_axes={"input_ids": {0: "b", 1: "s"}, "attention_mask": {0: "b", 1: "s"},
                      "hidden": {0: "b", 1: "s"}},
        opset_version=14)
    print("  encoder.onnx written")

    # ---- decoder ------------------------------------------------------------------------------
    # Exported without a key/value cache. It re-reads its own output each step, which is slower per
    # token and very much simpler to drive from Java -- and chat messages are a line long, not a
    # page, so the cost is small and the correctness is worth more.
    hid = torch.ones(1, 8, cfg.d_model, dtype=torch.float32)
    dec_ids = torch.ones(1, 3, dtype=torch.long)

    class Dec(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, decoder_input_ids, hidden, attention_mask):
            out = self.m.model.decoder(input_ids=decoder_input_ids,
                                       encoder_hidden_states=hidden,
                                       encoder_attention_mask=attention_mask).last_hidden_state
            return self.m.lm_head(out) + self.m.final_logits_bias

    torch.onnx.export(
        Dec(model), (dec_ids, hid, mask), os.path.join(OUT, "decoder.onnx"),
        input_names=["decoder_input_ids", "hidden", "attention_mask"], output_names=["logits"],
        dynamic_axes={"decoder_input_ids": {0: "b", 1: "t"}, "hidden": {0: "b", 1: "s"},
                      "attention_mask": {0: "b", 1: "s"}, "logits": {0: "b", 1: "t"}},
        opset_version=14)
    print("  decoder.onnx written")

    # ---- quantise -----------------------------------------------------------------------------
    from onnxruntime.quantization import quantize_dynamic, QuantType
    for name in ("encoder", "decoder"):
        raw = os.path.join(OUT, "%s.onnx" % name)
        small = os.path.join(OUT, "%s.int8.onnx" % name)
        quantize_dynamic(raw, small, weight_type=QuantType.QInt8)
        before = os.path.getsize(raw) / 1048576.0
        after = os.path.getsize(small) / 1048576.0
        os.remove(raw)
        shutil.move(small, raw)
        print("  %-8s %6.1f MB -> %6.1f MB" % (name, before, after))

    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
    print("\nTOTAL SHIPPED: %.1f MB" % (total / 1048576.0))


if __name__ == "__main__":
    sys.exit(main())
