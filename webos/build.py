#!/usr/bin/env python3
"""Test, sestavení a kontrola distribučního balíčku LG bez knihoven navíc."""
import hashlib
import gzip
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
# webOS 5.x odmítá TAR s rokem 1970; pevné datum zachová reprodukovatelnost.
IPK_TIMESTAMP = 946684800  # 2000-01-01 UTC


def normalize_ipk(path):
    """Sjednotí časy a UID/GID bez neplatně starých dat pro webOS 5.x."""
    data = path.read_bytes()
    if data[:8] != b"!<arch>\n":
        raise ValueError("Neplatný IPK archiv")
    result = bytearray(data[:8])
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        size = int(header[48:58])
        payload = data[offset + 60:offset + 60 + size]
        name = header[:16].decode("ascii").strip().rstrip("/")
        if name.endswith(".tar.gz"):
            output = io.BytesIO()
            with tarfile.open(fileobj=io.BytesIO(payload)) as source, tarfile.open(fileobj=output, mode="w", format=tarfile.PAX_FORMAT) as target:
                for member in sorted(source.getmembers(), key=lambda item: item.name):
                    member.mtime = IPK_TIMESTAMP
                    member.uid = member.gid = 0
                    member.uname = member.gname = ""
                    member.pax_headers = {}
                    target.addfile(member, source.extractfile(member) if member.isfile() else None)
            payload = bytearray(gzip.compress(output.getvalue(), compresslevel=9, mtime=IPK_TIMESTAMP))
            payload[9] = 255
        result.extend((name.ljust(16) + str(IPK_TIMESTAMP).ljust(12) + "0".ljust(6) + "0".ljust(6) +
                       "100644".ljust(8) + str(len(payload)).ljust(10) + "`\n").encode("ascii"))
        result.extend(payload)
        if len(payload) % 2:
            result.extend(b"\n")
        offset += 60 + size + size % 2
    path.write_bytes(result)


def verify_ipk(path, expected):
    data = path.read_bytes()
    if data[:8] != b"!<arch>\n":
        raise ValueError("Neplatný IPK archiv")
    offset = 8
    found = {}
    while offset < len(data):
        header = data[offset:offset + 60]
        size = int(header[48:58])
        archive_name = header[:16].decode("ascii").strip().rstrip("/")
        if archive_name in ("control.tar.gz", "data.tar.gz"):
            with tarfile.open(fileobj=io.BytesIO(data[offset + 60:offset + 60 + size])) as archive:
                for item in archive.getmembers():
                    if item.mtime < IPK_TIMESTAMP:
                        raise ValueError("Příliš starý čas v IPK: " + archive_name + "/" + item.name)
                    if archive_name == "data.tar.gz" and item.isfile():
                        name = item.name.removeprefix("./")
                        if name in found:
                            raise ValueError("Duplicitní soubor v IPK: " + name)
                        found[name] = archive.extractfile(item).read()
        offset += 60 + size + size % 2
    metadata = "usr/palm/packages/cz.playcaption.overlaytest/packageinfo.json"
    package = json.loads(found.pop(metadata))
    if package["version"] != json.loads(expected["usr/palm/applications/cz.playcaption.overlaytest/appinfo.json"])["version"]:
        raise ValueError("Nesouhlasí verze IPK")
    if found != expected:
        raise ValueError("Obsah IPK neodpovídá zdrojům nebo obsahuje soubory navíc")


def main():
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(HERE), "-p", "test_build.py"], check=True)
    app = json.loads((HERE / "app/appinfo.json").read_text(encoding="utf8"))
    service = json.loads((HERE / "service/package.json").read_text(encoding="utf8"))
    assert app["version"] == service["version"], "Nesouhlasí verze UI a služby"
    assert service["name"] == app["id"] + ".receiver", "Nesouhlasí ID služby"
    for test in ["test.cjs", "test-udp.cjs", "test-input.cjs", "test-receiver.cjs", "test-controls.cjs", "test-story.cjs", "test-recovery.cjs"]:
        subprocess.run(["node", test], cwd=HERE, check=True)
    for source in ["app/app.js", "app/receiver-ui.js", "service/protocol.js", "service/service.js", "service/switch-input.js"]:
        subprocess.run(["node", "--check", source], cwd=HERE, check=True)
    (HERE / "dist").mkdir(exist_ok=True)
    # Pevný příkaz bez interpolace vstupu; shell podporuje také npx.cmd na Windows.
    subprocess.run("npx --yes --package @webos-tools/cli@3.2.6 ares-package --no-minify app service --outdir dist",
                   shell=True, cwd=HERE, check=True)
    ipk = HERE / "dist" / (app["id"] + "_" + app["version"] + "_all.ipk")
    normalize_ipk(ipk)
    expected = {}
    for directory, prefix in [("app", "applications/" + app["id"]), ("service", "services/" + service["name"])]:
        for source in (HERE / directory).iterdir():
            if not source.is_file():
                raise ValueError("Neočekávaný podadresář: " + str(source))
            expected["usr/palm/" + prefix + "/" + source.name] = source.read_bytes()
    verify_ipk(ipk, expected)
    manifest = {"schema": 1, "platform": "webos", "id": app["id"], "version": app["version"],
                "file": ipk.name, "size": ipk.stat().st_size, "sha256": hashlib.sha256(ipk.read_bytes()).hexdigest(),
                "protocols": ["PCT1", "PCV1", "PCO1", "PCQ1", "PCS1", "PCA1"], "story": True,
                "capabilities": ["subtitles", "hint", "layout", "speaker-colors", "choices", "text",
                                 "choice-frame", "ocr-frame", "controls", "local-test", "sender-lease", "story-pages", "story-ack", "story-timing", "direct-discovery"]}
    manifest_data = (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf8")
    (HERE / "dist/receiver-manifest.json").write_bytes(manifest_data)
    files = {ipk.name: ipk.read_bytes(), "LICENSE": (ROOT / "LICENSE").read_bytes()}
    files["receiver-manifest.json"] = manifest_data
    files["NAVOD-LG.md"] = (HERE / "NAVOD-LG.md").read_bytes()
    files["SHODA-ANDROID.md"] = (HERE / "SHODA-ANDROID.md").read_bytes()
    files["INSTALACE-Z-ANDROIDU.md"] = (HERE / "INSTALACE-Z-ANDROIDU.md").read_bytes()
    files["source/LICENSE"] = (ROOT / "LICENSE").read_bytes()
    files["source/BACKLOG.md"] = (ROOT / "BACKLOG.md").read_bytes()
    sources = ["README.md", "NAVOD-LG.md", "TESTOVANI.md", "SHODA-ANDROID.md", "INSTALACE-Z-ANDROIDU.md", "test.cjs", "test-udp.cjs", "test-input.cjs", "test-receiver.cjs", "test-controls.cjs", "test-story.cjs", "test-recovery.cjs", "build.py", "test_build.py"]
    # as_posix: na Windows by relative_to dalo "app\\app.js" a název v ZIPu by nesouhlasil
    sources += [p.relative_to(HERE).as_posix() for folder in ["app", "service"] for p in sorted((HERE / folder).iterdir())]
    for name in sources:
        files["source/webos/" + name] = (HERE / name).read_bytes()
    files["SHA256SUMS"] = "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n" for name, data in sorted(files.items())).encode()
    output = HERE / "dist" / ("PlayCaption-LG-" + app["version"] + ".zip")
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None, "Poškozený ZIP"
        assert set(archive.namelist()) == set(files), "Nesouhlasí obsah ZIPu"
    print("OK: IPK odpovídá zdrojům; ZIP obsahuje návod, licenci, zdroje a SHA256SUMS.")
    print(output)


if __name__ == "__main__":
    main()
