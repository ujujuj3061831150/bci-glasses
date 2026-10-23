"""Export a private Android calibration directory without altering its bytes."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
from pathlib import Path


FILES = (
    "session.json",
    "raw_stream.f32",
    "packets.csv",
    "markers.csv",
    "trials.csv",
    "quality.csv",
    "frame_timing.csv",
    "checksums.sha256",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--package", default="com.bci.glasses")
    parser.add_argument("--session", required=True, help="Android internal files directory name")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=False)

    remote_base = f"files/{args.session}"
    for name in FILES:
        result = subprocess.run(
            [str(args.adb), "exec-out", "run-as", args.package, "cat", f"{remote_base}/{name}"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        (args.output_dir / name).write_bytes(result.stdout)

    expected = {}
    for line in (args.output_dir / "checksums.sha256").read_text(encoding="utf-8").splitlines():
        digest, name = line.split(maxsplit=1)
        expected[name.strip()] = digest
    mismatches = [name for name, digest in expected.items() if sha256(args.output_dir / name) != digest]
    if mismatches:
        raise RuntimeError(f"导出后校验和不匹配: {', '.join(mismatches)}")
    print(f"导出完成并通过校验: {args.output_dir}")


if __name__ == "__main__":
    main()
