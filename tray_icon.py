import ctypes
from ctypes import wintypes
import os
import sys
import threading
from typing import Callable, Optional

# Win32 Constants
WM_USER = 0x0400
WM_TRAYICON = WM_USER + 20
NIM_ADD = 0x00000000
NIM_MODIFY = 0x00000001
NIM_DELETE = 0x00000002

NIF_MESSAGE = 0x00000001
NIF_ICON = 0x00000002
NIF_TIP = 0x00000004

WM_LBUTTONUP = 0x0202
WM_LBUTTONDBLCLK = 0x0203
WM_RBUTTONUP = 0x0205

MF_STRING = 0x00000000
MF_SEPARATOR = 0x00000800
TPM_RIGHTBUTTON = 0x0002
TPM_RETURNCMD = 0x0100

ID_OPEN = 1001
ID_SYNC_NOW = 1002
ID_PAUSE_RESUME = 1003
ID_EXIT = 1004

# 64-bit safe types
LRESULT = ctypes.c_longlong

# Structures
class GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", wintypes.DWORD),
        ("Data2", wintypes.WORD),
        ("Data3", wintypes.WORD),
        ("Data4", wintypes.BYTE * 8),
    ]

class NOTIFYICONDATAW(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("hWnd", wintypes.HWND),
        ("uID", wintypes.UINT),
        ("uFlags", wintypes.UINT),
        ("uCallbackMessage", wintypes.UINT),
        ("hIcon", wintypes.HICON),
        ("szTip", wintypes.WCHAR * 128),
        ("dwState", wintypes.DWORD),
        ("dwStateMask", wintypes.DWORD),
        ("szInfo", wintypes.WCHAR * 256),
        ("uTimeoutOrVersion", wintypes.UINT),
        ("szInfoTitle", wintypes.WCHAR * 64),
        ("dwInfoFlags", wintypes.DWORD),
        ("guidItem", GUID),
        ("hBalloonIcon", wintypes.HICON),
    ]

WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)

class WNDCLASSW(ctypes.Structure):
    _fields_ = [
        ("style", wintypes.UINT),
        ("lpfnWndProc", WNDPROC),
        ("cbClsExtra", ctypes.c_int),
        ("cbWndExtra", ctypes.c_int),
        ("hInstance", wintypes.HINSTANCE),
        ("hIcon", wintypes.HICON),
        ("hCursor", wintypes.HANDLE),
        ("hbrBackground", wintypes.HBRUSH),
        ("lpszMenuName", wintypes.LPCWSTR),
        ("lpszClassName", wintypes.LPCWSTR),
    ]

user32 = ctypes.windll.user32
shell32 = ctypes.windll.shell32
kernel32 = ctypes.windll.kernel32

# Configure 64-bit function prototypes
user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.DefWindowProcW.restype = LRESULT
user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.PostMessageW.restype = wintypes.BOOL


class WindowsTrayIcon:
    def __init__(self, tooltip: str = "Яндекс Диск Синхронизация",
                 on_open: Optional[Callable[[], None]] = None,
                 on_sync: Optional[Callable[[], None]] = None,
                 on_toggle_pause: Optional[Callable[[], None]] = None,
                 on_exit: Optional[Callable[[], None]] = None,
                 get_is_paused: Optional[Callable[[], bool]] = None):
        self.tooltip = tooltip
        self.on_open = on_open
        self.on_sync = on_sync
        self.on_toggle_pause = on_toggle_pause
        self.on_exit = on_exit
        self.get_is_paused = get_is_paused

        self.hwnd = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._nid = None
        self._wndproc = WNDPROC(self._window_proc)

    def start(self):
        if sys.platform != "win32":
            return
        self._running = True
        self._thread = threading.Thread(target=self._run_tray_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self.hwnd:
            if self._nid:
                shell32.Shell_NotifyIconW(NIM_DELETE, ctypes.byref(self._nid))
            user32.PostMessageW(self.hwnd, 0x0010, 0, 0)  # WM_CLOSE

    def update_tooltip(self, text: str):
        self.tooltip = text
        if self.hwnd and self._nid:
            self._nid.szTip = text[:127]
            self._nid.uFlags = NIF_TIP
            shell32.Shell_NotifyIconW(NIM_MODIFY, ctypes.byref(self._nid))

    def _run_tray_loop(self):
        try:
            class_name = f"YandexDiskSyncTray_{os.getpid()}"
            hinstance = kernel32.GetModuleHandleW(None)

            wnd_class = WNDCLASSW()
            wnd_class.hInstance = hinstance
            wnd_class.lpszClassName = class_name
            wnd_class.lpfnWndProc = self._wndproc
            user32.RegisterClassW(ctypes.byref(wnd_class))

            self.hwnd = user32.CreateWindowExW(
                0, class_name, "YandexDiskTrayHelper",
                0, 0, 0, 0, 0,
                None, None, hinstance, None
            )

            IDI_APPLICATION = 32512
            IMAGE_ICON = 1
            LR_LOADFROMFILE = 0x00000010
            LR_DEFAULTSIZE = 0x00000040

            if getattr(sys, "frozen", False):
                base_dir = getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
            else:
                base_dir = os.path.dirname(os.path.abspath(__file__))
            icon_file = os.path.join(base_dir, "app.ico")
            h_icon = None

            if os.path.exists(icon_file):
                try:
                    h_icon = user32.LoadImageW(
                        None, icon_file, IMAGE_ICON, 0, 0,
                        LR_LOADFROMFILE | LR_DEFAULTSIZE
                    )
                except Exception:
                    h_icon = None

            if not h_icon:
                h_icon = user32.LoadIconW(0, IDI_APPLICATION)

            nid = NOTIFYICONDATAW()
            nid.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
            nid.hWnd = self.hwnd
            nid.uID = 1
            nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP
            nid.uCallbackMessage = WM_TRAYICON
            nid.hIcon = h_icon
            nid.szTip = self.tooltip[:127]
            self._nid = nid

            shell32.Shell_NotifyIconW(NIM_ADD, ctypes.byref(nid))

            msg = wintypes.MSG()
            while self._running and user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
                user32.TranslateMessage(ctypes.byref(msg))
                user32.DispatchMessageW(ctypes.byref(msg))
        except Exception as e:
            print(f"Tray loop error: {e}")

    def _show_popup_menu(self):
        try:
            pt = wintypes.POINT()
            user32.GetCursorPos(ctypes.byref(pt))

            h_menu = user32.CreatePopupMenu()
            user32.AppendMenuW(h_menu, MF_STRING, ID_OPEN, "Открыть окно синхронизации")
            user32.AppendMenuW(h_menu, MF_STRING, ID_SYNC_NOW, "Синхронизировать сейчас")

            is_paused = self.get_is_paused() if self.get_is_paused else False
            pause_label = "Возобновить синхронизацию" if is_paused else "Приостановить синхронизацию"
            user32.AppendMenuW(h_menu, MF_STRING, ID_PAUSE_RESUME, pause_label)

            user32.AppendMenuW(h_menu, MF_SEPARATOR, 0, None)
            user32.AppendMenuW(h_menu, MF_STRING, ID_EXIT, "Выход из программы")

            user32.SetForegroundWindow(self.hwnd)
            cmd = user32.TrackPopupMenuEx(
                h_menu, TPM_RIGHTBUTTON | TPM_RETURNCMD, pt.x, pt.y, self.hwnd, None
            )
            user32.DestroyMenu(h_menu)

            if cmd == ID_OPEN and self.on_open:
                self.on_open()
            elif cmd == ID_SYNC_NOW and self.on_sync:
                self.on_sync()
            elif cmd == ID_PAUSE_RESUME and self.on_toggle_pause:
                self.on_toggle_pause()
            elif cmd == ID_EXIT and self.on_exit:
                self.on_exit()
        except Exception as e:
            print(f"Tray popup error: {e}")

    def _window_proc(self, hwnd, msg, wparam, lparam):
        try:
            if msg == WM_TRAYICON:
                if lparam in (WM_LBUTTONUP, WM_LBUTTONDBLCLK):
                    if self.on_open:
                        self.on_open()
                    return 0
                elif lparam == WM_RBUTTONUP:
                    self._show_popup_menu()
                    return 0
            return user32.DefWindowProcW(hwnd, msg, wparam, lparam)
        except Exception:
            return 0
