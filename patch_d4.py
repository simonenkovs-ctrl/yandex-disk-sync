# patch_d4.py - (A) fix D3 regression + (B) D4 retry changes
import re
import sys
import py_compile
from pathlib import Path

W = "--write" in sys.argv


def rep(text, old, new, label):
    if old not in text:
        print(f"FAIL {label}")
        sys.exit(1)
    print(f"OK   {label}")
    return text.replace(old, new, 1)


# ===== A: sync_engine.py - restore 'if self.on_sync_completed:' guard =====
sync = Path("sync_engine.py").read_text("utf-8")

buggy_re = re.compile(
    r'(                    self\.log\("WARN", f"  - \{_sf\}"\)\n+)'
    r'(                self\.on_sync_completed\(stats\))'
)
FIXED = (
    '                    self.log("WARN", f"  - {_sf}")\n'
    '\n'
    '            if self.on_sync_completed:\n'
    '                self.on_sync_completed(stats)'
)

new_sync, n = buggy_re.subn(FIXED, sync)
if n > 0:
    sync = new_sync
    print(f"OK   A: fixed on_sync_completed guard ({n} occurrence)")
elif '            if self.on_sync_completed:\n                self.on_sync_completed(stats)' in sync:
    print("SKIP A: guard already present")
else:
    print("FAIL A: buggy pattern not found")
    sys.exit(1)


# ===== B: yandex_api.py - D4 =====
api = Path("yandex_api.py").read_text("utf-8")

start = "        # Helper for retrying a single request\n"
end = "        clean_root = self.normalize_remote_path(remote_root)\n"
si = api.find(start)
if si < 0:
    print("FAIL B1: helper start not found")
    sys.exit(1)
ei = api.find(end, si)
if ei < 0:
    print("FAIL B1: helper end not found")
    sys.exit(1)
removed_lines = api[si:ei].count("\n")
if not (5 < removed_lines < 40):
    print(f"FAIL B1: unexpected block size {removed_lines} lines, aborting")
    sys.exit(1)
api = api[:si] + api[ei:]
print(f"OK   B1: removed helper ({removed_lines} lines, clean_root kept)")

api = rep(
    api,
    '                # Perform the request with retry logic\n                data = _request_with_retry(lambda: self._request("GET", url))',
    '                data = self._request("GET", url)',
    "B2: direct _request call",
)

B3_OLD = (
    "                # Handle rate limiting (429) or transient 5xx\n"
    "                if e.code in (429, 500, 502, 503, 504) and attempt < retries - 1:\n"
    "                    time.sleep(backoff)\n"
    "                    backoff *= 2\n"
    "                    continue"
)
B3_NEW = (
    "                # Handle 429/5xx with Retry-After support\n"
    "                if e.code in (429, 500, 502, 503, 504) and attempt < retries - 1:\n"
    "                    _delay = backoff\n"
    "                    try:\n"
    "                        _ra = e.headers.get(\"Retry-After\") if e.headers else None\n"
    "                    except Exception:\n"
    "                        _ra = None\n"
    "                    if _ra:\n"
    "                        try:\n"
    "                            _delay = min(float(_ra), 30.0)\n"
    "                        except (ValueError, TypeError):\n"
    "                            pass\n"
    "                    time.sleep(_delay)\n"
    "                    backoff = min(backoff * 2, 30.0)\n"
    "                    continue"
)
api = rep(api, B3_OLD, B3_NEW, "B3: Retry-After support")


for name, content in (("sync_engine.py", sync), ("yandex_api.py", api)):
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
    Path("sync_engine.py").write_text(sync, "utf-8")
    Path("yandex_api.py").write_text(api, "utf-8")
    print("\n[WRITE] applied A + D4")
else:
    print("\n[DRY-RUN] OK, re-run with --write to apply")
