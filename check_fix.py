text = open('d:/gemeni/sync/sync_engine.py', encoding='utf-8').read()
checks = [
    ('.bash_logout', 'IGNORED_NAMES += .bash_logout'),
    ('startswith(".sync_manifest")', '_is_ignored: .sync_manifest check'),
    ('skipped_files: list', 'skipped_files list init'),
    ('skipped_files.append(rel_path)', 'skipped_files.append in _do_download'),
    ('Не удалось синхронизировать файлов', 'end-of-sync report'),
    ('not k.lower().startswith(".sync_manifest")', 'manifest exclusion from local/remote'),
]
for needle, label in checks:
    status = 'OK     ' if needle in text else 'MISSING'
    print(status, label)
