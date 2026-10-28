"""Copy a quiescent preview app's indexed media and metadata to the computer.

The caller must verify the runtime and force-stop the preview app before calling
backup(). This module never starts/stops apps, deletes media or changes grants.
"""
from __future__ import annotations

from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import threading
from typing import Iterable

DATABASE = "video_knowledge_sorter_db"
URI_PATTERN = re.compile(r"content://media/(?:external|external_primary)/(?:images|video)/media/[0-9]+")


class BackupError(RuntimeError):
    """A backup is incomplete and must not be accepted."""


def _validate_identity(serial: str, package: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", serial) or serial.startswith("-"):
        raise BackupError("An explicit valid ADB serial is required")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\.preview", package):
        raise BackupError("Only a preview package may be backed up by this workflow")


def _adb(adb_path: str, serial: str, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run([adb_path, "-s", serial, *args], capture_output=True, timeout=60)
    if check and result.returncode:
        raise BackupError(f"ADB failed ({result.returncode}): {result.stderr.decode(errors='replace')[:500]}")
    return result


def _copy_stream(adb_path: str, serial: str, args: list[str], destination: Path | None) -> tuple[str, int]:
    digest = hashlib.sha256()
    count = 0
    first = b""
    with subprocess.Popen([adb_path, "-s", serial, "exec-out", *args], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE) as process:
        output = destination.open("xb") if destination is not None else None
        timer = threading.Timer(300, process.kill)
        timer.start()
        try:
            assert process.stdout is not None
            while chunk := process.stdout.read(1024 * 1024):
                if not first:
                    first = chunk[:160]
                digest.update(chunk)
                count += len(chunk)
                if output:
                    output.write(chunk)
            stderr = process.stderr.read() if process.stderr else b""
            code = process.wait(timeout=300)
            if code or first.startswith((b"Error while", b"java.lang.", b"Exception occurred")):
                raise BackupError(f"Device read failed ({code}): {stderr.decode(errors='replace')[:500]}")
        finally:
            timer.cancel()
            if output:
                output.close()
    return digest.hexdigest(), count


def _snapshot(adb_path: str, serial: str, package: str, root: Path) -> Path:
    pid = _adb(adb_path, serial, "shell", "pidof", package, check=False)
    if pid.stdout.strip():
        raise BackupError("The preview app must be force-stopped before taking its database snapshot")
    if pid.returncode not in (0, 1):
        raise BackupError("Could not verify that the preview app is stopped")
    raw = root / "database-snapshot"
    raw.mkdir(mode=0o700)
    main = raw / DATABASE
    _copy_stream(adb_path, serial, ["run-as", package, "cat", f"databases/{DATABASE}"], main)
    with main.open("rb") as stream:
        header = stream.read(16)
    if header != b"SQLite format 3\x00":
        raise BackupError("App database snapshot is not a SQLite database")
    wal_path = f"databases/{DATABASE}-wal"
    wal = _adb(adb_path, serial, "exec-out", "run-as", package, "ls", wal_path, check=False)
    if wal.returncode == 0:
        _copy_stream(adb_path, serial, ["run-as", package, "cat", wal_path], raw / f"{DATABASE}-wal")
    elif b"No such file" not in wal.stderr + wal.stdout:
        raise BackupError("Could not determine whether the database WAL is present")
    return main


def _read_metadata(database: Path) -> dict:
    try:
        with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as connection:
            connection.row_factory = sqlite3.Row
            integrity = connection.execute("PRAGMA quick_check").fetchone()[0]
            if integrity != "ok":
                raise BackupError(f"Database integrity check failed: {integrity}")
            media = [dict(row) for row in connection.execute("SELECT * FROM video_items ORDER BY id")]
            analyses = [dict(row) for row in connection.execute("SELECT * FROM analysis_results ORDER BY id")]
            required = {"id", "uriString", "displayName", "isCurrentlyAvailable"}
            columns = {row[1] for row in connection.execute("PRAGMA table_info(video_items)")}
            if not required <= columns:
                raise BackupError("Unsupported media schema; required columns are missing")
            by_media: dict[int, list[dict]] = {}
            for item in analyses:
                by_media.setdefault(item["videoId"], []).append(item)
            for item in media:
                item["analysis"] = by_media.get(item["id"], [])
            return {"schema": 1, "media": media}
    except sqlite3.Error as error:
        raise BackupError(f"Could not read the media database: {error}") from error


def _choose_media(metadata: dict, selected_uris: Iterable[str] | None) -> list[dict]:
    available = [item for item in metadata["media"] if item["isCurrentlyAvailable"] and not item.get("isPending")
                 and not item.get("isTrashed")]
    if selected_uris is None:
        chosen = available
    else:
        requested = set(selected_uris)
        if not requested:
            raise BackupError("Select at least one indexed, available media URI")
        chosen = [item for item in available if item["uriString"] in requested]
        if {item["uriString"] for item in chosen} != requested:
            raise BackupError("Every selected URI must belong to indexed, available media")
    if not chosen:
        raise BackupError("No available media exists to prove a real media transfer")
    seen = set()
    result = []
    for item in chosen:
        uri = item["uriString"]
        if not URI_PATTERN.fullmatch(uri):
            raise BackupError(f"Unsupported MediaStore URI: {uri}")
        if uri not in seen:
            seen.add(uri)
            result.append(item)
    return result


def _file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _local_name(item: dict) -> str:
    filename = re.sub(r"[^A-Za-z0-9._-]", "_", str(item["displayName"]))[:160].strip(".") or "media"
    identity = str(item["id"])
    if not re.fullmatch(r"[0-9]+", identity):
        raise BackupError("Media identity must be numeric")
    return f"{identity}-{filename}"


def backup(adb_path: str, serial: str, package: str, destination: str | Path,
           selected_uris: Iterable[str] | None = None) -> dict:
    """Return a verified manifest; raise BackupError for any incomplete transfer.

    destination must not exist. A failure leaves inspection artifacts and a
    FAILED receipt, never a successful receipt. Source files remain untouched.
    Metadata includes the complete media inventory; media bytes include only
    currently available entries, or the explicitly selected available entries.
    """
    _validate_identity(serial, package)
    root = Path(destination)
    try:
        root.mkdir(mode=0o700, parents=False, exist_ok=False)
    except OSError as error:
        raise BackupError(f"A new backup destination is required: {error}") from error
    manifest = {"schema": 1, "status": "IN_PROGRESS", "serial": serial, "package": package,
                "source_preserved": True, "files": []}
    receipt = root / "manifest.json"
    try:
        database = _snapshot(adb_path, serial, package, root)
        metadata = _read_metadata(database)
        selected = _choose_media(metadata, selected_uris)
        metadata_path = root / "metadata.json"
        metadata_path.write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n")
        files = root / "media"
        files.mkdir(mode=0o700)
        for item in selected:
            uri = item["uriString"]
            path = files / _local_name(item)
            source_digest, size = _copy_stream(adb_path, serial, ["content", "read", "--uri", uri], path)
            independent_digest, independent_size = _copy_stream(adb_path, serial, ["content", "read", "--uri", uri], None)
            destination_digest = _file_digest(path)
            if not size or source_digest != independent_digest or source_digest != destination_digest or size != independent_size:
                raise BackupError(f"Source/destination verification failed for {uri}")
            expected_size = item.get("sizeBytes")
            if expected_size and size != expected_size:
                raise BackupError(f"Media byte size changed for {uri}: indexed={expected_size}, copied={size}")
            manifest["files"].append({"uri": uri, "path": str(path.relative_to(root)), "bytes": size,
                                      "source_sha256": independent_digest, "destination_sha256": destination_digest})
        manifest.update(status="PASS", metadata_sha256=_file_digest(metadata_path),
                        metadata_items=len(metadata["media"]), copied_media=len(manifest["files"]))
        receipt.write_text(json.dumps(manifest, indent=2) + "\n")
        return manifest
    except Exception as error:
        manifest.update(status="FAILED", error=str(error))
        receipt.write_text(json.dumps(manifest, indent=2) + "\n")
        if isinstance(error, BackupError):
            raise
        raise BackupError(str(error)) from error
