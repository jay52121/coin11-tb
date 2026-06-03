import argparse
import importlib
import shutil
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def ok(message):
    print(f"[OK] {message}")


def warn(message):
    print(f"[WARN] {message}")


def fail(message):
    print(f"[FAIL] {message}")


def run_command(args, timeout=10):
    try:
        result = subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
            check=False,
        )
        return result.returncode, result.stdout.strip()
    except Exception as exc:
        return 1, str(exc)


def check_python():
    version = sys.version_info
    ok(f"Python {version.major}.{version.minor}.{version.micro} at {sys.executable}")
    if version < (3, 10):
        warn("建议使用 Python 3.10 或 3.11；过低版本可能影响依赖安装。")


def check_imports():
    modules = [
        "uiautomator2",
        "cv2",
        "easyocr",
        "fastapi",
        "uvicorn",
        "torch",
    ]
    for name in modules:
        try:
            module = importlib.import_module(name)
            version = getattr(module, "__version__", "")
            ok(f"import {name} {version}".strip())
        except Exception as exc:
            fail(f"import {name} failed: {exc}")


def check_torch():
    try:
        import torch
    except Exception as exc:
        fail(f"torch unavailable: {exc}")
        return
    ok(f"torch {torch.__version__}")
    if hasattr(torch.backends, "mps"):
        ok(f"torch MPS available: {torch.backends.mps.is_available()}")
    ok(f"torch CUDA available: {torch.cuda.is_available()}")


def check_adb():
    adb = shutil.which("adb")
    if not adb:
        fail("adb not found in PATH")
        return None
    ok(f"adb: {adb}")
    code, output = run_command(["adb", "devices"], timeout=10)
    if code != 0:
        fail(f"adb devices failed: {output}")
        return None
    lines = [line for line in output.splitlines() if "\tdevice" in line]
    if not lines:
        warn("adb is installed, but no authorized Android device is connected.")
        print(output)
        return None
    serial = lines[0].split()[0]
    ok(f"adb device: {serial}")
    return serial


def check_uiautomator2(serial=None):
    try:
        import uiautomator2 as u2
    except Exception as exc:
        fail(f"uiautomator2 import failed: {exc}")
        return
    try:
        device = u2.connect(serial) if serial else u2.connect()
        ok(f"uiautomator2 connected: {device.serial}")
        started = time.perf_counter()
        xml = device.dump_hierarchy(compressed=True)
        elapsed = time.perf_counter() - started
        root = ET.fromstring(xml)
        nodes = sum(1 for _ in root.iter("node"))
        ok(f"dump_hierarchy compressed=True: {elapsed:.3f}s, {len(xml)} chars, {nodes} nodes")
    except Exception as exc:
        fail(f"uiautomator2 check failed: {exc}")


def check_gui_port(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(0.5)
    try:
        result = sock.connect_ex(("127.0.0.1", port))
    finally:
        sock.close()
    if result == 0:
        warn(f"127.0.0.1:{port} is already in use.")
    else:
        ok(f"127.0.0.1:{port} is free.")


def check_ocr(skip_ocr):
    if skip_ocr:
        warn("OCR initialization skipped.")
        return
    try:
        import easyocr
        started = time.perf_counter()
        reader = easyocr.Reader(["ch_sim"], gpu=False, verbose=False)
        elapsed = time.perf_counter() - started
        ok(f"EasyOCR CPU reader initialized in {elapsed:.2f}s")
        del reader
    except Exception as exc:
        fail(f"EasyOCR initialization failed: {exc}")


def main():
    parser = argparse.ArgumentParser(description="Check local environment for coin11-tb.")
    parser.add_argument("--skip-ocr", action="store_true", help="skip slow EasyOCR initialization")
    parser.add_argument("--port", type=int, default=8765, help="GUI port to check")
    args = parser.parse_args()

    check_python()
    check_imports()
    check_torch()
    serial = check_adb()
    check_uiautomator2(serial)
    check_gui_port(args.port)
    check_ocr(args.skip_ocr)


if __name__ == "__main__":
    main()
