import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("migration", Path(__file__).parents[1] / "migrate-websocket-env.py")
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


class MigrationTest(unittest.TestCase):
    def test_preserves_secrets_and_custom_pool_backs_up_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            original = "DB_PASSWORD='private=a#b'\nWEBSOCKET_AUTOMATIC_REPAIR_ENABLED=false\nWEBSOCKET_SCHEDULER_POOL_SIZE=32\n"
            path.write_text(original, encoding="utf-8")
            self.assertTrue(migration.migrate(path))
            updated = path.read_text(encoding="utf-8")
            self.assertIn("DB_PASSWORD='private=a#b'", updated)
            self.assertIn("WEBSOCKET_AUTOMATIC_REPAIR_ENABLED=true", updated)
            self.assertIn("WEBSOCKET_SCHEDULER_POOL_SIZE=32", updated)
            self.assertIn("WEBSOCKET_AUTOMATIC_REPAIR_MIN_HOURS=2", updated)
            self.assertIn("WEBSOCKET_AUTOMATIC_REPAIR_MAX_HOURS=4", updated)
            self.assertIn("WEBSOCKET_AUTOMATIC_REPAIR_ACCOUNT_SPACING_MINUTES=15", updated)
            backups = list(Path(directory).glob(".env.websocket-backup-*"))
            self.assertEqual(1, len(backups))
            self.assertEqual(original, backups[0].read_text(encoding="utf-8"))
            self.assertFalse(migration.migrate(path))
            self.assertEqual(1, len(list(Path(directory).glob(".env.websocket-backup-*"))))

    def test_adds_default_pool_but_does_not_create_fake_deployment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            with self.assertRaises(FileNotFoundError):
                migration.migrate(path)
            path.write_text("DB_NAME=test\n", encoding="utf-8")
            migration.migrate(path)
            self.assertIn("WEBSOCKET_SCHEDULER_POOL_SIZE=16", path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
