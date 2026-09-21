import hashlib
import json
import os
import queue
import re
import shutil
import subprocess
import tempfile
import threading
from datetime import datetime
from pathlib import Path
import xml.etree.ElementTree as ET


def _bool_attr(value):
    return str(value).lower() == "true"


def _bounds_attr(value):
    numbers = [int(item) for item in re.findall(r"-?\d+", value or "")]
    return numbers[:4] if len(numbers) >= 4 else [0, 0, 0, 0]


def normalize_nodes(root):
    nodes = []
    for node in root.iter("node"):
        attrs = node.attrib
        nodes.append({
            "text": attrs.get("text") or None,
            "contentDescription": attrs.get("content-desc") or None,
            "viewId": attrs.get("resource-id") or None,
            "className": attrs.get("class") or None,
            "bounds": _bounds_attr(attrs.get("bounds")),
            "clickable": _bool_attr(attrs.get("clickable")),
            "scrollable": _bool_attr(attrs.get("scrollable")),
            "enabled": _bool_attr(attrs.get("enabled")),
        })
    return nodes


class GoldenSnapshotRecorder:
    def __init__(self, base_dir, runtime_version, android_user, rules_provider, logger=print):
        self.enabled = os.environ.get("TJB_GOLDEN_SNAPSHOT", "0").strip() == "1"
        self.output_dir = Path(base_dir) / "runtime" / "golden-captures"
        self.runtime_version = runtime_version
        self.android_user = android_user
        self.rules_provider = rules_provider
        self.logger = logger
        self._seen = set()
        self._queue = queue.Queue(maxsize=64)
        self._worker = None
        if self.enabled:
            self.output_dir.mkdir(parents=True, exist_ok=True)
            self._worker = threading.Thread(target=self._run, name="golden-snapshot-writer", daemon=True)
            self._worker.start()

    def capture(self, root, package, activity, page_type, page_signature, reason="classification"):
        if not self.enabled or root is None:
            return
        item = {
            "root": root,
            "package": package,
            "activity": activity,
            "pageType": page_type,
            "pageSignature": page_signature,
            "reason": reason,
            "capturedAt": datetime.now().astimezone().isoformat(timespec="milliseconds"),
        }
        try:
            self._queue.put_nowait(item)
        except queue.Full:
            self.logger("Golden Snapshot队列已满，本次采集已跳过")

    def flush(self):
        if self.enabled:
            self._queue.join()

    def _run(self):
        while True:
            item = self._queue.get()
            try:
                self._write(item)
            except Exception as exc:
                self.logger("Golden Snapshot写入失败", exc)
            finally:
                self._queue.task_done()

    def _write(self, item):
        nodes = normalize_nodes(item["root"])
        signature_source = {
            "package": item["package"],
            "activity": item["activity"],
            "pageType": item["pageType"],
            "nodes": nodes,
        }
        signature_json = json.dumps(signature_source, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        digest = hashlib.sha256(signature_json.encode("utf-8")).hexdigest()
        if digest in self._seen:
            return
        self._seen.add(digest)

        target = self.output_dir / f"{item['pageType']}-{digest[:16]}"
        if target.exists():
            return

        rules = self.rules_provider() or {}
        rules_json = json.dumps(rules, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        metadata = {
            "schemaVersion": 1,
            "capturedAt": item["capturedAt"],
            "gitCommit": self._git_commit(),
            "runtimeVersion": self.runtime_version,
            "androidUser": int(self.android_user) if str(self.android_user).isdigit() else self.android_user,
            "package": item["package"],
            "activity": item["activity"],
            "legacyPageType": item["pageType"],
            "pageSignature": item["pageSignature"],
            "normalizedSignatureSha256": digest,
            "rulesVersion": rules.get("app_version"),
            "rulesSha256": hashlib.sha256(rules_json.encode("utf-8")).hexdigest(),
            "ocrRequested": False,
            "reason": item["reason"],
            "nodes": nodes,
        }

        temp_dir = Path(tempfile.mkdtemp(prefix=".golden-", dir=self.output_dir))
        try:
            (temp_dir / "metadata.json").write_text(
                json.dumps(metadata, ensure_ascii=False, indent=2, default=list) + "\n",
                encoding="utf-8",
            )
            ET.ElementTree(item["root"]).write(temp_dir / "page.xml", encoding="utf-8", xml_declaration=True)
            os.replace(temp_dir, target)
        finally:
            if temp_dir.exists():
                shutil.rmtree(temp_dir, ignore_errors=True)

    def _git_commit(self):
        try:
            return subprocess.check_output(
                ["git", "rev-parse", "HEAD"],
                cwd=self.output_dir.parent.parent,
                text=True,
                stderr=subprocess.DEVNULL,
            ).strip()
        except Exception:
            return None
