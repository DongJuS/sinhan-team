#!/usr/bin/env python3
"""Start a project-owned localhost Ollama, pull Qwen and create its bounded alias."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen

root = Path(__file__).resolve().parents[1]
endpoint = "http://127.0.0.1:11435"
ollama = shutil.which("ollama")
if not ollama:
    raise SystemExit(
        "Install Ollama from https://ollama.com/download, then rerun this script."
    )
artifacts = root / ".artifacts"
models = root / ".local-ai" / "models"
artifacts.mkdir(exist_ok=True)
models.mkdir(parents=True, exist_ok=True)
env = {
    **os.environ,
    "OLLAMA_HOST": "127.0.0.1:11435",
    "OLLAMA_MODELS": str(models),
    "OLLAMA_NO_CLOUD": "1",
    "OLLAMA_NUM_PARALLEL": "1",
    "OLLAMA_MAX_LOADED_MODELS": "1",
    "OLLAMA_CONTEXT_LENGTH": "4096",
    "OLLAMA_FLASH_ATTENTION": "1",
    "OLLAMA_KV_CACHE_TYPE": "q8_0",
}


def available():
    try:
        with urlopen(endpoint + "/api/version", timeout=2) as response:
            return bool(json.load(response).get("version"))
    except (URLError, TimeoutError, ValueError):
        return False


if not available():
    with (artifacts / "ollama.log").open("ab") as log:
        process = subprocess.Popen(
            [ollama, "serve"],
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    (artifacts / "ollama.pid").write_text(str(process.pid))
    for _ in range(30):
        if available():
            break
        if process.poll() is not None:
            raise SystemExit("Ollama stopped; inspect .artifacts/ollama.log")
        time.sleep(1)
    else:
        raise SystemExit("Ollama did not become ready")

subprocess.run([ollama, "pull", "qwen3:14b-q8_0"], env=env, check=True)
subprocess.run(
    [
        ollama,
        "create",
        "sinhan-qwen:14b-q8_0",
        "-f",
        str(root / "infra/ollama/Modelfile"),
    ],
    env=env,
    check=True,
)
print("Local Qwen ready at " + endpoint + "; model sinhan-qwen:14b-q8_0")
print(
    "Mac GPU memory is measured by scripts/check-local-ai.sh; model weights are excluded from Git."
)
