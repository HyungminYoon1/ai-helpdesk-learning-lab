"""Build-time public artifact download; no Hub token, pickle, or remote Python."""

import hashlib
import json
from pathlib import Path
import sys
from urllib.request import urlopen


def main():
    lock = json.loads(Path(__file__).with_name("model-lock.json").read_text(encoding="utf-8"))
    # Neither repository, revision nor filenames are provided by a request.
    if (lock["repository"] != "amoeba04/koelectra-small-v3-privacy-ner"
            or lock["revision"] != "9f4e2fd9e35b12bcdb5fc334ac31be4399cb4281"
            or set(lock["files"]) != {"config.json", "vocab.txt", "tokenizer_config.json",
                                      "special_tokens_map.json", "model.safetensors", "README.md"}):
        raise ValueError("MODEL_LOCK_INVALID")
    destination = Path("/opt/privacy-model")
    destination.mkdir(exist_ok=True)
    for name, expected in lock["files"].items():
        url = f'https://huggingface.co/{lock["repository"]}/resolve/{lock["revision"]}/{name}'
        limit = 64 * 1024 * 1024 if name == "model.safetensors" else 1024 * 1024
        with urlopen(url, timeout=60) as response:
            if not response.url.startswith("https://"):
                raise ValueError("MODEL_DOWNLOAD_TLS_REQUIRED")
            data = response.read(limit + 1)
        if len(data) > limit or hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("MODEL_ARTIFACT_MISMATCH")
        (destination / name).write_bytes(data)
    print("PUBLIC_MODEL_ARTIFACTS_VERIFIED")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("PUBLIC_MODEL_DOWNLOAD_FAILED", file=sys.stderr)
        sys.exit(1)
