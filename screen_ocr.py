import time
import json
import os
import platform
import subprocess
import tempfile
from pathlib import Path

import cv2


_reader = None
_BACKEND = os.environ.get("TJB_OCR_BACKEND", "auto").strip().lower()
_BASE_DIR = Path(__file__).resolve().parent
_APPLE_VISION_BIN_CANDIDATES = [
    _BASE_DIR / "vision_ocr" / "vision_ocr",
    _BASE_DIR.parent / "vision_ocr" / "vision_ocr",
]


def _apple_vision_bin():
    for path in _APPLE_VISION_BIN_CANDIDATES:
        if path.exists() and os.access(path, os.X_OK):
            return path
    return None


def _use_apple_vision():
    if _BACKEND == "apple_vision":
        return True
    if _BACKEND in ("easyocr", "easy_ocr"):
        return False
    return platform.system() == "Darwin" and _apple_vision_bin() is not None


def get_reader(gpu=True):
    if _use_apple_vision():
        return "apple_vision"
    global _reader
    if _reader is None:
        import easyocr
        _reader = easyocr.Reader(["ch_sim"], gpu=gpu, verbose=False)
    return _reader


def resize_for_ocr(image, scale_factor=0.5):
    height, width = image.shape[:2]
    if scale_factor >= 1.0:
        return image, 1.0
    resized = cv2.resize(
        image,
        (max(1, int(width * scale_factor)), max(1, int(height * scale_factor))),
        interpolation=cv2.INTER_AREA,
    )
    return resized, scale_factor


def normalize_text(text):
    return text.replace(" ", "").replace("己", "已")


def _write_temp_png(image):
    fd, path = tempfile.mkstemp(prefix="tjb_ocr_", suffix=".png")
    os.close(fd)
    if not cv2.imwrite(path, image):
        try:
            os.unlink(path)
        except OSError:
            pass
        raise RuntimeError("failed to write temporary OCR image")
    return path


def _apple_vision_results(image, mode="accurate", min_confidence=0.2):
    vision_bin = _apple_vision_bin()
    if vision_bin is None:
        raise RuntimeError("Apple Vision OCR binary not found")

    started = time.perf_counter()
    image_path = _write_temp_png(image)
    try:
        ocr_started = time.perf_counter()
        result = subprocess.run(
            [str(vision_bin), image_path, "--mode", mode],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=15,
            check=False,
        )
        ocr_elapsed = time.perf_counter() - ocr_started
    finally:
        try:
            os.unlink(image_path)
        except OSError:
            pass

    if not result.stdout.strip():
        raise RuntimeError(f"Apple Vision OCR produced no output: {result.stderr.strip()}")
    data = json.loads(result.stdout)
    if data.get("error"):
        raise RuntimeError(f"Apple Vision OCR failed: {data['error']}")

    items = []
    for item in data.get("items") or []:
        confidence = float(item.get("confidence") or 0.0)
        if confidence < min_confidence:
            continue
        bbox = item.get("bbox") or [0, 0, 0, 0]
        x, y, width, height = bbox
        items.append({
            "text": item.get("text", ""),
            "confidence": confidence,
            "bounds": (
                int(round(x)),
                int(round(y)),
                int(round(x + width)),
                int(round(y + height)),
            ),
        })
    timings = {
        "scale": 1.0,
        "ocr": ocr_elapsed,
        "total": time.perf_counter() - started,
        "backend": "apple_vision",
        "apple_vision_ms": data.get("elapsed_ms"),
    }
    return items, timings


def image_has_text(image, target_text, scale_factor=0.5, gpu=True, min_confidence=0.2):
    timings = {}
    started = time.perf_counter()

    if _use_apple_vision():
        items, timings = read_ocr_results(
            image,
            scale_factor=scale_factor,
            gpu=gpu,
            min_confidence=min_confidence,
        )
        compact_target = normalize_text(target_text)
        hits = [
            {"text": item["text"], "confidence": item["confidence"], "bbox": item["bounds"]}
            for item in items
            if compact_target in normalize_text(item["text"])
        ]
        return bool(hits), hits, timings

    resized, scale = resize_for_ocr(image, scale_factor=scale_factor)
    timings["scale"] = scale

    ocr_started = time.perf_counter()
    reader = get_reader(gpu=gpu)
    results = reader.readtext(resized, detail=1, paragraph=False)
    timings["ocr"] = time.perf_counter() - ocr_started
    timings["total"] = time.perf_counter() - started

    hits = []
    compact_target = normalize_text(target_text)
    for bbox, text, confidence in results:
        compact_text = normalize_text(text)
        if confidence >= min_confidence and compact_target in compact_text:
            hits.append({"text": text, "confidence": float(confidence), "bbox": bbox})
    return bool(hits), hits, timings


def read_ocr_results(image, scale_factor=0.5, gpu=True, min_confidence=0.2):
    timings = {}
    started = time.perf_counter()

    if _use_apple_vision():
        return _apple_vision_results(image, mode="accurate", min_confidence=min_confidence)

    resized, scale = resize_for_ocr(image, scale_factor=scale_factor)
    timings["scale"] = scale

    ocr_started = time.perf_counter()
    reader = get_reader(gpu=gpu)
    results = reader.readtext(resized, detail=1, paragraph=False)
    timings["ocr"] = time.perf_counter() - ocr_started
    timings["total"] = time.perf_counter() - started

    items = []
    for bbox, text, confidence in results:
        if confidence < min_confidence:
            continue
        xs = [point[0] for point in bbox]
        ys = [point[1] for point in bbox]
        bounds = (
            int(min(xs) / scale),
            int(min(ys) / scale),
            int(max(xs) / scale),
            int(max(ys) / scale),
        )
        items.append({"text": text, "confidence": float(confidence), "bounds": bounds})
    return items, timings


def screen_has_text(d, target_text, scale_factor=0.5, gpu=True, min_confidence=0.2):
    started = time.perf_counter()
    screenshot = d.screenshot(format="opencv")
    screenshot_time = time.perf_counter() - started
    ok, hits, timings = image_has_text(
        screenshot,
        target_text,
        scale_factor=scale_factor,
        gpu=gpu,
        min_confidence=min_confidence,
    )
    timings["screenshot"] = screenshot_time
    timings["total"] += screenshot_time
    return ok, hits, timings
