path = r'd:\gemeni\sync\sync_engine.py'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

results = []

# 1. Add .bash_logout to IGNORED_NAMES
old = '"sync_state.db", "config.json"\n}'
new = '"sync_state.db", "config.json", ".bash_logout"\n}'
if old in text:
    text = text.replace(old, new)
    results.append('OK  1: .bash_logout added to IGNORED_NAMES')
else:
    results.append('SKIP 1: .bash_logout already present or pattern mismatch')

# 2. Add .sync_manifest filter in _is_ignored
old = '            if part_lower.startswith(".tmp_"):\n                return True\n'
new = ('            if part_lower.startswith(".tmp_"):\n'
       '                return True\n'
       '            # Ignore sync manifest files and their conflict copies\n'
       '            if part_lower.startswith(".sync_manifest"):\n'
       '                return True\n')
if old in text and 'startswith(".sync_manifest")' not in text:
    text = text.replace(old, new)
    results.append('OK  2: .sync_manifest filter added to _is_ignored')
else:
    results.append('SKIP 2: already present or pattern mismatch')

# 3. Exclude manifest files from local/remote after count log
old = ('            self.log("INFO", f"Файлов найдено: на ПК — {len(local_files)} (за {t_scan:.1f}с), в облаке — {len(remote_files)}")\n'
       '\n'
       '            db_records = self.db.get_all_records()')
new = ('            self.log("INFO", f"Файлов найдено: на ПК — {len(local_files)} (за {t_scan:.1f}с), в облаке — {len(remote_files)}")\n'
       '\n'
       '            # Exclude service manifest files from sync to prevent conflicts\n'
       '            local_files = {k: v for k, v in local_files.items() if not k.lower().startswith(".sync_manifest")}\n'
       '            remote_files = {k: v for k, v in remote_files.items() if not k.lower().startswith(".sync_manifest")}\n'
       '\n'
       '            db_records = self.db.get_all_records()')
if old in text:
    text = text.replace(old, new)
    results.append('OK  3: manifest exclusion added after scan')
else:
    results.append('SKIP 3: already present or pattern mismatch')

# 4. Add skipped_files list after stats init
old = ('            stats = {"uploaded": 0, "downloaded": 0, "deleted": 0, "conflicts": 0}\n'
       '            self._manifest_invalid = False')
new = ('            stats = {"uploaded": 0, "downloaded": 0, "deleted": 0, "conflicts": 0}\n'
       '            skipped_files: list = []  # files that failed to sync\n'
       '            self._manifest_invalid = False')
if old in text and 'skipped_files' not in text:
    text = text.replace(old, new)
    results.append('OK  4: skipped_files list init added')
else:
    results.append('SKIP 4: already present or pattern mismatch')

# 5. Append to skipped_files in _do_download on WARN
old = ('            self.log("WARN", f"\u26a0 \u041f\u0440\u043e\u043f\u0443\u0441\u043a \u0444\u0430\u0439\u043b\u0430 {rel_path}: {e}")\n'
       '            return')
new = ('            self.log("WARN", f"\u26a0 \u041f\u0440\u043e\u043f\u0443\u0441\u043a \u0444\u0430\u0439\u043b\u0430 {rel_path}: {e}")\n'
       '            skipped_files.append(rel_path)\n'
       '            return')
if old in text:
    text = text.replace(old, new)
    results.append('OK  5: skipped_files.append added in _do_download')
else:
    results.append('SKIP 5: already present or pattern mismatch')

# 6. Print skipped_files report before on_sync_completed
old = ('            if self.on_sync_completed:\n'
       '                self.on_sync_completed(stats)')
new = ('            # Report files that could not be synced\n'
       '            if skipped_files:\n'
       '                self.log("WARN", f"\u26a0 \u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0441\u0438\u043d\u0445\u0440\u043e\u043d\u0438\u0437\u0438\u0440\u043e\u0432\u0430\u0442\u044c \u0444\u0430\u0439\u043b\u043e\u0432: {len(skipped_files)}")\n'
       '                for sf in skipped_files:\n'
       '                    self.log("WARN", f"  \u2014 {sf}")\n'
       '\n'
       '            if self.on_sync_completed:\n'
       '                self.on_sync_completed(stats)')
if old in text and 'skipped_files' in text:
    text = text.replace(old, new, 1)
    results.append('OK  6: skipped_files end-of-sync report added')
else:
    results.append('SKIP 6: already present or pattern mismatch')

with open(path, 'w', encoding='utf-8') as f:
    f.write(text)

for r in results:
    print(r)
print('\nDone.')
