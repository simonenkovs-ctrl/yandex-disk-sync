import json
import os
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, Any, List, Optional, Callable


class YandexDiskAPIError(Exception):
    def __init__(self, message: str, status_code: Optional[int] = None, error_code: Optional[str] = None):
        super().__init__(message)
        self.status_code = status_code
        self.error_code = error_code


class YandexDiskClient:
    BASE_URL = "https://cloud-api.yandex.net/v1/disk"

    def __init__(self, token: str, timeout: int = 40):
        self.token = token.strip() if token else ""
        self.timeout = timeout
        self._ssl_context = ssl.create_default_context()

    def set_token(self, token: str):
        self.token = token.strip() if token else ""

    def _headers(self) -> Dict[str, str]:
        if not self.token:
            raise YandexDiskAPIError("OAuth-токен не указан. Укажите токен в настройках.")
        return {
            "Authorization": f"OAuth {self.token}",
            "User-Agent": "YandexDiskSyncApp/1.0",
            "Accept": "application/json"
        }

    def _request(self, method: str, url: str, data: Optional[bytes] = None, headers: Optional[Dict[str, str]] = None) -> Any:
        req_headers = headers if headers is not None else self._headers()
        req = urllib.request.Request(url, data=data, headers=req_headers, method=method)

        retries = 3
        backoff = 1.0

        for attempt in range(retries):
            try:
                with urllib.request.urlopen(req, timeout=self.timeout, context=self._ssl_context) as response:
                    content_type = response.headers.get("Content-Type", "")
                    body = response.read()
                    if "application/json" in content_type and body:
                        return json.loads(body.decode("utf-8"))
                    return body
            except urllib.error.HTTPError as e:
                err_body = ""
                try:
                    err_body = e.read().decode("utf-8")
                    err_json = json.loads(err_body)
                    msg = err_json.get("message", err_body)
                    error_code = err_json.get("error", "")
                except Exception:
                    msg = err_body or str(e)
                    error_code = ""

                # Handle rate limiting (429) or transient 5xx
                if e.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                    time.sleep(backoff)
                    backoff *= 2
                    continue

                raise YandexDiskAPIError(f"HTTP {e.code}: {msg}", status_code=e.code, error_code=error_code)
            except urllib.error.URLError as e:
                if attempt < retries - 1:
                    time.sleep(backoff)
                    backoff *= 2
                    continue
                raise YandexDiskAPIError(f"Ошибка сети: {e.reason}")
            except Exception as e:
                raise YandexDiskAPIError(f"Непредвиденная ошибка: {e}")

    def get_disk_info(self) -> Dict[str, Any]:
        """Returns disk info: total_space, used_space, trash_size, user info"""
        url = f"{self.BASE_URL}/"
        return self._request("GET", url)

    def check_connection(self) -> bool:
        """Validates OAuth token by calling disk info endpoint"""
        try:
            info = self.get_disk_info()
            return "total_space" in info
        except Exception:
            return False

    def normalize_remote_path(self, path: str) -> str:
        path = path.replace("\\", "/").strip()
        if not path.startswith("/"):
            path = "/" + path
        # Remove trailing slash unless root
        if len(path) > 1 and path.endswith("/"):
            path = path[:-1]
        return path

    def create_folder(self, remote_path: str) -> bool:
        """Creates directory on Yandex Disk. Creates parent folders as well."""
        clean_path = self.normalize_remote_path(remote_path)
        if clean_path in ("", "/"):
            return True

        parts = [p for p in clean_path.split("/") if p]
        curr = ""
        for part in parts:
            curr += "/" + part
            params = urllib.parse.urlencode({"path": curr})
            url = f"{self.BASE_URL}/resources?{params}"
            try:
                self._request("PUT", url)
            except YandexDiskAPIError as e:
                # 409 means resource already exists, which is fine
                if e.status_code == 409:
                    continue
                raise
        return True

    def get_resource_meta(self, remote_path: str) -> Optional[Dict[str, Any]]:
        """Gets metadata for single resource (file or folder)"""
        clean_path = self.normalize_remote_path(remote_path)
        params = urllib.parse.urlencode({"path": clean_path})
        url = f"{self.BASE_URL}/resources?{params}"
        try:
            return self._request("GET", url)
        except YandexDiskAPIError as e:
            if e.status_code == 404:
                return None
            raise

    def list_folder_recursive(self, remote_root: str, progress_callback: Optional[Callable[[str], None]] = None) -> Dict[str, Dict[str, Any]]:
        """Recursively list files on Yandex Disk with retry and rate‑limiting.

        The Yandex Disk API may reset connections during long listings or when many
        requests are made quickly. This method now performs each request with a simple
        exponential‑backoff retry (default 3 attempts) and adds a short pause between
        paginated calls to avoid hitting rate limits.
        """
        # Helper for retrying a single request
        def _request_with_retry(req_func):
            retries = 3
            backoff = 1.0
            for attempt in range(retries):
                try:
                    return req_func()
                except (urllib.error.URLError, urllib.error.HTTPError) as e:
                    if attempt < retries - 1:
                        time.sleep(backoff)
                        backoff *= 2
                        continue
                    raise
                except Exception as e:
                    # Unexpected errors – propagate after retries
                    if attempt < retries - 1:
                        time.sleep(backoff)
                        backoff *= 2
                        continue
                    raise
        
        clean_root = self.normalize_remote_path(remote_root)
        files_map: Dict[str, Dict[str, Any]] = {}
        queue = [clean_root]
        while queue:
            current_folder = queue.pop(0)
            if progress_callback:
                progress_callback(f"Чтение облачной папки: {current_folder}")
            offset = 0
            limit = 100
            while True:
                params = urllib.parse.urlencode({"path": current_folder, "limit": limit, "offset": offset})
                url = f"{self.BASE_URL}/resources?{params}"
                # Perform the request with retry logic
                data = _request_with_retry(lambda: self._request("GET", url))
                items = data.get("_embedded", {}).get("items", [])
                for item in items:
                    item_type = item.get("type")
                    item_path = item.get("path", "")
                    if item_path.startswith("disk:"):
                        item_path = item_path[5:]
                    item_path = self.normalize_remote_path(item_path)
                    if item_type == "dir":
                        queue.append(item_path)
                    elif item_type == "file":
                        rel = item_path[len(clean_root):].lstrip("/") if item_path.startswith(clean_root) else item.get("name", "")
                        files_map[rel] = {
                            "type": "file",
                            "name": item.get("name"),
                            "path": item_path,
                            "md5": item.get("md5", "").lower(),
                            "size": item.get("size", 0),
                            "modified": item.get("modified", ""),
                        }
                total = data.get("_embedded", {}).get("total", 0)
                offset += len(items)
                if offset >= total or not items:
                    break
                # Small pause to avoid flooding the API
                time.sleep(0.2)
        return files_map
        """
        Recursively lists all files in remote_root.
        Returns a dict: relative_path -> metadata (type, md5, size, modified, remote_full_path)
        """
        clean_root = self.normalize_remote_path(remote_root)
        files_map: Dict[str, Dict[str, Any]] = {}

        queue = [clean_root]
        while queue:
            current_folder = queue.pop(0)
            if progress_callback:
                progress_callback(f"Чтение облачной папки: {current_folder}")

            offset = 0
            limit = 100
            while True:
                params = urllib.parse.urlencode({"path": current_folder, "limit": limit, "offset": offset})
                url = f"{self.BASE_URL}/resources?{params}"
                try:
                    data = self._request("GET", url)
                except YandexDiskAPIError as e:
                    if e.status_code == 404:
                        # Folder doesn't exist yet
                        return {}
                    raise

                items = data.get("_embedded", {}).get("items", [])
                for item in items:
                    item_type = item.get("type")
                    item_path = item.get("path", "")
                    if item_path.startswith("disk:"):
                        item_path = item_path[5:]
                    item_path = self.normalize_remote_path(item_path)

                    if item_type == "dir":
                        queue.append(item_path)
                    elif item_type == "file":
                        # Calculate relative path from clean_root
                        if item_path.startswith(clean_root):
                            rel = item_path[len(clean_root):].lstrip("/")
                        else:
                            rel = item.get("name", "")
                        files_map[rel] = {
                            "type": "file",
                            "name": item.get("name"),
                            "path": item_path,
                            "md5": item.get("md5", "").lower(),
                            "size": item.get("size", 0),
                            "modified": item.get("modified", ""),
                        }

                total = data.get("_embedded", {}).get("total", 0)
                offset += len(items)
                if offset >= total or len(items) == 0:
                    break

        return files_map

    def upload_file(self, local_path: str, remote_path: str, overwrite: bool = True,
                    progress_callback: Optional[Callable[[int, int], None]] = None) -> bool:
        """
        Uploads local_path to remote_path on Yandex Disk.
        Supports chunked upload with progress callback.
        """
        clean_path = self.normalize_remote_path(remote_path)
        # Ensure parent remote directory exists
        parent_dir = "/".join(clean_path.split("/")[:-1])
        if parent_dir:
            self.create_folder(parent_dir)

        # 1. Get upload link
        params = urllib.parse.urlencode({"path": clean_path, "overwrite": "true" if overwrite else "false"})
        url = f"{self.BASE_URL}/resources/upload?{params}"
        res = self._request("GET", url)
        upload_href = res.get("href")
        if not upload_href:
            raise YandexDiskAPIError("Не удалось получить ссылку для загрузки.")

        file_size = os.path.getsize(local_path)

        # 2. Upload file via PUT
        with open(local_path, "rb") as f:
            class ProgressFileStream:
                def __init__(self, file_obj, total_size, cb):
                    self.file_obj = file_obj
                    self.total_size = total_size
                    self.cb = cb
                    self.bytes_read = 0

                def read(self, size=-1):
                    chunk = self.file_obj.read(size)
                    if chunk:
                        self.bytes_read += len(chunk)
                        if self.cb:
                            self.cb(self.bytes_read, self.total_size)
                    return chunk

                def __len__(self):
                    return self.total_size

            req = urllib.request.Request(
                upload_href,
                data=ProgressFileStream(f, file_size, progress_callback),
                headers={"Content-Length": str(file_size)},
                method="PUT"
            )
            with urllib.request.urlopen(req, timeout=300, context=self._ssl_context) as resp:
                if resp.status not in (200, 201, 202):
                    raise YandexDiskAPIError(f"Ошибка загрузки файла, код HTTP: {resp.status}")

        return True

    def download_file(self, remote_path: str, local_path: str,
                      progress_callback: Optional[Callable[[int, int], None]] = None) -> bool:
        """
        Downloads remote_path from Yandex Disk to local_path.
        """
        clean_path = self.normalize_remote_path(remote_path)
        params = urllib.parse.urlencode({"path": clean_path})
        url = f"{self.BASE_URL}/resources/download?{params}"
        res = self._request("GET", url)
        download_href = res.get("href")
        if not download_href:
            raise YandexDiskAPIError("Не удалось получить ссылку для скачивания.")

        # Ensure local directory exists
        os.makedirs(os.path.dirname(os.path.abspath(local_path)), exist_ok=True)

        temp_local_path = local_path + ".tmp_download"
        for attempt in range(3):
            try:
                req = urllib.request.Request(download_href, headers={"User-Agent": "YandexDiskSyncApp/1.0"})
                with urllib.request.urlopen(req, timeout=300, context=self._ssl_context) as response:
                    total_size = int(response.headers.get("Content-Length", 0))
                    bytes_downloaded = 0
                    chunk_size = 64 * 1024

                    with open(temp_local_path, "wb") as f:
                        while True:
                            chunk = response.read(chunk_size)
                            if not chunk:
                                break
                            f.write(chunk)
                            bytes_downloaded += len(chunk)
                            if progress_callback:
                                progress_callback(bytes_downloaded, total_size)

                if os.path.exists(local_path):
                    os.remove(local_path)
                os.rename(temp_local_path, local_path)
                return True
            except urllib.error.HTTPError as e:
                if os.path.exists(temp_local_path):
                    try:
                        os.remove(temp_local_path)
                    except Exception:
                        pass
                if e.code in (429, 500, 502, 503, 504) and attempt < 2:
                    time.sleep(2 ** attempt)
                    continue
                raise
            except Exception:
                if os.path.exists(temp_local_path):
                    try:
                        os.remove(temp_local_path)
                    except Exception:
                        pass
                if attempt < 2:
                    time.sleep(2 ** attempt)
                    continue
                raise
        return False

    def delete_resource(self, remote_path: str, permanently: bool = False) -> bool:
        """Deletes file or directory on Yandex Disk"""
        clean_path = self.normalize_remote_path(remote_path)
        params = urllib.parse.urlencode({"path": clean_path, "permanently": "true" if permanently else "false"})
        url = f"{self.BASE_URL}/resources?{params}"
        try:
            self._request("DELETE", url)
            return True
        except YandexDiskAPIError as e:
            if e.status_code == 404:
                return True
            raise
