import json
import os
import numpy as np
import onnxruntime as ort

BASE = os.environ.get(
    "SMALL100_MODEL_DIR",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "translation-backend", "small100")
)

_encoder = None
_decoder = None
_vocab = None
_rank = None
_id_to_piece = None
_LANG = None


def _load_models():
    global _encoder, _decoder, _vocab, _rank, _id_to_piece, _LANG

    if _encoder is not None:
        return

    with open(os.path.join(BASE, "tokenizer.json"), encoding="utf-8") as f:
        data = json.load(f)

    _vocab = data["model"]["vocab"]
    merges = data["model"]["merges"]
    _rank = {m: i for i, m in enumerate(merges)}
    _id_to_piece = {v: k for k, v in _vocab.items()}

    with open(os.path.join(BASE, "lang_tokens.json"), encoding="utf-8") as f:
        _LANG = json.load(f)["lang_to_id"]

    _encoder = ort.InferenceSession(
        os.path.join(BASE, "onnx", "encoder_model.onnx"),
        providers=["CPUExecutionProvider"]
    )

    _decoder = ort.InferenceSession(
        os.path.join(BASE, "onnx", "decoder_model_merged.onnx"),
        providers=["CPUExecutionProvider"]
    )


def bpe(text):
    _load_models()

    text = "▁" + text.replace(" ", "▁")
    chars = list(text)

    while True:
        best = None

        for i in range(len(chars) - 1):
            r = _rank.get(chars[i] + " " + chars[i + 1])

            if r is not None and (best is None or r < best[0]):
                best = (r, i)

        if best is None:
            break

        _, i = best
        chars[i:i + 2] = [chars[i] + chars[i + 1]]

    return chars


def encode(text):
    _load_models()
    return [_vocab.get(x, 3) for x in bpe(text)]


def decode(ids):
    _load_models()
    pieces = [_id_to_piece.get(i, "<UNK>") for i in ids]
    return "".join(pieces).replace("▁", " ").strip()


def translate(text, source_lang, target_lang):
    _load_models()

    source_lang = source_lang.lower()
    target_lang = target_lang.lower()

    if source_lang not in _LANG:
        raise ValueError(f"Unsupported source language: {source_lang}")

    if target_lang not in _LANG:
        raise ValueError(f"Unsupported target language: {target_lang}")

    src = [_LANG[target_lang]] + encode(text) + [2]

    enc = _encoder.run(
        None,
        {
            "input_ids": np.array([src], dtype=np.int64),
            "attention_mask": np.ones((1, len(src)), dtype=np.int64)
        }
    )[0]

    enc_len = len(src)
    past = {}

    for i in range(3):
        past[f"past_key_values.{i}.decoder.key"] = np.zeros(
            (1, 16, 0, 64), dtype=np.float32
        )
        past[f"past_key_values.{i}.decoder.value"] = np.zeros(
            (1, 16, 0, 64), dtype=np.float32
        )
        past[f"past_key_values.{i}.encoder.key"] = np.zeros(
            (1, 16, enc_len, 64), dtype=np.float32
        )
        past[f"past_key_values.{i}.encoder.value"] = np.zeros(
            (1, 16, enc_len, 64), dtype=np.float32
        )

    cur = 2
    generated = []
    use_cache = False

    for _ in range(80):
        feeds = {
            "input_ids": np.array([[cur]], dtype=np.int64),
            "encoder_attention_mask": np.ones((1, enc_len), dtype=np.int64),
            "encoder_hidden_states": enc,
            "use_cache_branch": np.array([use_cache], dtype=bool),
            **past
        }

        out = _decoder.run(None, feeds)

        next_id = int(np.argmax(out[0][0, -1]))

        if next_id == 2:
            break

        generated.append(next_id)

        new_past = {}

        for i in range(3):
            base = 1 + i * 4

            new_past[f"past_key_values.{i}.decoder.key"] = out[base]
            new_past[f"past_key_values.{i}.decoder.value"] = out[base + 1]

            if not use_cache:
                new_past[f"past_key_values.{i}.encoder.key"] = out[base + 2]
                new_past[f"past_key_values.{i}.encoder.value"] = out[base + 3]
            else:
                new_past[f"past_key_values.{i}.encoder.key"] = past[
                    f"past_key_values.{i}.encoder.key"
                ]
                new_past[f"past_key_values.{i}.encoder.value"] = past[
                    f"past_key_values.{i}.encoder.value"
                ]

        past = new_past
        cur = next_id
        use_cache = True

    return decode(generated)
