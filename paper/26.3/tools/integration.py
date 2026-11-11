#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 EyeCrasher
"""Test the release JAR on disposable loopback-only Paper servers and a restart."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import urllib.request
import zipfile

MODULE = Path(__file__).resolve().parents[1]
PAPER_SHA256 = "9601f9a26a2a52a0f2c807cf1abf691f96ee4fb287878313b601cebc2c51eabf"
PAPER_URL = f"https://fill-data.papermc.io/v1/objects/{PAPER_SHA256}/paper-26.3-244.jar"


def run_logged(command, cwd, log, timeout=300):
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE,
                                   stdout=output, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # First request an orderly stop; kill only this disposable test process
            # if it cannot shut down within the additional timeout.
            try:
                process.stdin.write(b"stop\n")
                process.stdin.flush()
                process.wait(timeout=30)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                process.kill()
                process.wait()
            raise RuntimeError(f"Test timed out; inspect {log}") from None
        finally:
            process.stdin.close()
    if code:
        raise RuntimeError(f"Command exited {code}; inspect {log}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true",
                        help="accept https://www.minecraft.net/eula for disposable test servers")
    parser.add_argument("--cache", type=Path, default=MODULE / "build/integration-cache")
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java")
                        if "JAVA_HOME" in os.environ else "java")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Read the Minecraft EULA, then pass --accept-eula to run test servers")
    java = shutil.which(args.java)
    if java is None:
        raise RuntimeError("Java 25 was not found")
    javac = Path(java).resolve().with_name("javac")
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    paper = cache / "paper.jar"
    if not paper.exists() or hashlib.sha256(paper.read_bytes()).hexdigest() != PAPER_SHA256:
        request = urllib.request.Request(PAPER_URL, headers={"User-Agent": "PortalLinked-integration/1.0"})
        with urllib.request.urlopen(request, timeout=90) as response:
            payload = response.read()
        if hashlib.sha256(payload).hexdigest() != PAPER_SHA256:
            raise RuntimeError("Paper checksum mismatch")
        paper.write_bytes(payload)
    run_logged([java, "-Dpaperclip.patchonly=true", "-jar", str(paper)], cache, cache / "bootstrap.log")
    server = cache / "versions/26.3/paper-26.3.jar"
    api = cache / "libraries/io/papermc/paper/paper-api/26.3.build.244-stable/paper-api-26.3.build.244-stable.jar"
    classpath = [api, server]
    classpath.extend(p for p in (cache / "libraries").rglob("*.jar") if "paper-api" not in str(p))
    work = MODULE / "build/integration"
    classes = work / "fixture-classes"
    classes.mkdir(parents=True, exist_ok=True)
    run_logged([str(javac), "--release", "25", "-cp", os.pathsep.join(map(str, classpath)),
                "-d", str(classes), str(MODULE / "tools/PortalIntegration.java")],
               MODULE, work / "fixture-compile.log", timeout=90)
    jar = MODULE / "build/libs/portallinked-paper-1.0.0+26.3.jar"
    if not jar.is_file():
        raise RuntimeError("Build first: ./gradlew build")
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    stage = Path(tempfile.mkdtemp(prefix="run-", dir=work))
    (stage / "plugins").mkdir()
    copied = stage / "plugins" / jar.name
    shutil.copy2(jar, copied)
    with zipfile.ZipFile(stage / "plugins/PortalIntegration.jar", "w") as out:
        out.writestr("plugin.yml", "name: PortalIntegration\nversion: 1\n"
                     "main: dev.eyecrasher.fixture.PortalIntegration\napi-version: '26.3'\ndepend: [PortalLinked]\n")
        for path in classes.rglob("*.class"):
            out.write(path, path.relative_to(classes))
    shutil.copy2(paper, stage / "paper.jar")
    for name in ["libraries", "versions", "cache"]:
        if (cache / name).exists():
            (stage / name).symlink_to(cache / name, target_is_directory=True)
    (stage / "eula.txt").write_text("eula=true\n")
    with socket.socket() as available:
        available.bind(("127.0.0.1", 0))
        port = available.getsockname()[1]
    generator = json.dumps({"layers": [{"block": "minecraft:bedrock", "height": 1},
                                      {"block": "minecraft:grass_block", "height": 1}], "biome": "minecraft:plains"})
    (stage / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n"
        f"level-type=minecraft:flat\ngenerator-settings={generator}\nlevel-seed=123456789\n"
        "generate-structures=false\nview-distance=2\nsimulation-distance=2\n"
        "pause-when-empty-seconds=-1\nmax-tick-time=300000\n")
    (stage / "spigot.yml").write_text("settings:\n  timeout-time: 300\n")
    phases = []
    print(f"Paper 26.3-244 test directory: {stage}", flush=True)
    for phase in [1, 2]:
        log = stage / f"console-{phase}.log"
        run_logged([java, "-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=4",
                    "-Dterminal.jline=false", "-Dterminal.ansi=false", f"-Dportal.phase={phase}",
                    "-jar", "paper.jar", "--nogui"], stage, log)
        result = stage / f"result-{phase}.json"
        if not result.is_file():
            raise RuntimeError(f"No fixture result; inspect {log}")
        outcome = json.loads(result.read_text())
        if outcome["failure"] is not None:
            raise RuntimeError(f"{outcome['failure']}; inspect {log}")
        phases.append(outcome)
        print(f"Phase {phase}: {len(outcome['checks'])} checks passed", flush=True)
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path in [jar, copied]):
        raise RuntimeError("Release artifact changed during integration")
    report = {"jar_sha256": digest, "paper": "26.3-244", "phases": phases,
              "passed": sum(len(phase["checks"]) for phase in phases), "stage": str(stage)}
    (work / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "jar_sha256": digest}), flush=True)


if __name__ == "__main__":
    main()
