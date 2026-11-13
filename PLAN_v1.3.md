cd D:\gemeni\sync
@'
# PLAN — YandexDiskSync v1.3.0

План следующей итерации. Предыдущий релиз — `v1.2.0` (тег `22df86b`, asset `YandexDiskSync.exe`, 13.45 MiB, SHA256 `6e0fae21…`).
Ветка: `master`. Репозиторий: `simonenkovs-ctrl/yandex-disk-sync` (private).

## 1. Исходное состояние (baseline после v1.2.0)

| Что сделано в v1.2.0 | Файл |
|---|---|
| Манифест v2: `version` + `timestamp` + TTL (`manifest_ttl_seconds=3600`) | `sync_engine.py`, `config.py` |
| Расширенные фильтры `.bak/.backup/.old/.temp/.partial/.download/.swo` | `sync_engine.py:IGNORED_EXTENSIONS` |
| Единый фильтр `_is_ignored` для local+remote | `sync_engine.py` |
| 404 без NameError, точечное обновление манифеста | `sync_engine.py:_do_download`, `_stale_remote_entries` |
| Восстановлен guard `if self.on_sync_completed` | `sync_engine.py` |
| Retry: один уровень в `_request`, учёт `Retry-After`, clamp 30s | `yandex_api.py` |
| Автозапуск: `_read_autostart_state()` из реестра, frozen/dev ветки | `gui.py` |
| CHANGELOG (ru) + README «Что нового» | `CHANGELOG.md`, `README.md` |
| `YandexDiskSync.spec` в репо | репозиторий |

Остаточные ограничения:
- Сборка на **Python 3.15 experimental** — возможны ложные срабатывания Defender, нестабильные хуки PyInstaller в будущем.
- Кеш манифеста только **remote** — при недоступном API полный scan невозможен, синхронизация падает.
- TTL — единственный триггер обновления манифеста; нельзя быстро проверить «изменилось ли что-то в облаке» без полного rebuild.
- Автозапуск не проверяет, что путь в реестре ведёт на существующий exe (если пользователь удалил `dist\` — запись висит битой).
- `.exe` не подписан — SmartScreen показывает «Неизвестный издатель».

## 2. Цели релиза v1.3.0

1. **Offline-fallback кеш манифеста** в `%LOCALAPPDATA%\YandexDiskSync\cache\manifest.json` — при недоступном API работать по последнему успешному манифесту.
2. **Инкрементальный probe** — 1 лёгкий запрос вместо TTL-полного rebuild; полный scan только при расхождении отпечатка.
3. **Сборка на Python 3.12 LTS** — стабильнее с PyInstaller, меньше ложных срабатываний Defender.
4. **Health-check автозапуска** при старте: если путь в реестре битый — переписать на актуальный `sys.executable` или удалить запись.
5. **Подпись `.exe`** — самоподписанный сертификат или Let's Encrypt code signing (опционально, см. раздел 7 «Риски»).

Вне scope v1.3.0 (кандидаты на v1.4.0):
- Инкрементальная синхронизация без полного scan (журнал изменений).
- Дельта-загрузка файлов (`PATCH` / chunked upload вместо `PUT` целого файла).
- WebDAV/облачный провайдер кроме Яндекс.Диска.

## 3. Задачи

### D1. Offline-fallback кеш манифеста (`sync_engine.py` + новый `cache.py`)
- [ ] Добавить модуль `cache.py` с функциями `load()`, `save()`, `invalidate()`.
- [ ] Путь кеша: `%LOCALAPPDATA%\YandexDiskSync\cache\manifest.json` (на Linux/macOS — `~/.cache/yandex-disk-sync/`).
- [ ] Формат: `{"version": 2, "timestamp": ..., "files": {...}}` — совместим с remote-манифестом.
- [ ] В `perform_sync`: при исключении `list_folder_recursive` — попытаться загрузить локальный кеш. Если он есть и возраст < `cache_ttl_seconds` (новый ключ, default 86400 = 24ч) — работать по нему, в логе `«Использован offline-кеш (возраст X мин)»`.
- [ ] При успешном полном scan — перезаписать локальный кеш.
- [ ] Атомарная запись (tmp + `os.replace`), как в remote-манифесте.
- [ ] Ключ `cache_ttl_seconds` в `config.py:DEFAULT_CONFIG`, значение 86400.

### D2. Инкрементальный probe (`sync_engine.py` + `yandex_api.py`)
- [ ] В `YandexDiskClient` добавить метод `probe_folder(root_path) -> str` — 1 запрос `GET /v1/disk/resources?path={root}&limit=1&sort=-modified`, вернуть `sha256(f"{count}|{first.modified}|{first.md5}")`.
- [ ] В `perform_sync` перед `list_folder_recursive`: вызвать `probe_folder`; сравнить с `manifest["probe"]`. Если совпал и возраст манифеста < `manifest_ttl_seconds` — использовать кеш без полного scan.
- [ ] Если probe-запрос упал (сеть/5xx) — fallback на TTL-логику v1.2.0 (не блокировать).
- [ ] В манифест добавить поле `probe`, при записи — заполнять.

### D3. Health-check автозапуска (`gui.py`)
- [ ] В `_read_autostart_state()` при `True` — дополнительно `winreg.QueryValueEx` и проверить, что путь извлекается (`shlex.split`/регулярка) и `os.path.exists(exe_path)`.
- [ ] Если путь битый — в логе `«Автозапуск указывает на несуществующий файл, требуется переприменение»`, вернуть `False` (чекбокс снимется).
- [ ] В `_apply_autostart(True)` — если старая запись была битой, перезаписать без предупреждения (не спрашивать пользователя).
- [ ] Добавить тест: вручную создать в реестре запись на несуществующий путь, запустить GUI — чекбокс должен быть `False`, после «Сохранить» — перезаписан на корректный.

### D4. Python 3.12 LTS для сборки (окружение + `.spec`)
- [ ] Установить **Python 3.12.10** (https://www.python.org/downloads/release/python-31210/), Windows installer 64-bit, «Add to PATH» выключен (использовать `py -3.12`).
- [ ] `py -3.12 -m pip install --upgrade pip pyinstaller pyinstaller-hooks-contrib`.
- [ ] Пересобрать `.spec` под 3.12: `py -3.12 -m PyInstaller --onefile --noconsole --name YandexDiskSync --icon app.ico --add-data "app.ico;." --hidden-import ctypes.wintypes --clean main.py`.
- [ ] Проверить размер exe (ожидается 13–16 MB) и работу (smoke + end-to-end как в v1.2.0).
- [ ] Обновить `README.md`: «Сборка — Python 3.12 LTS».

### D5. Документация (`CHANGELOG.md`, `README.md`)
- [ ] `CHANGELOG.md` → раздел `## [1.3.0] — YYYY-MM-DD` с блоками «Добавлено / Исправлено / Изменено».
- [ ] `README.md` → «Что нового в v1.3.0» + строка про `cache_ttl_seconds` в таблице настроек.
- [ ] `README.md` → раздел «Сборка из исходников» с `py -3.12 -m PyInstaller YandexDiskSync.spec`.

## 4. План тестирования

### 4.1. Offline-fallback (D1)
1. Запустить синхронизацию, дождаться успешного цикла. Проверить: `%LOCALAPPDATA%\YandexDiskSync\cache\manifest.json` создан.
2. Отключить интернет (или подменить `BASE_URL` на несуществующий хост).
3. «Синхронизировать сейчас» → в логе `«Использован offline-кеш (возраст X мин)»`, файлы синхронизируются по нему.
4. Подождать > `cache_ttl_seconds` (для теста — 60 сек), повторить → в логе `«offline-кеш устарел, синхронизация невозможна»`.

### 4.2. Инкрементальный probe (D2)
1. Первый цикл: полный scan, манифест записан с `probe`.
2. Второй цикл (через 5 сек, файлы не менялись): в логе `«probe совпал, кеш актуален»`, полного scan нет (по логу `Файлов найдено`).
3. Изменить 1 файл в облаке через веб → третий цикл: `probe` другой → полный rebuild, изменение подхвачено.

### 4.3. Health-check автозапуска (D3)
1. Включить автозапуск в GUI. Проверить реестр — корректный путь.
2. Переименовать `dist\YandexDiskSync.exe` → `dist\YandexDiskSync.exe.bak`.
3. Перезапустить GUI → чекбокс `False`, в логе предупреждение.
4. Вернуть exe, «Сохранить» → реестр перезаписан.
5. Удалить ключ вручную, перезапустить → чекбокс `False` (не падает).

### 4.4. Сборка на Python 3.12 (D4)
- [ ] `py -3.12 -m PyInstaller YandexDiskSync.spec` — без warning про unsupported Python.
- [ ] Размер `dist\YandexDiskSync.exe` 13–16 MB.
- [ ] Smoke: запуск, окно, трей, токен, автозапуск, синк.
- [ ] End-to-end: скачать из релиза в чистую папку — работает.

### 4.5. Регрессия v1.2.0
- [ ] `.bak` не синхронизируется.
- [ ] 404 при скачивании не валит цикл.
- [ ] Retry при 5xx (`Retry-After`) — не ломается.
- [ ] `manifest_ttl_seconds=60` → через минуту rebuild.

## 5. Релиз

```powershell
cd D:\gemeni\sync
git add CHANGELOG.md README.md cache.py sync_engine.py yandex_api.py gui.py config.py YandexDiskSync.spec PLAN_v1.3.md
git commit -m "feat(v1.3.0): offline cache, incremental probe, autostart health-check, Python 3.12 build"
git tag -a v1.3.0 -m "v1.3.0: offline cache, incremental probe, autostart health-check, Python 3.12 LTS build"
git push origin master
git push origin v1.3.0

gh release create v1.3.0 .\dist\YandexDiskSync.exe `
  --title "v1.3.0 — offline-кеш, инкрементальный probe, health-check автозапуска, Python 3.12 LTS" `
  --notes-file CHANGELOG.md `
  --repo simonenkovs-ctrl/yandex-disk-sync `
  --latest