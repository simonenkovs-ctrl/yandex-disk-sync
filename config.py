import json
import os
import sys
from pathlib import Path

DEFAULT_CONFIG = {
    "oauth_token": "",
    "local_folder": str(Path.home() / "YandexDiskSync"),
    "remote_folder": "/SyncFolder",
    "sync_interval_seconds": 30,
    "conflict_resolution": "keep_both",  # keep_both, local_wins, remote_wins, newer_wins
    "sync_deletions": True,
    "start_minimized": False,
    "minimize_to_tray": True,
    "auto_start_sync": True,
    "autostart": False,
    "manifest_ttl_seconds": 3600,
}

CONFIG_FILE_NAME = "config.json"


class ConfigManager:
    def __init__(self, base_dir=None):
        if base_dir is None:
            if getattr(sys, "frozen", False):
                self.base_dir = Path(sys.executable).resolve().parent
            else:
                self.base_dir = Path(__file__).resolve().parent
        else:
            self.base_dir = Path(base_dir)
        self.config_path = self.base_dir / CONFIG_FILE_NAME
        self.config = dict(DEFAULT_CONFIG)
        self.load()

    def load(self):
        if self.config_path.exists():
            try:
                with open(self.config_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.config.update(data)
            except Exception as e:
                print(f"Error loading config: {e}")
        return self.config

    def save(self):
        try:
            with open(self.config_path, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=4, ensure_ascii=False)
            return True
        except Exception as e:
            print(f"Error saving config: {e}")
            return False

    def get(self, key, default=None):
        return self.config.get(key, default)

    def set(self, key, value):
        self.config[key] = value

    def update(self, **kwargs):
        self.config.update(kwargs)
        self.save()
