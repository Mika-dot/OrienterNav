from __future__ import annotations

import importlib
import os
import platform
import sys
from pathlib import Path

SERVICE_DIR = Path(__file__).resolve().parent
REPO_DIR = SERVICE_DIR.parent


def check_import(module: str) -> tuple[bool, str]:
    try:
        imported = importlib.import_module(module)
        version = getattr(imported, "__version__", "installed")
        return True, str(version)
    except Exception as exc:
        return False, f"{type(exc).__name__}: {exc}"


def main() -> int:
    print(f"Python: {sys.version.split()[0]}")
    print(f"Platform: {platform.platform()}")

    required = [
        "fastapi",
        "uvicorn",
        "numpy",
        "torch",
        "torchvision",
        "omegaconf",
        "cv2",
        "timm",
        "rtree",
        "shapely",
        "pyproj",
        "maploc",
        "perspective2d",
    ]

    failed = False
    for module in required:
        ok, detail = check_import(module)
        print(f"{module:16} {'OK' if ok else 'FAIL'}  {detail}")
        failed = failed or not ok

    try:
        import torch

        print(f"CUDA available: {torch.cuda.is_available()}")
        print(f"Torch CUDA: {torch.version.cuda}")
        if torch.cuda.is_available():
            print(f"GPU: {torch.cuda.get_device_name(0)}")
        else:
            print("WARNING: CUDA is unavailable. Real inference will run on CPU and is not recommended.")
    except Exception as exc:
        print(f"CUDA check failed: {exc}")
        failed = True

    candidates = [
        Path(os.getenv("ORIENTERNET_CHECKPOINT", "")) if os.getenv("ORIENTERNET_CHECKPOINT") else None,
        REPO_DIR / "orienternet_mgl.ckpt",
        SERVICE_DIR / "orienternet_mgl.ckpt",
    ]
    checkpoint = next((p.resolve() for p in candidates if p and p.is_file()), None)
    print(f"Checkpoint: {checkpoint if checkpoint else 'not found (can be downloaded by OrienterNet on first load)'}")

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
