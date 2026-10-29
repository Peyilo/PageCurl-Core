#!/usr/bin/env python3
"""Check the local release artifacts before a Central upload; never contacts a server."""

import argparse
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


def verify(root: Path, require_signatures: bool) -> None:
    properties = dict(
        line.split("=", 1)
        for line in (root / "gradle.properties").read_text().splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )
    group, version = properties["GROUP"], properties["VERSION_NAME"]
    artifact = "pagecurl-core"
    directory = root / "build/repository" / group.replace(".", "/") / artifact / version
    stem = f"{artifact}-{version}"
    artifacts = [directory / f"{stem}{suffix}" for suffix in (
        ".aar", ".pom", "-sources.jar", "-javadoc.jar",
    )]
    for path in artifacts:
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"Missing or empty artifact: {path.name}")
        for algorithm in ("md5", "sha1"):
            checksum = Path(f"{path}.{algorithm}")
            if not checksum.is_file():
                raise ValueError(f"Missing checksum: {checksum.name}")
            actual = hashlib.new(algorithm, path.read_bytes()).hexdigest()
            if checksum.read_text().strip().lower() != actual:
                raise ValueError(f"Checksum mismatch: {checksum.name}")
        if require_signatures:
            signature = Path(f"{path}.asc")
            if not signature.is_file() or "BEGIN PGP SIGNATURE" not in signature.read_text():
                raise ValueError(f"Missing armored signature: {signature.name}")

    pom = ET.parse(directory / f"{stem}.pom")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for field, expected in (("groupId", group), ("artifactId", artifact), ("version", version), ("packaging", "aar")):
        if pom.findtext(f"m:{field}", namespaces=ns) != expected:
            raise ValueError(f"Unexpected POM field: {field}")
    for field in ("name", "description", "url", "licenses/license/name", "licenses/license/url",
                  "developers/developer/id", "developers/developer/name", "developers/developer/email",
                  "scm/url", "scm/connection"):
        xpath = "/".join(f"m:{segment}" for segment in field.split("/"))
        if not pom.findtext(xpath, namespaces=ns):
            raise ValueError(f"Missing POM metadata: {field}")

    with zipfile.ZipFile(directory / f"{stem}.aar") as archive:
        if not {"AndroidManifest.xml", "classes.jar"}.issubset(archive.namelist()):
            raise ValueError("AAR is missing its manifest or compiled library")
    with zipfile.ZipFile(directory / f"{stem}-sources.jar") as archive:
        if not any(name.endswith("CurlMesh.kt") for name in archive.namelist()):
            raise ValueError("Sources JAR does not include the public mesh API")
    with zipfile.ZipFile(directory / f"{stem}-javadoc.jar") as archive:
        if "index.html" not in archive.namelist() or "META-INF/LICENSE" not in archive.namelist():
            raise ValueError("Documentation JAR is missing its HTML entry point or license")
        if not any("-curl-mesh/" in name and name.endswith(".html") for name in archive.namelist()):
            raise ValueError("Documentation JAR does not include generated mesh API pages")
    print(f"Verified {group}:{artifact}:{version}: AAR, sources, API documentation, POM and checksums.")
    if require_signatures:
        print("Detached signatures are present; Central will also verify them against the published public key.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-signatures", action="store_true")
    arguments = parser.parse_args()
    verify(Path(__file__).resolve().parents[1], arguments.require_signatures)
