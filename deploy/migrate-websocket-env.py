#!/usr/bin/env python3
"""Upgrade an existing deployment's WebSocket settings without exposing secrets."""
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path


def migrate(path):
    path = Path(path).resolve(strict=True)
    original = path.read_text(encoding="utf-8")
    settings = {
        "WEBSOCKET_AUTOMATIC_REPAIR_ENABLED": "true",
        "WEBSOCKET_AUTOMATIC_REPAIR_MIN_HOURS": "2",
        "WEBSOCKET_AUTOMATIC_REPAIR_MAX_HOURS": "4",
        "WEBSOCKET_AUTOMATIC_REPAIR_ACCOUNT_SPACING_MINUTES": "15",
    }
    if not re.search(r"(?m)^WEBSOCKET_SCHEDULER_POOL_SIZE=\S+", original):
        settings["WEBSOCKET_SCHEDULER_POOL_SIZE"] = "16"
    updated = original
    for key, value in settings.items():
        pattern = r"(?m)^" + key + r"=.*$"
        if re.search(pattern, updated):
            updated = re.sub(pattern, key + "=" + value, updated)
        else:
            updated = updated.rstrip("\n") + "\n" + key + "=" + value + "\n"
    if updated == original:
        return False
    # Keep a private backup and atomically replace the existing .env.
    fd, backup = tempfile.mkstemp(prefix=".env.websocket-backup-", dir=path.parent)
    os.close(fd)
    shutil.copyfile(path, backup)
    fd, staged = tempfile.mkstemp(prefix=".env.websocket-staged-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
            output.write(updated)
        if hasattr(os, "chown"):
            stat = path.stat()
            os.chown(staged, stat.st_uid, stat.st_gid)
        os.replace(staged, path)
    finally:
        if os.path.exists(staged):
            os.unlink(staged)
    print("WebSocket settings migrated; private backup: " + str(backup))
    return True


if __name__ == "__main__":
    migrate(sys.argv[1] if len(sys.argv) > 1 else ".env")
