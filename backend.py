import os
from http.server import BaseHTTPRequestHandler, HTTPServer
import sys
import json
import os
import uuid
import subprocess

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
UPLOAD_DIR = os.path.join(BASE_DIR, "uploads")
GROQ_API_KEY = ""
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
if GROQ_API_KEY:
    print("GROQ_API_KEY: configured")
else:
    print("GROQ_API_KEY: not configured (local Whisper mode)")
WHISPER_BIN = os.path.join(BASE_DIR, "whisper.cpp/build/bin/whisper-cli")
WHISPER_MODEL = os.path.join(BASE_DIR, "whisper.cpp/models/ggml-medium.bin")

os.makedirs(UPLOAD_DIR, exist_ok=True)



def transcribe_with_groq(audio_path):
    if not GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY is not configured")

    from groq import Groq

    client = Groq(api_key=GROQ_API_KEY)

    with open(audio_path, "rb") as audio_file:
        transcription = client.audio.transcriptions.create(
            file=audio_file,
            model="whisper-large-v3-turbo",
            language="ta",
            response_format="text"
        )

    return transcription


def translate_text(text, src_lang="tam_Taml", tgt_lang="eng_Latn"):
    if not text.strip():
        return ""

    if not GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY is not configured")

    from groq import Groq

    client = Groq(api_key=GROQ_API_KEY)

    prompt = f"""Translate the following Tamil movie dialogue into natural spoken English.
Keep the meaning, emotion, and dialogue style.
Return ONLY the English translation.
Do not explain anything.

Tamil dialogue:
{text}
"""

    response = client.chat.completions.create(
        model="openai/gpt-oss-120b",
        messages=[
            {
                "role": "system",
                "content": "You are a professional Tamil-to-English movie dialogue translator."
            },
            {
                "role": "user",
                "content": prompt
            }
        ],
        temperature=0.6,
        max_completion_tokens=1024,
        include_reasoning=False
    )

    translated = response.choices[0].message.content.strip()

    if not translated:
        raise RuntimeError("Groq translation returned empty result")

    print("GROQ TRANSLATION:")
    print(translated)

    return translated




def merge_video_audio(video_path, audio_path, output_path):
    cmd = [
        "ffmpeg",
        "-y",
        "-i", video_path,
        "-i", audio_path,
        "-map", "0:v:0",
        "-map", "1:a:0",
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "192k",
        "-shortest",
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

    if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
        raise RuntimeError("Merged video was not created")

    return output_path


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


class Handler(BaseHTTPRequestHandler):

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")

        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()

        self.wfile.write(body)

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

        self.send_json({
            "error": "Not found"
        }, 404)

    def do_POST(self):

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

        # -----------------------------
        # 5. TRANSLATION
        # -----------------------------
        print("TRANSLATION: translating...")
        try:
            translated_text = translate_text(
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
