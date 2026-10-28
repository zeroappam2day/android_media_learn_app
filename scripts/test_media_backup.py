"""Checks for fail-closed backup selection, SQLite/WAL and byte verification."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import media_backup as subject

PACKAGE = "com.aistudio.videoknowledgesorter.fkvbwy.preview"
URI = "content://media/external/images/media/7"
PAYLOAD = b"actual-test-media-bytes"


def create_database(path, wal=False):
    connection = sqlite3.connect(path)
    if wal:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA wal_autocheckpoint=0")
    connection.execute("CREATE TABLE video_items (id INTEGER PRIMARY KEY, uriString TEXT, displayName TEXT, isCurrentlyAvailable INTEGER, sizeBytes INTEGER)")
    connection.execute("CREATE TABLE analysis_results (id INTEGER PRIMARY KEY, videoId INTEGER, descriptionLine1 TEXT)")
    connection.execute("INSERT INTO video_items VALUES (7, ?, '../../private.png', 1, ?)", (URI, len(PAYLOAD)))
    connection.execute("INSERT INTO analysis_results VALUES (1, 7, 'Real saved analysis')")
    connection.commit()
    return connection


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.database = self.root / "source.db"
        create_database(self.database).close()

    def test_metadata_reads_saved_analysis_and_wal_content(self):
        database = self.root / "wal.db"
        connection = create_database(database, wal=True)
        self.addCleanup(connection.close)
        snapshot = self.root / "wal-copy.db"
        shutil.copyfile(database, snapshot)
        shutil.copyfile(str(database) + "-wal", str(snapshot) + "-wal")
        metadata = subject._read_metadata(snapshot)
        self.assertEqual(URI, metadata["media"][0]["uriString"])
        self.assertEqual("Real saved analysis", metadata["media"][0]["analysis"][0]["descriptionLine1"])

    def test_invalid_schema_and_corrupt_sqlite_fail(self):
        empty = self.root / "empty.db"
        sqlite3.connect(empty).close()
        with self.assertRaises(subject.BackupError):
            subject._read_metadata(empty)
        bad = self.root / "bad.db"
        bad.write_bytes(b"not a database")
        with self.assertRaises(subject.BackupError):
            subject._read_metadata(bad)

    def test_unindexed_unavailable_and_non_media_uris_fail(self):
        metadata = subject._read_metadata(self.database)
        for selected in ([], ["content://media/external/images/media/999"]):
            with self.assertRaises(subject.BackupError):
                subject._choose_media(metadata, selected)
        metadata["media"][0]["isCurrentlyAvailable"] = 0
        with self.assertRaises(subject.BackupError):
            subject._choose_media(metadata, [URI])
        metadata["media"][0].update(isCurrentlyAvailable=1, uriString="content://other.provider/private")
        with self.assertRaises(subject.BackupError):
            subject._choose_media(metadata, None)

    def test_serial_and_package_validation_precedes_destination_creation(self):
        for serial, package in (("", PACKAGE), ("--all", PACKAGE), ("emulator-5554;echo", PACKAGE), ("emulator-5554", "com.production")):
            destination = self.root / "invalid"
            with self.assertRaises(subject.BackupError):
                subject.backup("adb", serial, package, destination)
            self.assertFalse(destination.exists())

    def test_existing_destination_is_never_overwritten(self):
        destination = self.root / "existing"
        destination.mkdir()
        original = destination / "important.txt"
        original.write_text("preserve me")
        with self.assertRaises(subject.BackupError):
            subject.backup("adb", "emulator-5554", PACKAGE, destination)
        self.assertEqual("preserve me", original.read_text())

    def run_backup(self, payloads):
        calls = []
        def copy(adb, serial, args, destination):
            calls.append(args)
            data = payloads[len(calls) - 1]
            if destination:
                destination.write_bytes(data)
            return hashlib.sha256(data).hexdigest(), len(data)
        destination = self.root / "result"
        with patch.object(subject, "_snapshot", return_value=self.database), patch.object(subject, "_copy_stream", side_effect=copy):
            result = subject.backup("adb", "emulator-5554", PACKAGE, destination, [URI])
        return destination, result, calls

    def test_verified_backup_has_real_bytes_metadata_safe_paths_and_matching_hashes(self):
        original_digest = subject._file_digest(self.database)
        destination, receipt, calls = self.run_backup([PAYLOAD, PAYLOAD])
        self.assertEqual("PASS", receipt["status"])
        self.assertEqual(2, len(calls))
        self.assertEqual(["content", "read", "--uri", URI], calls[0])
        self.assertEqual(1, receipt["copied_media"])
        copied = destination / receipt["files"][0]["path"]
        self.assertEqual(PAYLOAD, copied.read_bytes())
        self.assertEqual(destination / "media", copied.parent)
        self.assertEqual(receipt["files"][0]["source_sha256"], receipt["files"][0]["destination_sha256"])
        self.assertEqual("Real saved analysis", json.loads((destination / "metadata.json").read_text())["media"][0]["analysis"][0]["descriptionLine1"])
        self.assertEqual(original_digest, subject._file_digest(self.database))

    def test_changed_source_leaves_failed_receipt(self):
        destination = self.root / "result"
        with self.assertRaises(subject.BackupError):
            self.run_backup([PAYLOAD, PAYLOAD + b"changed"])
        receipt = json.loads((destination / "manifest.json").read_text())
        self.assertEqual("FAILED", receipt["status"])
        self.assertIn("verification failed", receipt["error"])

    def test_indexed_size_mismatch_leaves_failed_receipt(self):
        with self.assertRaises(subject.BackupError):
            self.run_backup([b"wrong size", b"wrong size"])
        receipt = json.loads((self.root / "result" / "manifest.json").read_text())
        self.assertEqual("FAILED", receipt["status"])
        self.assertIn("byte size changed", receipt["error"])

    def test_empty_media_never_counts_as_a_verified_transfer(self):
        with self.assertRaises(subject.BackupError):
            self.run_backup([b"", b""])
        self.assertEqual("FAILED", json.loads((self.root / "result" / "manifest.json").read_text())["status"])


if __name__ == "__main__":
    unittest.main()
