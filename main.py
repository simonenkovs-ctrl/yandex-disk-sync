import ctypes
import os
import sys

# 1. Enable Windows High-DPI Awareness BEFORE creating Tk instance
if sys.platform == "win32":
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            try:
                ctypes.windll.user32.SetProcessDPIAware()
            except Exception:
                pass

# 2. Auto-detach from terminal so closing console never kills the application
if sys.platform == "win32" and "--foreground" not in sys.argv and not getattr(sys, "frozen", False):
    exe_lower = sys.executable.lower()
    if exe_lower.endswith("python.exe"):
        pyw = sys.executable[:-4] + "w.exe"
        if os.path.exists(pyw):
            import subprocess
            flags = 0x00000008 | 0x08000000  # DETACHED_PROCESS | CREATE_NO_WINDOW
            filtered_args = [a for a in sys.argv[1:] if a != "--foreground"]
            cmd = [pyw, os.path.abspath(__file__), "--foreground"] + filtered_args
            subprocess.Popen(cmd, creationflags=flags, close_fds=True)
            print("[OK] Яндекс Диск Синхронизатор запущен в фоновом режиме.")
            print("Значок программы активен в системном трее (около часов).")
            print("Этот терминал можно безопасно закрыть.")
            sys.exit(0)

# Add current folder to sys.path
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from tkinter import Tk
from gui import YandexDiskSyncApp


def main():
    root = Tk()
    app = YandexDiskSyncApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
