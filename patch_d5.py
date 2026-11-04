import sys, py_compile
from pathlib import Path
W = "--write" in sys.argv
gui = Path("gui.py").read_text("utf-8")

def rep(text, old, new, label):
    if old not in text:
        print("FAIL " + label); sys.exit(1)
    print("OK   " + label); return text.replace(old, new, 1)

gui = rep(gui,
    '        self.var_autostart = BooleanVar(value=self.config.get("autostart", False))',
    '        self.var_autostart = BooleanVar(value=self._read_autostart_state())',
    "D5.1: init reads from registry")

OLD = r'''    def _apply_autostart(self, enabled):
        if winreg is None:
            return
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE)
        except Exception:
            key = winreg.CreateKey(winreg.HKEY_CURRENT_USER, key_path)
        try:
            if enabled:
                exe = sys.executable if getattr(sys, "frozen", False) else os.path.abspath(sys.argv[0])
                winreg.SetValueEx(key, "YandexDiskSync", 0, winreg.REG_SZ, f'"{exe}"')
            else:
                try:
                    winreg.DeleteValue(key, "YandexDiskSync")
                except FileNotFoundError:
                    pass
        finally:
            winreg.CloseKey(key)'''

NEW = r'''    def _read_autostart_state(self) -> bool:
        if winreg is None:
            return False
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_READ)
            try:
                winreg.QueryValueEx(key, "YandexDiskSync")
                return True
            finally:
                winreg.CloseKey(key)
        except FileNotFoundError:
            return False
        except Exception:
            return False

    def _apply_autostart(self, enabled):
        if winreg is None:
            return
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE)
        except Exception:
            key = winreg.CreateKey(winreg.HKEY_CURRENT_USER, key_path)
        try:
            if enabled:
                if getattr(sys, "frozen", False):
                    cmd = f'"{sys.executable}"'
                else:
                    _py = sys.executable
                    _pyw = _py[:-4] + "w.exe" if _py.lower().endswith("python.exe") else _py
                    _main = os.path.abspath(os.path.join(os.path.dirname(__file__), "main.py"))
                    cmd = f'"{_pyw}" "{_main}"'
                winreg.SetValueEx(key, "YandexDiskSync", 0, winreg.REG_SZ, cmd)
            else:
                try:
                    winreg.DeleteValue(key, "YandexDiskSync")
                except FileNotFoundError:
                    pass
        finally:
            winreg.CloseKey(key)'''

gui = rep(gui, OLD, NEW, "D5.2: _read_autostart_state + dev command")

tmp = Path("gui.py.chk"); tmp.write_text(gui, "utf-8")
try:
    py_compile.compile(str(tmp), doraise=True)
    print("OK   syntax gui.py")
except py_compile.PyCompileError as e:
    print("FAIL syntax: " + str(e)); tmp.unlink(missing_ok=True); sys.exit(1)
tmp.unlink(missing_ok=True)

if W:
    Path("gui.py").write_text(gui, "utf-8")
    print("\n[WRITE] applied D5")
else:
    print("\n[DRY-RUN] OK, re-run with --write to apply")
