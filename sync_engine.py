import hashlib
import os
import shutil
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, Optional, Callable, Set

from config import ConfigManager
from database import SyncDatabase
from yandex_api import YandexDiskClient, YandexDiskAPIError


IGNORED_NAMES: Set[str] = {
    ".git", ".svn", ".hg", "__pycache__", ".idea", ".vscode",
    "desktop.ini", "thumbs.db", ".ds_store", "$recycle.bin",
    "sync_state.db", "config.json"
}

IGNORED_EXTENSIONS: Set[str] = {
    ".tmp", ".crdownload", ".part", ".swp", ".lock"
}


def compute_md5(file_path: str, block_size: int = 131072) -> str:
    hasher = hashlib.md5()
    try:
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(block_size), b""):
                hasher.update(chunk)
        return hasher.hexdigest().lower()
    except Exception:
        return ""


def parse_iso_time(iso_str: str) -> float:
    try:
        clean_str = iso_str.replace("Z", "+00:00")
        dt = datetime.fromisoformat(clean_str)
        return dt.timestamp()
    except Exception:
        return 0.0


class SyncEngine:
    def __init__(self, config_manager: ConfigManager, database: SyncDatabase):
        self.config = config_manager
        self.db = database
        self.api = YandexDiskClient(self.config.get("oauth_token", ""))

        self.on_log: Optional[Callable[[str, str], None]] = None
        self.on_status_change: Optional[Callable[[str], None]] = None
        self.on_progress: Optional[Callable[[int, int, str], None]] = None
        self.on_sync_completed: Optional[Callable[[Dict[str, int]], None]] = None

        self._is_running = False
        self._is_paused = False
        self._sync_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._trigger_event = threading.Event()
        self._sync_lock = threading.Lock()

        # In-memory hash cache: rel_path -> (mtime, size, md5)
        self._local_hash_cache: Dict[str, tuple[float, int, str]] = {}

    def log(self, level: str, message: str):
        if self.on_log:
            self.on_log(level, message)
        else:
            print(f"[{level}] {message}")

    def set_status(self, status: str):
        if self.on_status_change:
            self.on_status_change(status)

    def is_running(self) -> bool:
        return self._is_running

    def is_paused(self) -> bool:
        return self._is_paused

    def pause(self):
        self._is_paused = True
        self.log("WARN", "Синхронизация приостановлена пользователем.")
        self.set_status("Приостановлено")

    def resume(self):
        self._is_paused = False
        self.log("INFO", "Синхронизация возобновлена.")
        self.set_status("Работает")
        self._trigger_event.set()

    def start(self):
        if self._is_running:
            return
        self._is_running = True
        self._stop_event.clear()
        self._sync_thread = threading.Thread(target=self._worker_loop, daemon=True)
        self._sync_thread.start()
        self.log("INFO", "Фоновый процесс синхронизации запущен.")

    def stop(self):
        self._is_running = False
        self._stop_event.set()
        self._trigger_event.set()
        if self._sync_thread and self._sync_thread.is_alive():
            self._sync_thread.join(timeout=3)
        self.log("INFO", "Синхронизация остановлена.")
        self.set_status("Остановлено")

    def trigger_sync(self):
        """Forces an immediate sync cycle"""
        self._trigger_event.set()

    def _is_ignored(self, rel_path: str) -> bool:
        parts = rel_path.replace("\\", "/").split("/")
        for part in parts:
            part_lower = part.lower()
            if part_lower in IGNORED_NAMES:
                return True
            if part_lower.startswith(".tmp_"):
                return True

        filename = parts[-1]
        ext = os.path.splitext(filename)[1].lower()
        if ext in IGNORED_EXTENSIONS:
            return True
        if filename.endswith(".tmp_download"):
            return True
        return False

    def scan_local_folder(self, root_dir: str) -> Dict[str, Dict[str, Any]]:
        """
        Fast scan: collects file paths, mtimes, and sizes without computing heavy MD5 hashes.
        MD5 is computed lazily only when comparing files.
        """
        local_files: Dict[str, Dict[str, Any]] = {}
        if not os.path.exists(root_dir):
            try:
                os.makedirs(root_dir, exist_ok=True)
            except Exception as e:
                self.log("ERROR", f"Не удалось создать локальную папку {root_dir}: {e}")
            return local_files

        root_path = Path(root_dir).resolve()
        for dirpath, dirnames, filenames in os.walk(root_path):
            dirnames[:] = [d for d in dirnames if d.lower() not in IGNORED_NAMES and not d.startswith(".")]

            for filename in filenames:
                full_path = os.path.join(dirpath, filename)
                try:
                    rel_path = os.path.relpath(full_path, root_path).replace("\\", "/")
                    if self._is_ignored(rel_path):
                        continue

                    stat = os.stat(full_path)
                    local_files[rel_path] = {
                        "full_path": full_path,
                        "mtime": stat.st_mtime,
                        "size": stat.st_size,
                    }
                except (OSError, PermissionError) as e:
                    self.log("WARN", f"Пропуск файла {filename}: {e}")

        return local_files

    def _get_local_md5(self, rel_path: str, local_info: Dict[str, Any], db_record: Optional[Dict[str, Any]]) -> str:
        """
        Retrieves local MD5 hash with instant DB/memory caching.
        """
        mtime = local_info["mtime"]
        size = local_info["size"]

        # 1. Check in-memory cache
        cached = self._local_hash_cache.get(rel_path)
        if cached and cached[0] == mtime and cached[1] == size:
            return cached[2]

        # 2. Check SQLite DB record
        if db_record and db_record.get("local_mtime") == mtime and db_record.get("local_size") == size:
            h = db_record.get("local_md5", "")
            if h:
                self._local_hash_cache[rel_path] = (mtime, size, h)
                return h

        # 3. Calculate from disk
        file_md5 = compute_md5(local_info["full_path"])
        if file_md5:
            self._local_hash_cache[rel_path] = (mtime, size, file_md5)
        return file_md5

    def _worker_loop(self):
        while not self._stop_event.is_set():
            if not self._is_paused:
                try:
                    self.perform_sync()
                except Exception as e:
                    self.log("ERROR", f"Сбой в цикле синхронизации: {e}")
                    self.set_status(f"Ошибка: {e}")

            interval = int(self.config.get("sync_interval_seconds", 30))
            self._trigger_event.wait(timeout=interval)
            self._trigger_event.clear()

    def perform_sync(self):
        if not self._sync_lock.acquire(blocking=False):
            self.log("INFO", "Синхронизация уже выполняется прямо сейчас...")
            return

        try:
            token = self.config.get("oauth_token", "").strip()
            if not token:
                self.log("WARN", "OAuth-токен не указан. Укажите токен во вкладке «Настройки».")
                self.set_status("Ожидание токена...")
                return

            self.api.set_token(token)
            local_folder = self.config.get("local_folder", "").strip()
            remote_folder = self.config.get("remote_folder", "/SyncFolder").strip()
            sync_deletions = self.config.get("sync_deletions", True)
            conflict_res = self.config.get("conflict_resolution", "keep_both")

            self.log("INFO", f"▶ Начало синхронизации: [{local_folder}] ↔ [Я.Диск:{remote_folder}]")
            self.set_status("Проверка облачной папки...")

            # 1. Ensure remote root exists
            try:
                self.api.create_folder(remote_folder)
            except Exception as e:
                self.log("ERROR", f"Не удалось подключиться к Яндекс Диску: {e}")
                self.set_status("Ошибка подключения к Яндекс Диску")
                return

            # 2. Fast scan local files
            self.set_status("Сканирование файлов на ПК...")
            t_scan_start = time.time()
            local_files = self.scan_local_folder(local_folder)
            t_scan = time.time() - t_scan_start

            # 3. Scan remote files
            self.set_status("Сканирование файлов в Яндекс Диске...")
            try:
                remote_files = self.api.list_folder_recursive(remote_folder, progress_callback=lambda msg: self.set_status(msg))
            except Exception as e:
                self.log("ERROR", f"Ошибка чтения папки в облаке: {e}")
                self.set_status("Ошибка чтения облака")
                return

            self.log("INFO", f"Файлов найдено: на ПК — {len(local_files)} (за {t_scan:.1f}с), в облаке — {len(remote_files)}")

            db_records = self.db.get_all_records()
            all_rel_paths = set(local_files.keys()) | set(remote_files.keys()) | set(db_records.keys())

            stats = {"uploaded": 0, "downloaded": 0, "deleted": 0, "conflicts": 0}

            for rel_path in sorted(all_rel_paths):
                if self._stop_event.is_set():
                    break

                if self._is_ignored(rel_path):
                    continue

                local_info = local_files.get(rel_path)
                remote_info = remote_files.get(rel_path)
                db_record = db_records.get(rel_path)

                remote_full_path = self.api.normalize_remote_path(f"{remote_folder}/{rel_path}")
                local_full_path = os.path.join(local_folder, rel_path.replace("/", os.sep))

                # CASE 1: File exists on both Local and Remote
                if local_info and remote_info:
                    # Quick size check first
                    remote_size = remote_info.get("size", 0)
                    local_size = local_info.get("size", 0)

                    # Compute local MD5 lazily
                    local_md5 = self._get_local_md5(rel_path, local_info, db_record)
                    remote_md5 = remote_info.get("md5", "")

                    if local_md5 and remote_md5 and local_md5 == remote_md5:
                        self.db.upsert_record(
                            rel_path, local_info["mtime"], local_size,
                            local_md5, remote_md5, remote_info.get("modified", ""),
                            time.time(), "synced"
                        )
                        continue

                    # Files differ! Check change history
                    local_changed = not db_record or db_record["local_md5"] != local_md5
                    remote_changed = not db_record or db_record["remote_md5"] != remote_md5

                    if local_changed and not remote_changed:
                        self._do_upload(local_full_path, remote_full_path, rel_path, local_info, local_md5)
                        stats["uploaded"] += 1
                    elif remote_changed and not local_changed:
                        self._do_download(remote_full_path, local_full_path, rel_path, remote_info)
                        stats["downloaded"] += 1
                    else:
                        stats["conflicts"] += 1
                        self._resolve_conflict(
                            conflict_res, rel_path, local_full_path, remote_full_path,
                            local_info, remote_info, stats
                        )

                # CASE 2: File exists Locally, but NOT on Remote
                elif local_info and not remote_info:
                    if db_record and sync_deletions:
                        self.log("INFO", f"Удаление локального файла (удален в облаке): {rel_path}")
                        try:
                            if os.path.exists(local_full_path):
                                os.remove(local_full_path)
                            self.db.delete_record(rel_path)
                            stats["deleted"] += 1
                        except Exception as e:
                            self.log("ERROR", f"Не удалось удалить локальный файл {rel_path}: {e}")
                    else:
                        local_md5 = self._get_local_md5(rel_path, local_info, db_record)
                        self._do_upload(local_full_path, remote_full_path, rel_path, local_info, local_md5)
                        stats["uploaded"] += 1

                # CASE 3: File exists on Remote, but NOT Locally
                elif remote_info and not local_info:
                    if db_record and sync_deletions:
                        self.log("INFO", f"Удаление файла в облаке (удален на ПК): {rel_path}")
                        try:
                            self.api.delete_resource(remote_full_path)
                            self.db.delete_record(rel_path)
                            stats["deleted"] += 1
                        except Exception as e:
                            self.log("ERROR", f"Не удалось удалить файл в облаке {rel_path}: {e}")
                    else:
                        self._do_download(remote_full_path, local_full_path, rel_path, remote_info)
                        stats["downloaded"] += 1

                # CASE 4: Obsolete record
                elif db_record:
                    self.db.delete_record(rel_path)

            now_str = datetime.now().strftime("%H:%M:%S")
            self.set_status(f"Синхронизировано ({now_str})")

            if any(stats.values()):
                self.log(
                    "SUCCESS",
                    f"✔ Синхронизация завершена: ⬆ {stats['uploaded']} загружено, "
                    f"⬇ {stats['downloaded']} скачано, 🗑 {stats['deleted']} удалено, "
                    f"⚠️ {stats['conflicts']} конфликтов."
                )
            else:
                self.log(
                    "SUCCESS",
                    f"✔ Все файлы актуальны! Изменений нет (ПК: {len(local_files)}, Облако: {len(remote_files)})."
                )

            if self.on_sync_completed:
                self.on_sync_completed(stats)

        except Exception as e:
            self.log("ERROR", f"Ошибка во время синхронизации: {e}")
            self.set_status("Ошибка синхронизации")
        finally:
            self._sync_lock.release()

    def _do_upload(self, local_path: str, remote_path: str, rel_path: str, local_info: Dict[str, Any], local_md5: str):
        size = local_info["size"]
        self.set_status(f"Загрузка: {rel_path}")
        self.log("INFO", f"⬆ Загрузка в облако: {rel_path} ({size} байт)")

        def progress_cb(current, total):
            if self.on_progress:
                self.on_progress(current, total, f"Загрузка {rel_path}")

        self.api.upload_file(local_path, remote_path, overwrite=True, progress_callback=progress_cb)

        meta = self.api.get_resource_meta(remote_path)
        remote_md5 = meta.get("md5", local_md5) if meta else local_md5
        remote_mod = meta.get("modified", "") if meta else ""

        self.db.upsert_record(
            rel_path, local_info["mtime"], size,
            local_md5, remote_md5, remote_mod, time.time(), "synced"
        )
        self.log("SUCCESS", f"✔ Успешно загружен: {rel_path}")

    def _do_download(self, remote_path: str, local_path: str, rel_path: str, remote_info: Dict[str, Any]):
        size = remote_info.get("size", 0)
        self.set_status(f"Скачивание: {rel_path}")
        self.log("INFO", f"⬇ Скачивание из облака: {rel_path} ({size} байт)")

        def progress_cb(current, total):
            if self.on_progress:
                self.on_progress(current, total, f"Скачивание {rel_path}")

        self.api.download_file(remote_path, local_path, progress_callback=progress_cb)

        stat = os.stat(local_path)
        local_md5 = compute_md5(local_path)
        self._local_hash_cache[rel_path] = (stat.st_mtime, stat.st_size, local_md5)

        self.db.upsert_record(
            rel_path, stat.st_mtime, stat.st_size,
            local_md5, remote_info.get("md5", ""), remote_info.get("modified", ""), time.time(), "synced"
        )
        self.log("SUCCESS", f"✔ Успешно скачан: {rel_path}")

    def _resolve_conflict(self, strategy: str, rel_path: str, local_path: str, remote_path: str,
                          local_info: Dict[str, Any], remote_info: Dict[str, Any], stats: Dict[str, int]):
        self.log("WARN", f"⚠️ Обнаружен конфликт версий для: {rel_path}")
        local_md5 = self._get_local_md5(rel_path, local_info, None)

        if strategy == "local_wins":
            self.log("INFO", f"Разрешение конфликта: приоритет локальной версии ({rel_path})")
            self._do_upload(local_path, remote_path, rel_path, local_info, local_md5)
            stats["uploaded"] += 1
        elif strategy == "remote_wins":
            self.log("INFO", f"Разрешение конфликта: приоритет облачной версии ({rel_path})")
            self._do_download(remote_path, local_path, rel_path, remote_info)
            stats["downloaded"] += 1
        elif strategy == "newer_wins":
            remote_epoch = parse_iso_time(remote_info.get("modified", ""))
            local_epoch = local_info.get("mtime", 0.0)
            if local_epoch >= remote_epoch:
                self.log("INFO", f"Разрешение: локальный новее ({rel_path})")
                self._do_upload(local_path, remote_path, rel_path, local_info, local_md5)
                stats["uploaded"] += 1
            else:
                self.log("INFO", f"Разрешение: облачный новее ({rel_path})")
                self._do_download(remote_path, local_path, rel_path, remote_info)
                stats["downloaded"] += 1
        else:
            # keep_both
            dir_name, file_name = os.path.split(local_path)
            stem, ext = os.path.splitext(file_name)
            time_suffix = datetime.now().strftime("%Y%m%d_%H%M%S")
            conflict_filename = f"{stem} (Конфликт PC {time_suffix}){ext}"
            conflict_local_path = os.path.join(dir_name, conflict_filename)

            self.log("INFO", f"Сохранение обеих версий: {file_name} -> {conflict_filename}")
            shutil.copy2(local_path, conflict_local_path)

            self._do_download(remote_path, local_path, rel_path, remote_info)
            stats["downloaded"] += 1

            rel_dir = os.path.dirname(rel_path).replace("\\", "/")
            conflict_rel = f"{rel_dir}/{conflict_filename}".lstrip("/") if rel_dir else conflict_filename
            conflict_remote_path = self.api.normalize_remote_path(
                f"{self.config.get('remote_folder')}/{conflict_rel}"
            )
            c_stat = os.stat(conflict_local_path)
            c_info = {
                "full_path": conflict_local_path,
                "mtime": c_stat.st_mtime,
                "size": c_stat.st_size,
            }
            c_md5 = compute_md5(conflict_local_path)
            self._do_upload(conflict_local_path, conflict_remote_path, conflict_rel, c_info, c_md5)
            stats["uploaded"] += 1
