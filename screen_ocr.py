import time

import cv2


_reader = None


def get_reader(gpu=True):
    global _reader
    if _reader is None:
        import easyocr
        _reader = easyocr.Reader(["ch_sim"], gpu=gpu, verbose=False)
    return _reader


def resize_for_ocr(image, max_width=900):
    height, width = image.shape[:2]
    if width <= max_width:
        return image, 1.0
    scale = max_width / width
    resized = cv2.resize(image, (max_width, int(height * scale)), interpolation=cv2.INTER_AREA)
    return resized, scale


def normalize_text(text):
    return text.replace(" ", "").replace("己", "已")


def screen_has_text(d, target_text, max_width=900, gpu=True, min_confidence=0.2):
    timings = {}
    started = time.perf_counter()
    screenshot = d.screenshot(format="opencv")
    timings["screenshot"] = time.perf_counter() - started

    resized, scale = resize_for_ocr(screenshot, max_width=max_width)
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
