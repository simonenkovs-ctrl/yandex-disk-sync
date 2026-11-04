# patch.py - YandexDiskSync v1.2.0 patcher (D1+D2+D3), ASCII only
# Usage:
#   python patch.py            # dry-run
#   python patch.py --write    # apply
import sys, py_compile
from pathlib import Path

W = "--write" in sys.argv

def rep(text, old, new, label):
    if old not in text:
        print(f"FAIL {label}")
        sys.exit(1)
    print(f"OK   {label}")
    return text.replace(old, new, 1)

def rep_by_markers(text, start, end, new_block, label):
    si = text.find(start)
    if si < 0:
        print(f"FAIL {label} (start marker not found)")
        sys.exit(1)
    ei = text.find(end, si)
    if ei < 0:
        print(f"FAIL {label} (end marker not found)")
        sys.exit(1)
    ei += len(end)
    print(f"OK   {label}")
    return text[:si] + new_block + text[ei:]

conf = Path("config.py").read_text("utf-8")
sync = Path("sync_engine.py").read_text("utf-8")

# 1. config.py - TTL
conf = rep(conf, '"autostart": False,',
           '"autostart": False,\n    "manifest_ttl_seconds": 3600,',
           "config.py: manifest_ttl_seconds")

# 2. IGNORED_EXTENSIONS
sync = rep(sync,
    '".tmp", ".crdownload", ".part", ".swp", ".lock"',
    '".tmp", ".temp", ".crdownload", ".part", ".partial", ".download",\n    ".swp", ".swo", ".lock",\n    ".bak", ".backup", ".old",',
    "sync_engine.py: IGNORED_EXTENSIONS")

# 3a. local filter
sync = rep(sync,
    'local_files = {k: v for k, v in local_files.items() if not k.lower().startswith(".sync_manifest")}',
    'local_files = {k: v for k, v in local_files.items() if not self._is_ignored(k)}',
    "sync_engine.py: local filter")

# 3b. remote filter
sync = rep(sync,
    'remote_files = {k: v for k, v in remote_files.items() if not k.lower().startswith(".sync_manifest")}',
    'remote_files = {k: v for k, v in remote_files.items() if not self._is_ignored(k)}',
    "sync_engine.py: remote filter")

# 4. manifest load with TTL
old4 = '''                with open(tmp, "r", encoding="utf-8") as f:
                    data = json.load(f)
                remote_files = data.get("files", {})'''
new4 = '''                with open(tmp, "r", encoding="utf-8") as f:
                    data = json.load(f)
                _ver = data.get("version", 1)
                _ts = data.get("timestamp", 0)
                _ttl = int(self.config.get("manifest_ttl_seconds", 3600))
                _age = time.time() - _ts
                if _ver >= 2 and _age > _ttl:
                    self.log("INFO", f"Manifest stale ({_age/60:.0f} min > TTL {_ttl/60:.0f} min), rebuilding")
                    remote_files = None
                else:
                    remote_files = data.get("files", {})'''
sync = rep(sync, old4, new4, "sync_engine.py: manifest TTL")

# 5. manifest save v2
sync = rep(sync,
    '                        json.dump({"files": remote_files}, f, ensure_ascii=False)',
    '                        json.dump({"version": 2, "timestamp": time.time(), "files": remote_files}, f, ensure_ascii=False)',
    "sync_engine.py: manifest save v2")

# 6. init counters
old6 = '''            stats = {"uploaded": 0, "downloaded": 0, "deleted": 0, "conflicts": 0}
            skipped_files: list = []  # files that failed to sync'''
new6 = '''            stats = {"uploaded": 0, "downloaded": 0, "deleted": 0, "conflicts": 0}
            self._skipped_files: list = []
            self._stale_remote_entries: set = set()'''
sync = rep(sync, old6, new6, "sync_engine.py: init counters")

# 7. _do_download 404 (position-based, no Cyrillic dependency)
new7 = '''            _status = getattr(e, "status_code", None) or getattr(e, "code", None)
            if _status == 404:
                self.log("WARN", f"File 404, excluding from manifest: {rel_path}")
                self._stale_remote_entries.add(rel_path)
            else:
                self.log("WARN", f"Skip file {rel_path}: {e}")
            self._skipped_files.append(rel_path)
            return'''
sync = rep_by_markers(sync,
    '            if "404" in str(e):',
    '            skipped_files.append(rel_path)\n            return',
    new7, "sync_engine.py: _do_download 404")

# 8. report block (position-based)
new8 = '''            # Point-update manifest: drop 404 entries without full rebuild
            if self._stale_remote_entries and remote_files is not None:
                for _p in self._stale_remote_entries:
                    remote_files.pop(_p, None)
                try:
                    tmp = os.path.join(tempfile.gettempdir(), "sync_manifest.json")
                    with open(tmp, "w", encoding="utf-8") as f:
                        json.dump({"version": 2, "timestamp": time.time(), "files": remote_files}, f, ensure_ascii=False)
                    self.api.upload_file(tmp, manifest_path, overwrite=True)
                    self.log("INFO", f"Manifest updated: removed {len(self._stale_remote_entries)} stale entries (404)")
                except Exception as e:
                    self.log("WARN", f"Failed to update manifest after 404: {e}")
                self._stale_remote_entries.clear()

            if self._skipped_files:
                self.log("WARN", f"Skipped files: {len(self._skipped_files)}")
                for _sf in self._skipped_files:
                    self.log("WARN", f"  - {_sf}")

'''
sync = rep_by_markers(sync,
    '            # Report files that could not be synced',
    '            if self.on_sync_completed:',
    new8, "sync_engine.py: report")

# Syntax check
for name, content in (("config.py", conf), ("sync_engine.py", sync)):
    tmp = Path(name + ".chk")
    tmp.write_text(content, "utf-8")
    try:
        py_compile.compile(str(tmp), doraise=True)
        print(f"OK   syntax {name}")
    except py_compile.PyCompileError as e:
        print(f"FAIL syntax {name}: {e}")
        tmp.unlink(missing_ok=True)
        sys.exit(1)
    tmp.unlink(missing_ok=True)

if W:
    Path("config.py").write_text(conf, "utf-8")
    Path("sync_engine.py").write_text(sync, "utf-8")
    print("\n[WRITE] applied 8 edits")
else:
    print("\n[DRY-RUN] OK, re-run with --write to apply")