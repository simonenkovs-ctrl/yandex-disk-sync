import sqlite3
import sys
import threading
from pathlib import Path


class SyncDatabase:
    def __init__(self, db_path=None):
        if db_path is None:
            if getattr(sys, "frozen", False):
                base_dir = Path(sys.executable).resolve().parent
            else:
                base_dir = Path(__file__).resolve().parent
            db_path = base_dir / "sync_state.db"
        self.db_path = str(db_path)
        self._lock = threading.Lock()
        self._init_db()


    def _get_connection(self):
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self._lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS file_records (
                    rel_path TEXT PRIMARY KEY,
                    local_mtime REAL,
                    local_size INTEGER,
                    local_md5 TEXT,
                    remote_md5 TEXT,
                    remote_modified TEXT,
                    last_synced REAL,
                    status TEXT
                )
            """)
            conn.commit()
            conn.close()

    def get_record(self, rel_path):
        with self._lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            cursor.execute("SELECT * FROM file_records WHERE rel_path = ?", (rel_path,))
            row = cursor.fetchone()
            conn.close()
            return dict(row) if row else None

    def get_all_records(self):
        with self._lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            cursor.execute("SELECT * FROM file_records")
            rows = cursor.fetchall()
            conn.close()
            return {row["rel_path"]: dict(row) for row in rows}

    def upsert_record(self, rel_path, local_mtime, local_size, local_md5, remote_md5, remote_modified, last_synced, status="synced"):
        with self._lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            cursor.execute("""
                INSERT INTO file_records (
                    rel_path, local_mtime, local_size, local_md5, remote_md5, remote_modified, last_synced, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(rel_path) DO UPDATE SET
                    local_mtime = excluded.local_mtime,
                    local_size = excluded.local_size,
                    local_md5 = excluded.local_md5,
                    remote_md5 = excluded.remote_md5,
                    remote_modified = excluded.remote_modified,
                    last_synced = excluded.last_synced,
                    status = excluded.status
            """, (rel_path, local_mtime, local_size, local_md5, remote_md5, remote_modified, last_synced, status))
            conn.commit()
            conn.close()

    def delete_record(self, rel_path):
        with self._lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            cursor.execute("DELETE FROM file_records WHERE rel_path = ?", (rel_path,))
            conn.commit()
            conn.close()

    def clear_all(self):
        with self._lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            cursor.execute("DELETE FROM file_records")
            conn.commit()
            conn.close()
