import os
from http.server import BaseHTTPRequestHandler, HTTPServer
import sys
import json
import os
import uuid
import subprocess
import shutil

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
UPLOAD_DIR = os.path.join(BASE_DIR, "uploads")
GROQ_API_KEY = ""
GOOGLE_TRANSLATE_API_KEY = ""
env_path = os.path.join(BASE_DIR, ".env")
if os.path.exists(env_path):
    with open(env_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("GROQ_API_KEY="):
                GROQ_API_KEY = line.split("=", 1)[1].strip()
                break
if not GROQ_API_KEY:
    GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")

GOOGLE_TRANSLATE_API_KEY = os.environ.get(
    "GOOGLE_TRANSLATE_API_KEY", ""
)
if GROQ_API_KEY:
    print("GROQ_API_KEY: configured")
else:
    print("GROQ_API_KEY: not configured (local Whisper mode)")
WHISPER_BIN = os.path.join(BASE_DIR, "whisper.cpp/build/bin/whisper-cli")
WHISPER_MODEL = os.path.join(BASE_DIR, "whisper.cpp/models/ggml-large-v3.bin")

WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin"

def ensure_whisper_model():
    if os.path.exists(WHISPER_MODEL) and os.path.getsize(WHISPER_MODEL) > 1000000000:
        print("WHISPER MODEL: already available", flush=True)
        return

    os.makedirs(os.path.dirname(WHISPER_MODEL), exist_ok=True)
    print("WHISPER MODEL: downloading medium model...", flush=True)

    import urllib.request
    urllib.request.urlretrieve(WHISPER_MODEL_URL, WHISPER_MODEL)

    print("WHISPER MODEL: download complete", flush=True)

os.makedirs(UPLOAD_DIR, exist_ok=True)

if not GROQ_API_KEY:
    ensure_whisper_model()



def transcribe_with_groq(audio_path):
    if not GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY is not configured")

    from groq import Groq

    client = Groq(api_key=GROQ_API_KEY)

    with open(audio_path, "rb") as audio_file:
        transcription = client.audio.transcriptions.create(
            file=audio_file,
            model="whisper-large-v3-turbo",
            response_format="verbose_json",
            timestamp_granularities=["segment"]
        )

    return transcription


def clean_transcript(text):
    """
    Light cleanup for Whisper output before translation.
    Removes obvious immediate repeated words/phrases and normalizes spacing.
    Supports Tamil and English Unicode text.
    """
    import re

    text = str(text or "").strip()
    if not text:
        return ""

    # Normalize whitespace.
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{2,}", "\n", text).strip()

    # Remove immediately repeated identical words using Unicode-safe token logic.
    words = text.split()
    cleaned_words = []

    for word in words:
        if cleaned_words and word.casefold() == cleaned_words[-1].casefold():
            continue
        cleaned_words.append(word)

    text = " ".join(cleaned_words)

    # Remove immediately repeated complete sentences/phrases.
    sentences = re.split(r"(?<=[.!?。！？])\s+", text)
    cleaned_sentences = []

    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue

        if (
            cleaned_sentences
            and sentence.casefold() == cleaned_sentences[-1].casefold()
        ):
            continue

        cleaned_sentences.append(sentence)

    return " ".join(cleaned_sentences)


def split_dialogue_segments(text, max_chars=500):
    """
    Split a movie transcript into reasonably sized dialogue segments.
    Keeps punctuation-based boundaries and avoids creating huge translation chunks.
    """
    text = str(text or "").strip()
    if not text:
        return []

    import re

    # Normalize obvious Whisper spacing/repetition issues.
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{2,}", "\n", text).strip()

    # First split on line breaks and common sentence-ending punctuation.
    parts = re.split(r"(?<=[.!?。！？])\s+|\n+", text)

    segments = []
    current = ""

    for part in parts:
        part = part.strip()
        if not part:
            continue

        if not current:
            current = part
        elif len(current) + 1 + len(part) <= max_chars:
            current += " " + part
        else:
            segments.append(current.strip())
            current = part

    if current:
        segments.append(current.strip())

    return segments



def detect_dialogue_roles(text):
    """
    Use the LLM to classify each movie dialogue segment as:
    Hero, Heroine, Villain, or Other.

    Returns a list of dictionaries:
    [{"text": "...", "role": "Hero"}, ...]
    """
    segments = split_dialogue_segments(text)

    if not segments:
        return []

    if not GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY is not configured")

    from groq import Groq
    client = Groq(api_key=GROQ_API_KEY)

    numbered = "\n".join(
        f"{i + 1}. {segment}"
        for i, segment in enumerate(segments)
    )

    prompt = f"""
Classify the speaker role of each movie dialogue line.

Possible roles:
- Hero
- Heroine
- Villain
- Other

Use the dialogue context and wording to make the best inference.
Do not change any dialogue.
Do not add explanations.

Return ONLY valid JSON in this exact structure:
[
  {{"index": 1, "role": "Hero"}},
  {{"index": 2, "role": "Other"}}
]

DIALOGUES:
{numbered}
"""

    response = client.chat.completions.create(
        model="qwen/qwen3.8-27b",
        messages=[
            {
                "role": "system",
                "content": (
                    "You are a movie dialogue character classifier. "
                    "Classify each line into exactly one of: "
                    "Hero, Heroine, Villain, Other."
                )
            },
            {
                "role": "user",
                "content": prompt
            }
        ],
        temperature=0.2,
        max_completion_tokens=1024,
        include_reasoning=False
    )

    raw = response.choices[0].message.content.strip()

    try:
        classifications = json.loads(raw)
    except Exception as e:
        raise RuntimeError(
            "Character role classification returned invalid JSON: "
            + str(e)
        )

    role_map = {
        "hero": "Hero",
        "heroine": "Heroine",
        "villain": "Villain",
        "other": "Other"
    }

    result = []

    for i, segment in enumerate(segments):
        assigned = "Other"

        for item in classifications:
            if int(item.get("index", -1)) == i + 1:
                assigned = role_map.get(
                    str(item.get("role", "Other")).strip().lower(),
                    "Other"
                )
                break

        result.append({
            "text": segment,
            "role": assigned
        })

    print(
        f"MULTI VOICE: classified {len(result)} dialogue segments",
        flush=True
    )

    return result


def google_translate_text(text, src_lang="tam_Taml", tgt_lang="eng_Latn"):
    """
    Google Cloud Translation REST API.
    Uses an API key from GOOGLE_TRANSLATE_API_KEY.
    """
    if not text.strip():
        return ""

    if not GOOGLE_TRANSLATE_API_KEY:
        raise RuntimeError("GOOGLE_TRANSLATE_API_KEY is not configured")

    if src_lang == "tam_Taml":
        source = "ta"
    elif src_lang == "eng_Latn":
        source = "en"
    else:
        raise RuntimeError(f"Unsupported Google source language: {src_lang}")

    if tgt_lang == "tam_Taml":
        target = "ta"
    elif tgt_lang == "eng_Latn":
        target = "en"
    else:
        raise RuntimeError(f"Unsupported Google target language: {tgt_lang}")

    import urllib.request
    import urllib.parse

    url = (
        "https://translation.googleapis.com/language/translate/v2"
        "?key=" + urllib.parse.quote(GOOGLE_TRANSLATE_API_KEY)
    )

    payload = urllib.parse.urlencode({
        "q": text,
        "source": source,
        "target": target,
        "format": "text"
    }).encode("utf-8")

    request = urllib.request.Request(
        url,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"
        }
    )

    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            data = json.loads(response.read().decode("utf-8"))
    except Exception as e:
        raise RuntimeError(f"Google Translate request failed: {e}")

    try:
        translated = data["data"]["translations"][0]["translatedText"].strip()
    except Exception:
        raise RuntimeError(
            "Google Translate returned an unexpected response: "
            + json.dumps(data, ensure_ascii=False)[:2000]
        )

    if not translated:
        raise RuntimeError("Google Translate returned empty translation")

    return translated


def correct_translation_terms(text, src_lang, tgt_lang):
    """Apply safe terminology corrections after LLM translation."""
    if not text:
        return text

    if src_lang == "tam_Taml" and tgt_lang == "eng_Latn":
        replacements = {
            "the Dragon to choose you as its warrior": "choose you as the Dragon Warrior",
            "the Dragon to choose you as its warrior.": "choose you as the Dragon Warrior.",
            "Hey, maybe it was a mistake for the Dragon": "Panda, it may have been a mistake to choose you as the Dragon Warrior",
        }

        for old, new in replacements.items():
            text = text.replace(old, new)

    return text


def translate_movie_dialogue(text, src_lang="tam_Taml", tgt_lang="eng_Latn", role="Other"):
    """
    Context-aware movie dialogue translation.

    Each segment is translated with previous/current/next dialogue context,
    while returning one clean translated text for the existing TTS pipeline.
    """
    segments = split_dialogue_segments(text)

    if not segments:
        return ""

    if len(segments) == 1:
        return translate_text(
            segments[0],
            src_lang=src_lang,
            tgt_lang=tgt_lang
        )

    from groq import Groq

    if not GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY is not configured")

    client = Groq(api_key=GROQ_API_KEY)

    role = str(role or "Other").strip().lower()

    if role == "hero":
        role_instruction = (
            "The speaker is the Hero. Use confident, natural, conversational "
            "wording appropriate for a movie hero."
        )
    elif role == "heroine":
        role_instruction = (
            "The speaker is the Heroine. Use natural, expressive, conversational "
            "wording appropriate for a movie heroine."
        )
    elif role == "villain":
        role_instruction = (
            "The speaker is the Villain. Use controlled, intimidating, assertive "
            "wording when the meaning allows it. Do not change the original meaning."
        )
    else:
        role_instruction = (
            "The speaker is Other. Use natural, neutral movie dialogue wording."
        )

    if src_lang == "tam_Taml" and tgt_lang == "eng_Latn":
        system_prompt = (
            "You are a precise Tamil to English translator. "
            "Translate the Tamil dialogue directly into clear, natural English. "
            "Preserve the exact meaning of the source. "
            "Do not add, remove, exaggerate, soften, or reinterpret meaning. "
            "Do not add words that are not present in the source. "
            "Preserve names and important terms. "
            "Translate the Tamil term 'டிராகன் வாரியர்' as 'Dragon Warrior'. "
            "Translate 'பேண்டா' as 'Panda'. "
            "Do not add words such as 'Hey' or 'Damn' unless they are explicitly present. "
            "Use context only to resolve meaning, not to rewrite the dialogue. "
            + role_instruction +
            " Return ONLY the translation."
        )
    elif src_lang == "eng_Latn" and tgt_lang == "tam_Taml":
        system_prompt = (
            "You are a professional movie dialogue translator. "
            "Translate English dialogue into natural spoken Tamil suitable "
            "for movie dubbing. Preserve meaning, emotion, attitude, "
            "character intent, and conversational style. "
            + role_instruction +
            " Return ONLY the translation."
        )
    else:
        raise RuntimeError(
            f"Unsupported translation direction: {src_lang} -> {tgt_lang}"
        )

    translated_segments = []

    for i, current in enumerate(segments):
        previous = segments[i - 1] if i > 0 else ""
        following = segments[i + 1] if i + 1 < len(segments) else ""

        prompt = f"""
Translate ONLY the CURRENT movie dialogue.

PREVIOUS DIALOGUE:
{previous}

CURRENT DIALOGUE:
{current}

NEXT DIALOGUE:
{following}

STRICT TRANSLATION RULES:
- Preserve the exact meaning of the CURRENT dialogue.
- Preserve names, titles, places, objects, and important terms.
- Do not invent information that is not present in the CURRENT dialogue.
- Do not add words such as "damn", "really", "actually", "huge", or other emotional emphasis unless the CURRENT dialogue itself expresses that meaning.
- Do not change the speaker's intent.
- Use the previous and next dialogue ONLY to resolve context, pronouns, or implied references.
- Do NOT translate the previous or next dialogue.
- Make the result natural spoken English, but never sacrifice the original meaning for style.
- Return ONLY the translated CURRENT dialogue.
"""

        response = client.chat.completions.create(
            model="qwen/qwen3.8-27b",
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": prompt}
            ],
            temperature=0.1,
            max_completion_tokens=512,
            include_reasoning=False
        )

        translated = response.choices[0].message.content.strip()

        if not translated:
            raise RuntimeError(
                f"Empty translation returned for segment {i + 1}"
            )

        translated_segments.append(translated)

        print(
            f"SMART TRANSLATION: segment {i + 1}/{len(segments)}",
            flush=True
        )

    return "\n".join(translated_segments)


def translate_text(text, src_lang="tam_Taml", tgt_lang="eng_Latn"):
    if not text.strip():
        return ""

    if not GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY is not configured")

    from groq import Groq

    client = Groq(api_key=GROQ_API_KEY)

    if src_lang == "tam_Taml" and tgt_lang == "eng_Latn":
        prompt = f"""Translate the following Tamil movie dialogue into natural spoken English.
Keep the meaning, emotion, and dialogue style.
Return ONLY the English translation.
Do not explain anything.

Tamil dialogue:
{text}
"""
        system_prompt = "You are a professional Tamil-to-English movie dialogue translator."

    elif src_lang == "eng_Latn" and tgt_lang == "tam_Taml":
        prompt = f"""Translate the following English movie dialogue into natural spoken Tamil.
Keep the meaning, emotion, and dialogue style.
Use natural spoken Tamil suitable for movie dubbing.
Return ONLY the Tamil translation.
Do not explain anything.

English dialogue:
{text}
"""
        system_prompt = "You are a professional English-to-Tamil movie dialogue translator."

    else:
        raise RuntimeError(f"Unsupported translation direction: {src_lang} -> {tgt_lang}")

    response = client.chat.completions.create(
        model="qwen/qwen3.8-27b",
        messages=[
            {
                "role": "system",
                "content": system_prompt
            },
            {
                "role": "user",
                "content": prompt
            }
        ],
        temperature=0.1,
        max_completion_tokens=900,
        include_reasoning=False
    )

    translated = response.choices[0].message.content.strip()

    if not translated:
        raise RuntimeError("Groq translation returned empty result")

    print("GROQ TRANSLATION:")
    print(translated)

    return translated

def merge_video_audio(video_path, audio_path, output_path):
    duration_cmd = [
        "ffprobe",
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        video_path
    ]

    duration_result = subprocess.run(
        duration_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    if duration_result.returncode != 0:
        raise RuntimeError(
            "Could not determine video duration"
        )

    video_duration = float(
        duration_result.stdout.strip()
    )

    print(
        f"FFMPEG: original video duration = "
        f"{video_duration:.2f}s",
        flush=True
    )

    cmd = [
        "ffmpeg",
        "-y",
        "-i", video_path,
        "-i", audio_path,
        "-filter_complex",
        "[0:a]volume=0.15[orig];"
        "[1:a]volume=1.0[dub];"
        "[orig][dub]amix=inputs=2:duration=longest:dropout_transition=0[out]",
        "-map", "0:v:0",
        "-map", "[out]",
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "192k",
        "-t", f"{video_duration:.3f}",
        output_path
    ]

    result = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    print("FFMPEG MERGE OUTPUT:")
    print(result.stdout)

    if result.returncode != 0:
        raise RuntimeError("Video/audio merge failed")

    if (
        not os.path.exists(output_path)
        or os.path.getsize(output_path) == 0
    ):
        raise RuntimeError("Merged video was not created")

    return output_path

def get_multivoice_voice(role, direction):
    """
    Select a different TTS voice for each detected movie character.
    """
    role = str(role or "Other").strip().lower()
    direction = str(direction or "ta-en").strip().lower()

    if direction == "ta-en":
        voices = {
            "hero": "en-US-AndrewNeural",
            "heroine": "en-US-AvaNeural",
            "villain": "en-US-ChristopherNeural",
            "other": "en-US-BrianNeural",
        }
    else:
        voices = {
            "hero": "ta-IN-ValluvarNeural",
            "heroine": "ta-IN-PallaviNeural",
            "villain": "ta-IN-ValluvarNeural",
            "other": "ta-IN-PallaviNeural",
        }

    return voices.get(role, voices["other"])


def synthesize_tts(text, output_path, voice="en-US-JennyNeural"):
    if not text.strip():
        raise RuntimeError("TTS text is empty")

    cmd = [
        "edge-tts",
        "--voice", voice,
        "--text", text,
        "--write-media", output_path,
    ]

    result = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    print("TTS ENGINE OUTPUT:")
    print(result.stdout)

    if result.returncode != 0:
        raise RuntimeError("TTS failed")

    if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
        raise RuntimeError("TTS output file was not created")

    return output_path


def translate_timestamped_segments_batch(
    segments,
    src_lang="tam_Taml",
    tgt_lang="eng_Latn",
    role="Other"
):
    """Translate all timestamped dialogue segments in one Groq request."""

    if not segments:
        raise RuntimeError("No segments available for batch translation")

    items = []

    for i, seg in enumerate(segments):
        text = str(seg.get("text", "")).strip()

        if not text or not any(ch.isalnum() for ch in text):
            continue

        items.append({
            "index": i,
            "text": text
        })

    if not items:
        raise RuntimeError("No usable dialogue segments for translation")

    direction = f"{src_lang} -> {tgt_lang}"

    if src_lang == "tam_Taml" and tgt_lang == "eng_Latn":
        instruction = (
            "Translate Tamil movie dialogue into natural spoken English "
            "suitable for movie dubbing."
        )
    elif src_lang == "eng_Latn" and tgt_lang == "tam_Taml":
        instruction = (
            "Translate English movie dialogue into natural spoken Tamil "
            "suitable for movie dubbing."
        )
    else:
        instruction = (
            "Translate the movie dialogue naturally for professional dubbing."
        )

    payload = json.dumps(items, ensure_ascii=False)

    prompt = f"""You are a professional movie dialogue translator.

Translation direction: {direction}

{instruction}

Preserve the meaning, emotion, tone, and dialogue style.
Do not add explanations.
Do not merge or split dialogue segments.
Keep the exact segment order.

Return ONLY valid JSON in this exact format:
[
  {{"index": 0, "translated_text": "..."}},
  {{"index": 1, "translated_text": "..."}}
]

Dialogue segments:
{payload}
"""

    print(
        f"BATCH TRANSLATION START: {len(items)} segments",
        flush=True
    )

    client = Groq(api_key=GROQ_API_KEY)

    response = client.chat.completions.create(
        model="qwen/qwen3.8-27b",
        messages=[
            {
                "role": "system",
                "content": (
                    "You are a professional movie dialogue translator. "
                    "Return only valid JSON."
                )
            },
            {
                "role": "user",
                "content": prompt
            }
        ],
        temperature=0.1,
        max_completion_tokens=900,
        include_reasoning=False
    )

    raw = response.choices[0].message.content.strip()

    if not raw:
        raise RuntimeError("Batch translation returned empty result")

    print("BATCH TRANSLATION RAW:", raw, flush=True)

    if raw.startswith("```"):
        raw = raw.replace("```json", "", 1)
        raw = raw.replace("```", "")
        raw = raw.strip()

    try:
        translated_items = json.loads(raw)
    except json.JSONDecodeError as e:
        raise RuntimeError(
            "Batch translation returned invalid JSON: " + str(e)
        )

    if not isinstance(translated_items, list):
        raise RuntimeError("Batch translation result is not a JSON list")

    result_map = {}

    for item in translated_items:
        if not isinstance(item, dict):
            continue

        index = item.get("index")
        translated_text = str(
            item.get("translated_text", "")
        ).strip()

        if index is None or not translated_text:
            continue

        result_map[int(index)] = translated_text

    translated_segments = []

    for i, seg in enumerate(segments):
        text = str(seg.get("text", "")).strip()

        if not text or not any(ch.isalnum() for ch in text):
            continue

        translated = result_map.get(i, "").strip()

        if not translated:
            raise RuntimeError(
                f"Missing batch translation for segment {i + 1}"
            )

        translated_segments.append({
            "id": int(seg.get("id", i)),
            "start": float(seg.get("start", 0)),
            "end": float(seg.get("end", seg.get("start", 0))),
            "text": text,
            "translated_text": translated
        })

    if len(translated_segments) != len(items):
        raise RuntimeError(
            "Batch translation count mismatch"
        )

    print(
        f"BATCH TRANSLATION OK: {len(translated_segments)} segments",
        flush=True
    )

    return translated_segments


def build_timestamped_dubbed_audio(
    segments,
    src_lang,
    tgt_lang,
    voice
):
    """
    Translate timestamped segments, generate TTS, fit each TTS
    clip to its original dialogue duration, then mix at the
    original start times.
    """

    if not segments:
        raise RuntimeError("No timestamped segments available")

    work_dir = os.path.join(
        UPLOAD_DIR,
        "dub_" + uuid.uuid4().hex
    )
    os.makedirs(work_dir, exist_ok=True)

    segment_files = []
    translated_segments = []

    try:
        total = len(segments)

        # Translate all SRT dialogue segments in ONE Groq request.
        translated_segments = translate_timestamped_segments_batch(
            segments,
            src_lang=src_lang,
            tgt_lang=tgt_lang,
            role="Other"
        )

        translation_by_index = {
            item["id"]: item["translated_text"]
            for item in translated_segments
        }

        translated_segments = []

        for i, seg in enumerate(segments):
            text = str(seg.get("text", "")).strip()

            # Skip punctuation-only SRT segments.
            if not text or not any(ch.isalnum() for ch in text):
                print(
                    f"DUB SKIP NON-SPEECH SEGMENT {i + 1}: {text!r}",
                    flush=True
                )
                continue

            if not text:
                print(
                    f"DUB SKIP EMPTY SEGMENT {i + 1}",
                    flush=True
                )
                continue

            start = float(seg.get("start", 0))
            end = float(seg.get("end", start))

            if end <= start:
                end = start + 0.5

            target_duration = max(0.5, end - start)

            print(
                f"DUB SEGMENT {i + 1}/{total}: "
                f"{start:.2f}s -> {end:.2f}s "
                f"target={target_duration:.2f}s",
                flush=True
            )

            segment_id = int(seg.get("id", i))
            translated = translation_by_index.get(
                segment_id,
                ""
            ).strip()

            print(
                f"DUB TRANSLATED TEXT: {translated!r}",
                flush=True
            )

            if not translated:
                print(
                    "DUB WARNING: empty translation",
                    flush=True
                )
                continue

            translated_segments.append({
                "id": int(seg.get("id", i)),
                "start": start,
                "end": end,
                "text": text,
                "translated_text": translated
            })

            raw_name = (
                f"raw_{i}_{uuid.uuid4().hex}.mp3"
            )
            fitted_name = (
                f"segment_{i}_{uuid.uuid4().hex}.mp3"
            )

            raw_path = os.path.join(
                work_dir,
                raw_name
            )
            fitted_path = os.path.join(
                work_dir,
                fitted_name
            )

            print(
                f"DUB TTS START: voice={voice}",
                flush=True
            )

            synthesize_tts(
                translated,
                raw_path,
                voice=voice
            )

            probe_cmd = [
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                raw_path
            ]

            probe = subprocess.run(
                probe_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )

            if probe.returncode != 0:
                raise RuntimeError(
                    "Could not determine TTS duration"
                )

            raw_duration = float(
                probe.stdout.strip()
            )

            print(
                f"DUB RAW TTS: "
                f"{raw_duration:.3f}s -> "
                f"target {target_duration:.3f}s",
                flush=True
            )

            # SRT timing is the master.
            # Fit every TTS clip inside its exact SRT window.
            tempo = raw_duration / target_duration
            tempo = max(0.50, min(4.0, tempo))

            tempo_cmd = [
                "ffmpeg",
                "-y",
                "-i",
                raw_path,
                "-filter:a",
                f"atempo={tempo:.6f}",
                "-c:a",
                "libmp3lame",
                "-b:a",
                "192k",
                fitted_path
            ]

            tempo_result = subprocess.run(
                tempo_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )

            if tempo_result.returncode != 0:
                raise RuntimeError(
                    "TTS timing adjustment failed: "
                    + tempo_result.stdout[-3000:]
                )

            fitted_duration = float(
                subprocess.run(
                    [
                        "ffprobe",
                        "-v", "error",
                        "-show_entries", "format=duration",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        fitted_path
                    ],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True
                ).stdout.strip()
            )

            # Never allow TTS to cross the SRT end time.
            if fitted_duration > target_duration:
                trim_path = fitted_path + ".trim.mp3"

                trim_result = subprocess.run(
                    [
                        "ffmpeg",
                        "-y",
                        "-i",
                        fitted_path,
                        "-t",
                        f"{target_duration:.3f}",
                        "-c:a",
                        "libmp3lame",
                        "-b:a",
                        "192k",
                        trim_path
                    ],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True
                )

                if trim_result.returncode != 0:
                    raise RuntimeError(
                        "TTS exact-duration trim failed: "
                        + trim_result.stdout[-3000:]
                    )

                os.replace(trim_path, fitted_path)
                fitted_duration = target_duration

            print(
                f"DUB TTS FITTED: "
                f"tempo={tempo:.3f}, "
                f"duration={fitted_duration:.3f}s",
                flush=True
            )

            segment_files.append(
                (start, fitted_path)
            )

        if not segment_files:
            raise RuntimeError(
                "No segment TTS audio was generated"
            )

        inputs = []
        filters = []

        for i, (start, path) in enumerate(segment_files):
            inputs.extend([
                "-i",
                path
            ])

            delay_ms = max(
                0,
                int(round(start * 1000))
            )

            filters.append(
                f"[{i}:a]"
                f"adelay={delay_ms}:all=1"
                f"[a{i}]"
            )

        labels = "".join(
            f"[a{i}]"
            for i in range(len(segment_files))
        )

        filters.append(
            f"{labels}"
            f"amix=inputs={len(segment_files)}:"
            f"duration=longest:"
            f"dropout_transition=0"
            f"[out]"
        )

        output_name = (
            "timestamped_tts_" +
            uuid.uuid4().hex +
            ".mp3"
        )

        output_path = os.path.join(
            UPLOAD_DIR,
            output_name
        )

        ffmpeg_cmd = [
            "ffmpeg",
            "-y",
            *inputs,
            "-filter_complex",
            ";".join(filters),
            "-map",
            "[out]",
            "-c:a",
            "libmp3lame",
            "-b:a",
            "192k",
            output_path
        ]

        result = subprocess.run(
            ffmpeg_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True
        )

        if result.returncode != 0:
            raise RuntimeError(
                "Timestamp audio mix failed: "
                + result.stdout[-3000:]
            )

        if (
            not os.path.exists(output_path)
            or os.path.getsize(output_path) == 0
        ):
            raise RuntimeError(
                "Timestamped TTS output was not created"
            )

        print(
            f"DUB MIX OK: {output_name}",
            flush=True
        )

        return (
            output_name,
            translated_segments
        )

    finally:
        shutil.rmtree(
            work_dir,
            ignore_errors=True
        )

class Handler(BaseHTTPRequestHandler):

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "86400")
        self.end_headers()

    def do_GET(self):

        if self.path == "/":
            self.send_json({
                "status": "ok",
                "service": "Video Dubbing Backend"
            })
            return

        if self.path == "/dub":
            self.send_json({
                "status": "ready",
                "message": "Video dubbing pipeline ready"
            })
            return

        if self.path.startswith("/output/"):
            filename = os.path.basename(self.path[len("/output/"):])
            file_path = os.path.join(UPLOAD_DIR, filename)

            if not os.path.isfile(file_path):
                self.send_json({
                    "error": "Output file not found"
                }, 404)
                return

            content_type = "video/mp4"
            if filename.endswith(".mp3"):
                content_type = "audio/mpeg"
            elif filename.endswith(".wav"):
                content_type = "audio/wav"
            elif filename.endswith(".txt"):
                content_type = "text/plain; charset=utf-8"

            elif filename.endswith(".srt"):
                content_type = "application/x-subrip; charset=utf-8"

            file_size = os.path.getsize(file_path)

            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(file_size))
            self.send_header("Content-Disposition", 'inline; filename="' + filename + '"')
            self.end_headers()

            with open(file_path, "rb") as f:
                while True:
                    chunk = f.read(1024 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)

            return

        self.send_json({
            "error": "Not found"
        }, 404)

    def do_POST(self):
        print("POST REQUEST:", self.path, flush=True)

        # TRANSLATION + TTS ENDPOINT
        if self.path == "/translate-tts":
            content_length = int(
                self.headers.get("Content-Length", "0")
            )

            if content_length <= 0:
                self.send_json(
                    {"error": "Empty request"},
                    400
                )
                return

            body = self.rfile.read(content_length)

            try:
                data = json.loads(
                    body.decode("utf-8")
                )
            except Exception:
                self.send_json(
                    {"error": "Invalid JSON"},
                    400
                )
                return

            transcript = str(
                data.get("transcript", "")
            ).strip()

            segments = data.get("segments", [])
            srt_content = str(
                data.get("srt_content", "")
            ).strip()

            if not isinstance(segments, list):
                segments = []

            direction = str(
                data.get("direction", "ta-en")
            ).strip().lower()

            role = str(
                data.get("role", "Other")
            ).strip()

            if direction == "ta-en":
                src_lang = "tam_Taml"
                tgt_lang = "eng_Latn"
                tts_voice = "en-US-JennyNeural"
                tts_label = "English"

            elif direction == "en-ta":
                src_lang = "eng_Latn"
                tgt_lang = "tam_Taml"
                tts_voice = "ta-IN-PallaviNeural"
                tts_label = "Tamil"

            else:
                self.send_json(
                    {"error": "Unsupported direction: " + direction},
                    400
                )
                return

            if not transcript:
                self.send_json(
                    {"error": "Transcript is empty"},
                    400
                )
                return

            transcript = clean_transcript(transcript)

            try:
                print(
                    "TRANSLATION/TTS: processing timestamped segments...",
                    flush=True
                )

                if srt_content:
                    import re

                    def parse_srt_timestamp(value):
                        value = value.strip().replace(",", ".")
                        parts = value.split(":")
                        if len(parts) != 3:
                            raise ValueError(
                                f"Invalid SRT timestamp: {value}"
                            )
                        hours = float(parts[0])
                        minutes = float(parts[1])
                        seconds = float(parts[2])
                        return hours * 3600 + minutes * 60 + seconds

                    srt_segments = []
                    blocks = re.split(
                        r"\n\s*\n",
                        srt_content.replace("\r\n", "\n").replace("\r", "\n").strip()
                    )

                    for block in blocks:
                        lines = [
                            line.strip()
                            for line in block.split("\n")
                            if line.strip()
                        ]

                        if len(lines) < 3:
                            continue

                        timing_index = 1
                        if "-->" not in lines[timing_index]:
                            continue

                        timing = lines[timing_index]
                        start_text, end_text = [
                            part.strip()
                            for part in timing.split("-->", 1)
                        ]

                        try:
                            start = parse_srt_timestamp(start_text)
                            end = parse_srt_timestamp(end_text)
                        except Exception:
                            continue

                        text = " ".join(lines[timing_index + 1:]).strip()

                        if text and end > start:
                            srt_segments.append({
                                "id": len(srt_segments),
                                "start": start,
                                "end": end,
                                "text": text
                            })

                    if srt_segments:
                        segments = srt_segments
                        print(
                            f"TRANSLATION/TTS: using SRT timing master ({len(segments)} segments)",
                            flush=True
                        )

                usable_segments = []

                for seg in segments:
                    if not isinstance(seg, dict):
                        continue

                    seg_text = str(
                        seg.get("text", "")
                    ).strip()

                    if seg_text:
                        usable_segments.append(seg)

                if not usable_segments and transcript:
                    print(
                        "TRANSLATION/TTS: no usable segments; "
                        "using transcript fallback",
                        flush=True
                    )

                    usable_segments = [{
                        "id": 0,
                        "start": 0.0,
                        "end": 999999.0,
                        "text": transcript
                    }]

                if not usable_segments:
                    raise RuntimeError(
                        "Transcript and timestamped segments are both empty"
                    )

                print(
                    f"TRANSLATION/TTS: usable segments = "
                    f"{len(usable_segments)}",
                    flush=True
                )

                tts_name, translated_segments = (
                    build_timestamped_dubbed_audio(
                        usable_segments,
                        src_lang=src_lang,
                        tgt_lang=tgt_lang,
                        voice=tts_voice
                    )
                )

                translated_text = "\n".join(
                    item["translated_text"]
                    for item in translated_segments
                )

                if not translated_text:
                    raise RuntimeError(
                        "Translation returned empty text"
                    )

                translated_srt_name = (
                    f"translated_{uuid.uuid4().hex}.srt"
                )
                translated_srt_path = os.path.join(
                    UPLOAD_DIR, translated_srt_name
                )

                def translated_srt_time(seconds):
                    milliseconds = round(float(seconds) * 1000)
                    hours = milliseconds // 3600000
                    minutes = (milliseconds % 3600000) // 60000
                    seconds_part = (milliseconds % 60000) // 1000
                    millis = milliseconds % 1000
                    return (
                        f"{hours:02}:{minutes:02}:"
                        f"{seconds_part:02},{millis:03}"
                    )

                with open(
                    translated_srt_path, "w", encoding="utf-8"
                ) as f:
                    for index, item in enumerate(translated_segments, 1):
                        text = str(
                            item.get("translated_text", "")
                        ).strip()
                        if not text:
                            continue

                        start = translated_srt_time(item.get("start", 0))
                        end = translated_srt_time(item.get("end", 0))
                        f.write(
                            f"{index}\\n{start} --> {end}\\n{text}\\n\\n"
                        )

                print(
                    f"TRANSLATED SRT CREATED: {translated_srt_name}",
                    flush=True
                )

                print(
                    "TTS: timestamped dubbed audio ready",
                    flush=True
                )

                self.send_json({
                    "status": "translated_tts",
                    "translated_text":
                        translated_text,
                    "segments":
                        translated_segments,
                    "translated_srt_file": translated_srt_name,
                    "translated_srt_url": "/output/" + translated_srt_name,
                    "tts_file":
                        tts_name,
                    "tts_url":
                        "/output/" + tts_name
                })

            except Exception as e:
                print(
                    "TRANSLATION/TTS ERROR:",
                    repr(e),
                    flush=True
                )

                self.send_json({
                    "status": "error",
                    "stage": "translation_tts",
                    "error": str(e)
                }, 500)

            return

        # VIDEO + TTS MERGE ENDPOINT
        if self.path == "/merge-video-audio":
            content_length = int(
                self.headers.get("Content-Length", "0")
            )

            if content_length <= 0:
                self.send_json(
                    {"error": "Empty request"},
                    400
                )
                return

            try:
                data = json.loads(
                    self.rfile.read(content_length).decode("utf-8")
                )
            except Exception:
                self.send_json(
                    {"error": "Invalid JSON"},
                    400
                )
                return

            video_file = str(
                data.get("video_file", "")
            ).strip()

            tts_file = str(
                data.get("tts_file", "")
            ).strip()

            if not video_file or not tts_file:
                self.send_json(
                    {
                        "error": "video_file and tts_file are required"
                    },
                    400
                )
                return

            video_path = os.path.join(
                UPLOAD_DIR,
                os.path.basename(video_file)
            )

            tts_path = os.path.join(
                UPLOAD_DIR,
                os.path.basename(tts_file)
            )

            if not os.path.exists(video_path):
                self.send_json(
                    {"error": "Video file not found"},
                    404
                )
                return

            if not os.path.exists(tts_path):
                self.send_json(
                    {"error": "TTS file not found"},
                    404
                )
                return

            dubbed_name = (
                "dubbed_" +
                uuid.uuid4().hex +
                ".mp4"
            )

            dubbed_path = os.path.join(
                UPLOAD_DIR,
                dubbed_name
            )

            try:
                print(
                    "FFMPEG: merging video + TTS...",
                    flush=True
                )

                merge_video_audio(
                    video_path,
                    tts_path,
                    dubbed_path
                )

                print(
                    "DUBBED VIDEO OK:",
                    dubbed_path,
                    flush=True
                )

                self.send_json({
                    "status": "merged",
                    "dubbed_video": dubbed_name,
                    "dubbed_video_url":
                        "/output/" + dubbed_name
                })

            except Exception as e:
                print(
                    "MERGE ERROR:",
                    repr(e),
                    flush=True
                )

                self.send_json({
                    "status": "error",
                    "stage": "merge",
                    "error": str(e)
                }, 500)

            return

        # TRANSCRIPTION AUDIO ENDPOINT
        if self.path == "/transcribe":
            content_length = int(self.headers.get("Content-Length", "0"))
            if content_length <= 0:
                self.send_json({"error": "Empty audio upload"}, 400)
                return

            marker = self.headers.get("Content-Type", "")
            if "boundary=" not in marker:
                self.send_json({"error": "Multipart boundary missing"}, 400)
                return

            boundary = marker.split("boundary=", 1)[-1].strip().strip('"').encode()

            # Read multipart body once.
            # Keep the existing Android multipart format compatible.
            body = self.rfile.read(content_length)

            audio_data = None
            video_data = None
            srt_data = None

            for part in body.split(b"--" + boundary):
                header_end = part.find(b"\r\n\r\n")

                if header_end == -1:
                    continue

                headers = part[:header_end]
                content = part[header_end + 4:]

                # Remove only multipart CRLF/boundary padding.
                if content.endswith(b"\r\n"):
                    content = content[:-2]
                if content.endswith(b"--"):
                    content = content[:-2]
                if content.endswith(b"\r\n"):
                    content = content[:-2]

                if b'name="audio"' in headers:
                    audio_data = content

                elif b'name="video"' in headers:
                    video_data = content
                elif b'name="srt"' in headers:
                    srt_data = content

            if not audio_data:
                self.send_json({
                    "error": "Audio field not found; expected field name audio"
                }, 400)
                return

            audio_name = f"audio_{uuid.uuid4().hex}.wav"
            audio_path = os.path.join(UPLOAD_DIR, audio_name)
            transcript_path = audio_path + ".txt"
            srt_name = ""
            srt_path = ""

            if srt_data:
                srt_name = f"subtitle_{uuid.uuid4().hex}.srt"
                srt_path = os.path.join(UPLOAD_DIR, srt_name)
                with open(srt_path, "wb") as f:
                    f.write(srt_data)
                print(f"SRT UPLOAD OK: {srt_name}", flush=True)


            video_name = ""
            video_path = ""

            if video_data:
                video_name = f"video_{uuid.uuid4().hex}.mp4"
                video_path = os.path.join(UPLOAD_DIR, video_name)

                with open(video_path, "wb") as f:
                    f.write(video_data)

                print(
                    f"VIDEO UPLOAD OK: {video_name} ({len(video_data)} bytes)",
                    flush=True
                )

            with open(audio_path, "wb") as f:
                f.write(audio_data)

            print(
                f"AUDIO UPLOAD OK: {audio_name} ({len(audio_data)} bytes)",
                flush=True
            )

            # Normalize uploaded audio for Groq Whisper.
            # Android may send the source audio at arbitrary sample rate/channels.
            normalized_audio_path = audio_path + ".16k.wav"

            ffmpeg_cmd = [
                "ffmpeg",
                "-y",
                "-i",
                audio_path,
                "-ac",
                "1",
                "-ar",
                "16000",
                "-c:a",
                "pcm_s16le",
                normalized_audio_path
            ]

            ffmpeg_result = subprocess.run(
                ffmpeg_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )

            if ffmpeg_result.returncode != 0:
                raise RuntimeError(
                    "Audio normalization failed: " +
                    ffmpeg_result.stderr[-2000:]
                )

            audio_path = normalized_audio_path

            try:
                groq_segments = []

                if GROQ_API_KEY:
                    transcription_mode = "GROQ"
                    print("TRANSCRIPTION MODE: GROQ", flush=True)

                    groq_result = transcribe_with_groq(audio_path)

                    if not groq_result:
                        raise RuntimeError("Groq transcription returned empty result")

                    transcript = str(
                        getattr(groq_result, "text", "") or ""
                    ).strip()

                    if not transcript:
                        raise RuntimeError("Groq transcription text is empty")

                    groq_segments = getattr(
                        groq_result, "segments", None
                    ) or []

                    def groq_segment_value(seg, key, default=""):
                        if isinstance(seg, dict):
                            return seg.get(key, default)
                        return getattr(seg, key, default)

                    print(
                        f"GROQ: received {len(groq_segments)} timestamped segments",
                        flush=True
                    )

                else:
                    transcription_mode = "LOCAL_WHISPER"
                    print("TRANSCRIPTION MODE: LOCAL WHISPER", flush=True)

                    whisper_cmd = [
                        WHISPER_BIN, "-m", WHISPER_MODEL,
                        "-f", audio_path, "-l", "ta",
                        "-otxt", "-nt",
                        "--prompt",
                        "இது ஒரு தமிழ் திரைப்பட உரையாடல். கதாபாத்திரங்கள் தமிழில் பேசுகிறார்கள்."
                    ]

                    result = subprocess.run(
                        whisper_cmd,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT,
                        text=True
                    )

                    if result.returncode != 0:
                        raise RuntimeError(
                            "Whisper transcription failed: " + result.stdout[-2000:]
                        )

                    if not os.path.exists(transcript_path):
                        raise RuntimeError("Whisper transcript file was not created")

                    with open(transcript_path, "r", encoding="utf-8") as f:
                        transcript = f.read().strip()

                    if not transcript:
                        raise RuntimeError("Transcript is empty")

                with open(transcript_path, "w", encoding="utf-8") as f:
                    f.write(transcript)

                print("TRANSCRIPT:", flush=True)
                print(transcript, flush=True)

                srt_name = ""

                if transcription_mode == "GROQ" and groq_segments:
                    def srt_time(seconds):
                        milliseconds = round(float(seconds) * 1000)
                        hours = milliseconds // 3600000
                        minutes = (milliseconds % 3600000) // 60000
                        seconds_part = (milliseconds % 60000) // 1000
                        millis = milliseconds % 1000
                        return f"{hours:02}:{minutes:02}:{seconds_part:02},{millis:03}"

                    srt_name = f"original_{uuid.uuid4().hex}.srt"
                    srt_path = os.path.join(UPLOAD_DIR, srt_name)

                    with open(srt_path, "w", encoding="utf-8") as f:
                        for index, seg in enumerate(groq_segments, 1):
                            text = str(groq_segment_value(seg, "text", "")).strip()
                            if not text:
                                continue
                            start = srt_time(groq_segment_value(seg, "start", 0))
                            end = srt_time(groq_segment_value(seg, "end", 0))
                            f.write(f"{index}\n{start} --> {end}\n{text}\n\n")

                    print(f"ORIGINAL SRT CREATED: {srt_name}", flush=True)

                response_data = {
                    "status": "transcribed",
                    "transcript": transcript,
                    "transcript_file": os.path.basename(transcript_path),
                    "transcription_mode": transcription_mode,
                    "srt_file": srt_name,
                    "srt_url": "/output/" + srt_name if srt_name else "",
                    "srt_content": open(srt_path, "r", encoding="utf-8").read() if srt_name else "",
                    "video_file": video_name
                }

                if transcription_mode == "GROQ":
                    response_data["segments"] = [
                        {
                            "id": int(groq_segment_value(seg, "id", 0)),
                            "start": float(groq_segment_value(seg, "start", 0)),
                            "end": float(groq_segment_value(seg, "end", 0)),
                            "text": str(groq_segment_value(seg, "text", "")).strip()
                        }
                        for seg in (groq_segments or [])
                    ]

                self.send_json(response_data)

            except Exception as e:
                print("TRANSCRIPTION ERROR:", repr(e), flush=True)

                self.send_json({
                    "status": "error",
                    "stage": "transcription",
                    "error": str(e)
                }, 500)

            return

        if self.path != "/dub":
            self.send_json({
                "error": "Not found"
            }, 404)
            return

        content_length = int(
            self.headers.get("Content-Length", "0")
        )

        if content_length <= 0:
            self.send_json({
                "error": "Empty upload"
            }, 400)
            return

        video_name = f"video_{uuid.uuid4().hex}.mp4"
        video_path = os.path.join(
            UPLOAD_DIR,
            video_name
        )

        audio_name = video_name.replace(
            ".mp4",
            ".wav"
        )

        audio_path = os.path.join(
            UPLOAD_DIR,
            audio_name
        )

        transcript_name = audio_name + ".txt"

        transcript_path = os.path.join(
            UPLOAD_DIR,
            transcript_name
        )

        body = self.rfile.read(content_length)

        marker = self.headers.get("Content-Type", "")
        boundary = marker.split("boundary=", 1)[-1].encode()

        parts = body.split(b"--" + boundary)

        video_data = None

        for part in parts:
            if b'name="video"' in part:
                    header_end = part.find(b"\r\n\r\n")
                    if header_end != -1:
                        video_data = part[header_end + 4:]
                        video_data = video_data.rstrip(b"\r\n-")
                        break

        if not video_data:
            self.send_json({
                "error": "Video upload field not found"
            }, 400)
            return

        with open(video_path, "wb") as f:
            f.write(video_data)

        print("UPLOAD: OK")
        print("Saved bytes:", os.path.getsize(video_path))

        # -----------------------------
        # 2. EXTRACT AUDIO
        # -----------------------------

        print("FFMPEG: extracting audio...")

        ffmpeg_cmd = [
            "ffmpeg",
            "-y",
            "-i",
            video_path,
            "-map",
            "0:a:0",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            audio_path
        ]

        ffmpeg_result = subprocess.run(
            ffmpeg_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

        if ffmpeg_result.returncode != 0:

            print("FFMPEG ERROR")
            print(ffmpeg_result.stderr[-3000:])

            self.send_json({
                "status": "error",
                "stage": "audio_extraction",
                "error": "FFmpeg audio extraction failed",
                "details": ffmpeg_result.stderr[-3000:]
            }, 500)

            return

        print("FFMPEG: OK")

        # -----------------------------
        # 3. TRANSCRIPTION
        # -----------------------------

        if GROQ_API_KEY:
            print("GROQ: transcribing...")
            try:
                transcript = transcribe_with_groq(audio_path)
                transcript = str(transcript).strip()

                with open(
                    transcript_path,
                    "w",
                    encoding="utf-8"
                ) as f:
                    f.write(transcript)

                print("GROQ TRANSCRIPT:")
                print(transcript)

            except Exception as e:
                print("GROQ TRANSCRIPTION ERROR:", repr(e))

                self.send_json({
                    "status": "error",
                    "stage": "groq_transcription",
                    "error": str(e)
                }, 500)

                return

        else:
            print("WHISPER: transcribing...")

            whisper_cmd = [
                WHISPER_BIN,
                "-m",
                WHISPER_MODEL,
                "-f",
                audio_path,
                "-l",
                "ta",
                "-otxt",
                "-nt",
                "--prompt",
                "இது ஒரு தமிழ் திரைப்பட உரையாடல். கதாபாத்திரங்கள் தமிழில் பேசுகிறார்கள்."
            ]

            whisper_result = subprocess.run(
                whisper_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )

            print(whisper_result.stdout)

            if whisper_result.returncode != 0:
                self.send_json({
                    "status": "error",
                    "stage": "whisper",
                    "error": "Whisper transcription failed"
                }, 500)

                return

        # -----------------------------
        # 4. READ TRANSCRIPT
        # -----------------------------

        if not os.path.exists(transcript_path):

            self.send_json({
                "status": "error",
                "stage": "transcript",
                "error": "Transcript file not created"
            }, 500)

            return

        with open(
            transcript_path,
            "r",
            encoding="utf-8"
        ) as f:

            transcript = f.read().strip()

        print("TRANSCRIPT:")
        print(transcript)

        transcript = clean_transcript(transcript)

        print("CLEANED TRANSCRIPT:")
        print(transcript)

        # -----------------------------
        # 5. TRANSLATION
        # -----------------------------
        print("TRANSLATION: translating...")
        try:
            translated_text = translate_movie_dialogue(
                transcript,
                src_lang="tam_Taml",
                tgt_lang="eng_Latn"
            )
            if not translated_text or not translated_text.strip():
                raise RuntimeError("Translation returned empty text")
            print("TRANSLATED TEXT:")
            print(translated_text)
        except Exception as e:
            print("TRANSLATION ERROR:", repr(e))
            self.send_json({
                "status": "error",
                "stage": "translation",
                "error": str(e)
            }, 500)
            return

        print("TRANSLATED TEXT:")
        print(translated_text)

        # -----------------------------
        # 6. TTS
        # -----------------------------
        print("TTS: generating English voice...")

        tts_name = os.path.splitext(video_name)[0] + "_tts.mp3"
        tts_path = os.path.join(UPLOAD_DIR, tts_name)

        try:
            synthesize_tts(
                translated_text,
                tts_path,
                voice="en-US-JennyNeural"
            )
        except Exception as e:
            print("TTS ERROR:", repr(e))
            self.send_json({
                "status": "error",
                "stage": "tts",
                "error": str(e)
            }, 500)
            return

        print("TTS OK:", tts_path)

        # -----------------------------
        # 7. VIDEO + TTS MERGE
        # -----------------------------
        print("FFMPEG: merging dubbed audio with video...")

        dubbed_name = os.path.splitext(video_name)[0] + "_dubbed.mp4"
        dubbed_path = os.path.join(UPLOAD_DIR, dubbed_name)

        try:
            merge_video_audio(
                video_path,
                tts_path,
                dubbed_path
            )
        except Exception as e:
            print("MERGE ERROR:", repr(e))
            self.send_json({
                "status": "error",
                "stage": "merge",
                "error": str(e)
            }, 500)
            return

        print("DUBBED VIDEO OK:", dubbed_path)

        # -----------------------------
        # 8. RESPONSE
        # -----------------------------

        self.send_json({

            "status": "dubbed",

            "video": video_name,

            "audio": audio_name,

            "transcript_file": transcript_name,

            "transcript": transcript,

            "translated_text": translated_text,

            "tts_file": tts_name,

            "dubbed_video": dubbed_name,

            "message": "Video uploaded, audio extracted, translated, TTS generated and dubbed video created"

        })

    def log_message(self, format, *args):
        print(format % args)


if __name__ == "__main__":
    PORT = int(os.environ.get("PORT", "8081"))

    server = HTTPServer(
        ("0.0.0.0", PORT),
        Handler
    )

    print("")
    print("======================================")
    print(" Video Dubbing Backend")
    print(f" http://0.0.0.0:{PORT}")
    print("======================================")
    print("")

    server.serve_forever()
