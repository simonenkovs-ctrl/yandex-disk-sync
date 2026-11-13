import ctypes
import os
import sys
try:
    import winreg
except Exception:
    winreg=None
import threading
import time
import webbrowser
from datetime import datetime
from tkinter import (
    Tk, ttk, StringVar, BooleanVar, IntVar, Text, Scrollbar, Menu, font,
    filedialog, messagebox, END
)

# Ensure DPI Awareness is active
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

from config import ConfigManager
from database import SyncDatabase
from sync_engine import SyncEngine
from tray_icon import WindowsTrayIcon


def format_bytes(bytes_num: int) -> str:
    if bytes_num < 1024:
        return f"{bytes_num} Б"
    elif bytes_num < 1024 * 1024:
        return f"{bytes_num / 1024:.1f} КБ"
    elif bytes_num < 1024 * 1024 * 1024:
        return f"{bytes_num / (1024 * 1024):.1f} МБ"
    else:
        return f"{bytes_num / (1024 * 1024 * 1024):.2f} ГБ"


class YandexDiskSyncApp:
    def __init__(self, root: Tk):
        self.root = root
        self.root.title("Яндекс Диск — Двусторонняя синхронизация")
        self.root.geometry("880x660")
        self.root.minsize(780, 560)

        # Set application icon if available
        if getattr(sys, "frozen", False):
            base_dir = getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
        else:
            base_dir = os.path.dirname(os.path.abspath(__file__))
        icon_path = os.path.join(base_dir, "app.ico")
        if os.path.exists(icon_path):
            try:
                self.root.iconbitmap(icon_path)
            except Exception:
                pass

        # Config & DB & Engine
        self.config = ConfigManager()
        self.db = SyncDatabase()
        self.engine = SyncEngine(self.config, self.db)

        # Setup engine callbacks
        self.engine.on_log = self._handle_log
        self.engine.on_status_change = self._handle_status
        self.engine.on_progress = self._handle_progress
        self.engine.on_sync_completed = self._handle_sync_completed

        # UI Variables
        self.var_token = StringVar(value=self.config.get("oauth_token", ""))
        self.var_local_folder = StringVar(value=self.config.get("local_folder", ""))
        self.var_remote_folder = StringVar(value=self.config.get("remote_folder", "/SyncFolder"))
        self.var_interval = IntVar(value=self.config.get("sync_interval_seconds", 30))
        self.var_conflict = StringVar(value=self.config.get("conflict_resolution", "keep_both"))
        self.var_sync_deletions = BooleanVar(value=self.config.get("sync_deletions", True))
        self.var_minimize_tray = BooleanVar(value=self.config.get("minimize_to_tray", True))
        self.var_autostart = BooleanVar(value=self._read_autostart_state())

        self.var_status_badge = StringVar(value="Подключение к Яндекс Диску...")
        self.var_storage_text = StringVar(value="Объем диска: проверка...")
        self.var_current_op = StringVar(value="Готов к работе")

        self.tray = None
        self._build_style()
        self._build_ui()
        self._setup_tray()
        self._setup_clipboard_shortcuts()

        # Window close event
        self.root.protocol("WM_DELETE_WINDOW", self.on_close_window)

        # Initial check
        self.root.after(400, self.check_connection_async)

        # Auto start engine
        if self.config.get("auto_start_sync", True):
            self.engine.start()

    def _build_style(self):
        # Configure crisp Windows TrueType fonts across the entire application
        default_font = font.nametofont("TkDefaultFont")
        default_font.configure(family="Segoe UI", size=10)

        text_font = font.nametofont("TkTextFont")
        text_font.configure(family="Segoe UI", size=10)

        menu_font = font.nametofont("TkMenuFont")
        menu_font.configure(family="Segoe UI", size=10)

        style = ttk.Style(self.root)
        available_themes = style.theme_names()
        if "vista" in available_themes:
            style.theme_use("vista")
        elif "clam" in available_themes:
            style.theme_use("clam")

        # Custom widget styling
        style.configure("Header.TFrame", background="#f8fafc", relief="solid", borderwidth=1)
        style.configure("HeaderInner.TFrame", background="#f8fafc")
        
        style.configure("HeaderTitle.TLabel", font=("Segoe UI", 13, "bold"), background="#f8fafc", foreground="#0f172a")
        style.configure("HeaderSub.TLabel", font=("Segoe UI", 10), background="#f8fafc", foreground="#475569")
        style.configure("HeaderStatus.TLabel", font=("Segoe UI", 10, "bold"), background="#f8fafc")

        style.configure("TNotebook.Tab", font=("Segoe UI", 10), padding=(14, 6))
        style.configure("TButton", font=("Segoe UI", 10), padding=(8, 4))
        style.configure("Primary.TButton", font=("Segoe UI", 10, "bold"), padding=(10, 5))
        style.configure("TLabelframe.Label", font=("Segoe UI", 10, "bold"), foreground="#1e293b")
        style.configure("TLabel", font=("Segoe UI", 10))

    def _build_ui(self):
        # Настройка grid для корневого окна: статус бар всегда внизу и виден
        self.root.grid_rowconfigure(0, weight=0)  # header
        self.root.grid_rowconfigure(1, weight=1)  # notebook — забирает свободное место
        self.root.grid_rowconfigure(2, weight=0)  # statusbar — всегда виден
        self.root.grid_columnconfigure(0, weight=1)

        # 1. Header Frame (Top Card)
        header_outer = ttk.Frame(self.root, padding=(14, 10))
        header_outer.grid(row=0, column=0, sticky="ew")

        header = ttk.Frame(header_outer, padding=(16, 12), style="Header.TFrame")
        header.pack(fill="x")

        left_header = ttk.Frame(header, style="HeaderInner.TFrame")
        left_header.pack(side="left", fill="y")

        ttk.Label(left_header, text="☁ Яндекс Диск Синхронизатор", style="HeaderTitle.TLabel").pack(anchor="w")
        self.lbl_status = ttk.Label(left_header, textvariable=self.var_status_badge, foreground="#475569", style="HeaderStatus.TLabel")
        self.lbl_status.pack(anchor="w", pady=(3, 0))

        right_header = ttk.Frame(header, style="HeaderInner.TFrame")
        right_header.pack(side="right", fill="y", padx=5)

        ttk.Label(right_header, textvariable=self.var_storage_text, style="HeaderSub.TLabel").pack(anchor="e")
        self.disk_progress = ttk.Progressbar(right_header, orient="horizontal", length=240, mode="determinate")
        self.disk_progress.pack(anchor="e", pady=(5, 0))

        # 2. Main Notebook (Tabs)
        notebook = ttk.Notebook(self.root)
        notebook.grid(row=1, column=0, sticky="nsew", padx=14, pady=(4, 10))

        tab_main = ttk.Frame(notebook, padding=12)
        tab_settings = ttk.Frame(notebook, padding=12)
        tab_help = ttk.Frame(notebook, padding=12)

        notebook.add(tab_main, text="   Синхронизация   ")
        notebook.add(tab_settings, text="   Настройки   ")
        notebook.add(tab_help, text="   Инструкция   ")

        self._build_tab_main(tab_main)
        self._build_tab_settings(tab_settings)
        self._build_tab_help(tab_help)

        # 3. Bottom Status Bar — всегда виден, не вытесняется notebook
        statusbar = ttk.Frame(self.root, relief="groove", padding=(10, 6))
        statusbar.grid(row=2, column=0, sticky="ew")

        self.lbl_op = ttk.Label(statusbar, textvariable=self.var_current_op, font=("Segoe UI", 9))
        self.lbl_op.pack(side="left")

        self.op_progress = ttk.Progressbar(statusbar, orient="horizontal", length=180, mode="determinate")
        self.op_progress.pack(side="right", padx=5)

    def _build_tab_main(self, parent):
        # Action Buttons Bar
        btn_frame = ttk.Frame(parent)
        btn_frame.pack(fill="x", pady=(0, 10))

        self.btn_sync = ttk.Button(btn_frame, text="🔄 Синхронизировать сейчас", command=self.action_sync_now, style="Primary.TButton")
        self.btn_sync.pack(side="left", padx=(0, 6))

        self.btn_pause = ttk.Button(btn_frame, text="⏸ Пауза", command=self.action_toggle_pause)
        self.btn_pause.pack(side="left", padx=(0, 6))

        btn_open_local = ttk.Button(btn_frame, text="📁 Открыть папку на ПК", command=self.action_open_local_folder)
        btn_open_local.pack(side="left", padx=(0, 6))

        btn_open_web = ttk.Button(btn_frame, text="🌐 Яндекс Диск в браузере", command=self.action_open_web)
        btn_open_web.pack(side="left", padx=(0, 6))

        btn_clear_log = ttk.Button(btn_frame, text="🗑 Очистить лог", command=self.action_clear_log)
        btn_clear_log.pack(side="right")

        # Log Section
        log_label_frame = ttk.LabelFrame(parent, text="Журнал событий и операций", padding=8)
        log_label_frame.pack(fill="both", expand=True)

        text_container = ttk.Frame(log_label_frame)
        text_container.pack(fill="both", expand=True)

        scrollbar = ttk.Scrollbar(text_container, orient="vertical")
        self.log_text = Text(
            text_container, wrap="word", yscrollcommand=scrollbar.set,
            font=("Consolas", 10), bg="#18181b", fg="#f4f4f5",
            insertbackground="white", selectbackground="#2563eb",
            spacing1=3, spacing3=3, padx=10, pady=8
        )
        scrollbar.config(command=self.log_text.yview)

        scrollbar.pack(side="right", fill="y")
        self.log_text.pack(side="left", fill="both", expand=True)

        # Crisp Log Color Tags
        self.log_text.tag_config("SUCCESS", foreground="#34d399", font=("Consolas", 10, "bold"))
        self.log_text.tag_config("INFO", foreground="#38bdf8")
        self.log_text.tag_config("WARN", foreground="#fbbf24", font=("Consolas", 10, "bold"))
        self.log_text.tag_config("ERROR", foreground="#f87171", font=("Consolas", 10, "bold"))
        self.log_text.tag_config("TIME", foreground="#71717a")

    def _build_tab_settings(self, parent):
        # OAuth Section
        oauth_frame = ttk.LabelFrame(parent, text="Авторизация Яндекс Диска", padding=12)
        oauth_frame.pack(fill="x", pady=(0, 10))

        ttk.Label(oauth_frame, text="OAuth-токен доступа:").grid(row=0, column=0, sticky="w", pady=6)
        self.entry_token = ttk.Entry(oauth_frame, textvariable=self.var_token, width=44, show="*", font=("Segoe UI", 10))
        self.entry_token.grid(row=0, column=1, sticky="ew", padx=8, pady=6)
        self._add_context_menu(self.entry_token)

        btn_paste = ttk.Button(oauth_frame, text="📋 Вставить", width=12, command=self.paste_token_from_clipboard)
        btn_paste.grid(row=0, column=2, padx=4, pady=6)

        self.btn_show_token = ttk.Button(oauth_frame, text="👁 Показать", width=12, command=self.toggle_token_visibility)
        self.btn_show_token.grid(row=0, column=3, padx=4, pady=6)

        btn_test = ttk.Button(oauth_frame, text="🔌 Проверить", width=12, command=self.check_connection_async)
        btn_test.grid(row=0, column=4, padx=4, pady=6)

        oauth_frame.columnconfigure(1, weight=1)

        # Folder Paths Section
        folder_frame = ttk.LabelFrame(parent, text="Синхронизируемые папки", padding=12)
        folder_frame.pack(fill="x", pady=(0, 10))

        ttk.Label(folder_frame, text="Локальная папка на ПК:").grid(row=0, column=0, sticky="w", pady=6)
        entry_local = ttk.Entry(folder_frame, textvariable=self.var_local_folder, font=("Segoe UI", 10))
        entry_local.grid(row=0, column=1, sticky="ew", padx=8, pady=6)
        self._add_context_menu(entry_local)
        ttk.Button(folder_frame, text="📁 Обзор...", width=12, command=self.browse_local_folder).grid(row=0, column=2, padx=4, pady=6)

        ttk.Label(folder_frame, text="Папка на Яндекс Диске:").grid(row=1, column=0, sticky="w", pady=6)
        entry_remote = ttk.Entry(folder_frame, textvariable=self.var_remote_folder, font=("Segoe UI", 10))
        entry_remote.grid(row=1, column=1, sticky="ew", padx=8, pady=6)
        self._add_context_menu(entry_remote)
        ttk.Label(folder_frame, text="(например: /SyncFolder или /)").grid(row=1, column=2, sticky="w", padx=6, pady=6)

        folder_frame.columnconfigure(1, weight=1)

        # Rules & Preferences Section
        rules_frame = ttk.LabelFrame(parent, text="Параметры и правила синхронизации", padding=12)
        rules_frame.pack(fill="x", pady=(0, 10))

        ttk.Label(rules_frame, text="Интервал автосинхронизации (секунд):").grid(row=0, column=0, sticky="w", pady=6)
        ttk.Spinbox(rules_frame, from_=5, to=3600, textvariable=self.var_interval, width=12, font=("Segoe UI", 10)).grid(row=0, column=1, sticky="w", padx=8, pady=6)

        ttk.Label(rules_frame, text="Разрешение конфликтов:").grid(row=1, column=0, sticky="w", pady=6)
        self.conflict_display_map = {
            "keep_both": "Сохранять оба файла (с суффиксом Конфликт)",
            "newer_wins": "Брать более свежий файл по дате изменения",
            "local_wins": "Приоритет ПК (перезаписывать облако)",
            "remote_wins": "Приоритет Облака (перезаписывать ПК)",
        }
        self.conflict_value_map = {v: k for k, v in self.conflict_display_map.items()}
        self.var_conflict_display = StringVar(value=self.conflict_display_map.get(self.var_conflict.get(), self.conflict_display_map["keep_both"]))
        self.combo_conflict = ttk.Combobox(rules_frame, textvariable=self.var_conflict_display, state="readonly", width=50, font=("Segoe UI", 10), values=list(self.conflict_display_map.values()))
        self.combo_conflict.grid(row=1, column=1, sticky="w", padx=8, pady=6)

        cb_deletions = ttk.Checkbutton(
            rules_frame,
            text="Синхронизировать удаление файлов (удалять в облаке при удалении на ПК и наоборот)",
            variable=self.var_sync_deletions
        )
        cb_deletions.grid(row=2, column=0, columnspan=2, sticky="w", pady=5)

        cb_tray = ttk.Checkbutton(
            rules_frame,
            text="Сворачивать в системный трей при закрытии крестиком",
            variable=self.var_minimize_tray
        )
        cb_tray.grid(row=3, column=0, columnspan=2, sticky="w", pady=5)

        cb_autostart = ttk.Checkbutton(
            rules_frame,
            text="Запускать приложение при старте Windows",
            variable=self.var_autostart
        )
        cb_autostart.grid(row=4, column=0, columnspan=2, sticky="w", pady=5)

        # Save Button
        btn_save = ttk.Button(parent, text="💾 Сохранить и применить настройки", command=self.save_settings, style="Primary.TButton")
        btn_save.pack(anchor="e", pady=12)

    def _build_tab_help(self, parent):
        help_box = ttk.LabelFrame(parent, text="Быстрый старт и получение токена", padding=14)
        help_box.pack(fill="both", expand=True)

        help_text = (
            "Как настроить приложение за 1 минуту:\n\n"
            "1. Получение OAuth-токена Яндекс Диска:\n"
            "   • Перейдите на официальный Полигон Яндекс.Диска:\n"
            "     https://yandex.ru/dev/disk/poligon/\n"
            "   • В правом верхнем углу нажмите желтую кнопку «Получить OAuth-токен».\n"
            "   • Скопируйте полученный токен (строка вида y0_AgAAAA...).\n"
            "   • Во вкладке «Настройки» нажмите кнопку «Вставить» и «Проверить».\n\n"
            "2. Выбор локальной папки:\n"
            "   • По умолчанию используется папка C:\\Users\\<Пользователь>\\YandexDiskSync.\n"
            "   • Вы можете выбрать любую свою папку на диске ПК через кнопку «Обзор...».\n\n"
            "3. Принцип работы двусторонней синхронизации:\n"
            "   • Файлы, добавленные или измененные на ПК, автоматически загружаются в Яндекс Диск.\n"
            "   • Файлы, загруженные в Яндекс Диск через браузер или телефон, скачиваются на ПК.\n"
            "   • При одновременном изменении на ПК и в облаке создается защитная копия с меткой времени,\n"
            "     что исключает потерю ваших данных.\n"
            "   • При сворачивании приложение работает в фоновом режиме в системном трее Windows."
        )

        lbl = ttk.Label(help_box, text=help_text, justify="left", font=("Segoe UI", 10))
        lbl.pack(anchor="nw", pady=(0, 12))

        btn_open_poly = ttk.Button(help_box, text="🔗 Открыть Полигон Яндекса для получения токена",
                                   command=lambda: webbrowser.open("https://yandex.ru/dev/disk/poligon/"))
        btn_open_poly.pack(anchor="w")

    def _setup_clipboard_shortcuts(self):
        def handle_control_keys(event):
            # On Windows, event.keycode is the hardware virtual key code
            # 86 = V, 67 = C, 88 = X, 65 = A (independent of keyboard language layout)
            kc = event.keycode
            try:
                if kc == 86:  # VK_V
                    event.widget.event_generate("<<Paste>>")
                    return "break"
                elif kc == 67:  # VK_C
                    event.widget.event_generate("<<Copy>>")
                    return "break"
                elif kc == 88:  # VK_X
                    event.widget.event_generate("<<Cut>>")
                    return "break"
                elif kc == 65:  # VK_A
                    if hasattr(event.widget, "selection_range"):
                        event.widget.selection_range(0, END)
                        return "break"
                    elif hasattr(event.widget, "tag_add"):
                        event.widget.tag_add("sel", "1.0", "end")
                        return "break"
            except Exception:
                pass

        self.root.bind_all("<Control-KeyPress>", handle_control_keys)

    def _add_context_menu(self, widget):
        menu = Menu(widget, tearoff=0, font=("Segoe UI", 9))
        menu.add_command(label="Вставить", command=lambda: widget.event_generate("<<Paste>>"))
        menu.add_command(label="Копировать", command=lambda: widget.event_generate("<<Copy>>"))
        menu.add_command(label="Вырезать", command=lambda: widget.event_generate("<<Cut>>"))
        menu.add_separator()
        menu.add_command(label="Выделить всё", command=lambda: widget.selection_range(0, END) if hasattr(widget, "selection_range") else None)

        def show_popup(event):
            try:
                widget.focus_set()
                menu.tk_popup(event.x_root, event.y_root)
            finally:
                menu.grab_release()

        widget.bind("<Button-3>", show_popup)

    def paste_token_from_clipboard(self):
        try:
            clip = self.root.clipboard_get().strip()
            # Clean token from quotes or spaces
            clip = clip.strip("'\" \r\n\t")
            if clip:
                self.var_token.set(clip)
                self.entry_token.focus_set()
            else:
                messagebox.showwarning("Буфер обмена", "Буфер обмена пуст.")
        except Exception:
            messagebox.showwarning("Буфер обмена", "Не удалось прочитать текст из буфера обмена.")

    def _setup_tray(self):
        try:
            self.tray = WindowsTrayIcon(
                tooltip="Яндекс Диск Синхронизация",
                on_open=self.restore_from_tray,
                on_sync=self.action_sync_now,
                on_toggle_pause=self.action_toggle_pause,
                on_exit=self.action_quit_app,
                get_is_paused=lambda: self.engine.is_paused()
            )
            self.tray.start()
        except Exception as e:
            print(f"Tray initialization error: {e}")

    def toggle_token_visibility(self):
        if self.entry_token.cget("show") == "*":
            self.entry_token.config(show="")
            self.btn_show_token.config(text="🙈 Скрыть")
        else:
            self.entry_token.config(show="*")
            self.btn_show_token.config(text="👁 Показать")

    def browse_local_folder(self):
        chosen = filedialog.askdirectory(initialdir=self.var_local_folder.get() or str(os.path.expanduser("~")))
        if chosen:
            self.var_local_folder.set(chosen)


    def _read_autostart_state(self) -> bool:
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
            winreg.CloseKey(key)

    def save_settings(self):
        conflict_code = self.conflict_value_map.get(self.var_conflict_display.get(), "keep_both")
        self.config.update(
            oauth_token=self.var_token.get().strip(),
            local_folder=self.var_local_folder.get().strip(),
            remote_folder=self.var_remote_folder.get().strip(),
            sync_interval_seconds=int(self.var_interval.get()),
            conflict_resolution=conflict_code,
            sync_deletions=self.var_sync_deletions.get(),
            minimize_to_tray=self.var_minimize_tray.get(),
            autostart=self.var_autostart.get()
        )
        self._apply_autostart(self.var_autostart.get())
        self.engine.api.set_token(self.var_token.get().strip())
        self.engine.log("INFO", "💾 Настройки сохранены. Запуск синхронизации...")
        messagebox.showinfo("Успех", "Настройки успешно сохранены!")
        self.check_connection_async()
        self.engine.trigger_sync()

    def check_connection_async(self):
        token = self.var_token.get().strip()
        if not token:
            self.var_status_badge.set("🔴 Токен не указан (укажите в Настройках)")
            self.lbl_status.config(foreground="#dc2626")
            self.var_storage_text.set("Объем диска: нет данных")
            self.disk_progress["value"] = 0
            return

        self.var_status_badge.set("🟡 Проверка соединения...")
        self.lbl_status.config(foreground="#d97706")

        def task():
            try:
                self.engine.api.set_token(token)
                info = self.engine.api.get_disk_info()
                total = info.get("total_space", 0)
                used = info.get("used_space", 0)
                user = info.get("user", {}).get("display_name", "")

                percent = (used / total * 100) if total > 0 else 0
                used_str = format_bytes(used)
                total_str = format_bytes(total)
                label = f"Занято: {used_str} из {total_str} ({percent:.1f}%)"

                def update_ui():
                    badge = f"🟢 Подключено: {user}" if user else "🟢 Подключено"
                    self.var_status_badge.set(badge)
                    self.lbl_status.config(foreground="#16a34a")
                    self.var_storage_text.set(label)
                    self.disk_progress["value"] = percent
                    if self.tray:
                        self.tray.update_tooltip(f"Яндекс Диск: {label}")

                self.root.after(0, update_ui)
            except Exception as e:
                def update_err():
                    self.var_status_badge.set("🔴 Ошибка подключения к Яндекс Диску")
                    self.lbl_status.config(foreground="#dc2626")
                    self.var_storage_text.set("Объем диска: недоступен")
                    self.disk_progress["value"] = 0
                self.root.after(0, update_err)

        threading.Thread(target=task, daemon=True).start()

    def action_sync_now(self):
        self.engine.log("INFO", "🔄 Кнопка нажата: запуск синхронизации...")
        self.engine.trigger_sync()

    def action_toggle_pause(self):
        if self.engine.is_paused():
            self.engine.resume()
            self.btn_pause.config(text="⏸ Пауза")
        else:
            self.engine.pause()
            self.btn_pause.config(text="▶ Возобновить")

    def action_open_local_folder(self):
        folder = self.var_local_folder.get().strip()
        if not os.path.exists(folder):
            os.makedirs(folder, exist_ok=True)
        if sys.platform == "win32":
            os.startfile(folder)
        else:
            webbrowser.open(folder)

    def action_open_web(self):
        remote_path = self.var_remote_folder.get().strip().lstrip("/")
        if remote_path:
            webbrowser.open(f"https://disk.yandex.ru/client/disk/{remote_path}")
        else:
            webbrowser.open("https://disk.yandex.ru/client/disk")

    def action_clear_log(self):
        self.log_text.delete("1.0", END)

    def action_quit_app(self):
        self.engine.stop()
        if self.tray:
            self.tray.stop()
        self.root.quit()
        self.root.destroy()
        sys.exit(0)

    def on_close_window(self):
        if self.var_minimize_tray.get():
            self.root.withdraw()
        else:
            self.action_quit_app()

    def restore_from_tray(self):
        self.root.after(0, self._do_restore)

    def _do_restore(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def _handle_log(self, level: str, message: str):
        def update():
            time_str = datetime.now().strftime("%H:%M:%S")
            self.log_text.insert(END, f"[{time_str}] ", "TIME")
            self.log_text.insert(END, f"[{level}] {message}\n", level)
            self.log_text.see(END)
        self.root.after(0, update)

    def _handle_status(self, status: str):
        def update():
            self.var_current_op.set(status)
        self.root.after(0, update)

    def _handle_progress(self, current: int, total: int, action: str):
        def update():
            if total > 0:
                pct = int((current / total) * 100)
                self.op_progress["value"] = pct
                cur_str = format_bytes(current)
                tot_str = format_bytes(total)
                self.var_current_op.set(f"{action}: {cur_str} / {tot_str} ({pct}%)")
            else:
                self.op_progress["value"] = 0
        self.root.after(0, update)

    def _handle_sync_completed(self, stats):
        def update():
            self.op_progress["value"] = 100
            self.root.after(2000, lambda: self.op_progress.configure(value=0))
            self.check_connection_async()
        self.root.after(0, update)
