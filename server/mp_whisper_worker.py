"""Whisper worker for mp_retime: runs under the Python of a separate venv with faster-whisper (not the agent's: the
model and its CUDA libraries are large, and a crash or a stuck GPU must not take the agent down).

usage: mp_whisper_worker.py VIDEO MODEL DEVICE [LANGUAGE]
DEVICE: cuda, cpu or auto (cuda when it loads, else cpu). Then one JSON request per line on stdin:
{"a": seconds, "b": seconds} → one JSON line on stdout: {"words": [[start s in the film, "word"], ...], "secs": took}.
The first line out is {"ready": device, "compute": type, "model": name} (or {"error": ...}).

Lessons from tests (2026-11-25/26, two old films): pass a numpy array (PyAV in faster-whisper 1.2.1 fails on some
files); vad_filter=False (with it, words came out 5-6 s early where speech was sparse); condition_on_previous_text=False
(otherwise one misheard stretch repeats); each piece decoded with -ss before -i is exact for these short pieces."""
import json
import subprocess
import sys
import time


def main():
    video, model_name, device = sys.argv[1:4]
    language = sys.argv[4] if len(sys.argv) > 4 and sys.argv[4] else None
    try:
        import numpy as np
        from faster_whisper import WhisperModel
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": f"faster-whisper missing: {e}"}), flush=True)
        return 1
    model = None
    tried = ["cuda", "cpu"] if device == "auto" else [device]
    err = None
    for dev in tried:
        ct = "float32" if dev == "cuda" else "int8"  # float16 isn't fast on old cards (Maxwell); int8 for processors
        try:
            model = WhisperModel(model_name, device=dev, compute_type=ct)
            # a model that loads can still fail on the first piece (an old card, a missing CUDA library)
            model.transcribe(np.zeros(16000, np.float32), language=language or "en", vad_filter=False)
            break
        except Exception as e:  # noqa: BLE001
            err, model = f"{dev}: {e}", None
    if model is None:
        print(json.dumps({"error": f"model {model_name} didn't load ({err})"}), flush=True)
        return 1
    print(json.dumps({"ready": dev, "compute": ct, "model": model_name}), flush=True)
    for line in sys.stdin:
        try:
            req = json.loads(line)
            a, b = float(req["a"]), float(req["b"])
            t = time.time()
            pcm = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-ss", f"{a:.2f}", "-i", video, "-t", f"{b - a:.2f}",
                                  "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", "-"],
                                 capture_output=True, check=True).stdout
            audio = np.frombuffer(pcm, np.int16).astype(np.float32) / 32768
            segs, _ = model.transcribe(audio, word_timestamps=True, vad_filter=False, condition_on_previous_text=False,
                                       language=language)
            words = [[round(a + w.start, 2), w.word.strip()] for s in segs for w in (s.words or []) if w.word.strip()]
            print(json.dumps({"words": words, "secs": round(time.time() - t, 2)}), flush=True)
        except Exception as e:  # noqa: BLE001 - the agent decides
            print(json.dumps({"error": str(e)}), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
