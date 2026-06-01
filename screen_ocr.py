import time

import cv2


_reader = None


def get_reader(gpu=True):
    global _reader
    if _reader is None:
        import easyocr
        _reader = easyocr.Reader(["ch_sim"], gpu=gpu, verbose=False)
    return _reader


def resize_for_ocr(image, max_width=640):
    height, width = image.shape[:2]
    if width <= max_width:
        return image, 1.0
    scale = max_width / width
    resized = cv2.resize(image, (max_width, int(height * scale)), interpolation=cv2.INTER_AREA)
    return resized, scale


def normalize_text(text):
    return text.replace(" ", "").replace("己", "已")


def image_has_text(image, target_text, max_width=640, gpu=True, min_confidence=0.2):
    timings = {}
    started = time.perf_counter()

    resized, scale = resize_for_ocr(image, max_width=max_width)
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


def read_ocr_results(image, max_width=640, gpu=True, min_confidence=0.2):
    timings = {}
    started = time.perf_counter()

    resized, scale = resize_for_ocr(image, max_width=max_width)
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


def screen_has_text(d, target_text, max_width=640, gpu=True, min_confidence=0.2):
    started = time.perf_counter()
    screenshot = d.screenshot(format="opencv")
    screenshot_time = time.perf_counter() - started
    ok, hits, timings = image_has_text(
        screenshot,
        target_text,
        max_width=max_width,
        gpu=gpu,
        min_confidence=min_confidence,
    )
    timings["screenshot"] = screenshot_time
    timings["total"] += screenshot_time
    return ok, hits, timings
