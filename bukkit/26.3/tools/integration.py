#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 EyeCrasher
"""Test the release JAR on a disposable loopback-only server and a restart."""

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
SERVERS = json.loads((MODULE / "tools/servers.json").read_text())


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


def download(url, destination, digest):
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == digest:
        return
    request = urllib.request.Request(url, headers={"User-Agent": "PortalLinked-integration/1.1 (https://github.com/eyecrasher0/PortalLinked)"})
    with urllib.request.urlopen(request, timeout=90) as response:
        payload = response.read()
    if hashlib.sha256(payload).hexdigest() != digest:
        raise RuntimeError(f"Server SDK checksum mismatch: {destination.name}")
    destination.write_bytes(payload)


def prepare_engine(java, engine, cache):
    definition = SERVERS[engine]
    launcher = cache / (engine + ".jar")
    if definition["kind"] == "paperclip":
        download(definition["url"], launcher, definition["sha256"])
        run_logged([java, "-Dpaperclip.patchonly=true", "-jar", str(launcher)], cache, cache / "bootstrap.log")
    else:
        builder = cache / "builder"
        marker = cache / "verified-sdk.json"
        verified = json.loads(marker.read_text()) if marker.exists() else None
        reusable = launcher.exists() and verified and verified["refs"] == definition["refs"] \
            and verified["sha256"] == hashlib.sha256(launcher.read_bytes()).hexdigest()
        if not reusable:
            if not launcher.exists() or not all((builder / name / ".git").exists() for name in definition["refs"]):
                builder.mkdir(exist_ok=True)
                buildtools = cache / "BuildTools.jar"
                download(definition["buildtools_url"], buildtools, definition["buildtools_sha256"])
                run_logged([java, "-Xmx2G", "-jar", str(buildtools), "--rev", "26.3", "--compile", "SPIGOT",
                            "--remapped", "--nogui", "--final-name", "spigot.jar", "--output-dir", str(cache)],
                           builder, cache / "buildtools.log", timeout=900)
            for name, expected in definition["refs"].items():
                actual = subprocess.check_output(["git", "-C", str(builder / name), "rev-parse", "HEAD"], text=True).strip()
                if actual != expected:
                    raise RuntimeError(f"Spigot {name} source changed: expected {expected}, got {actual}")
            marker.write_text(json.dumps({"refs": definition["refs"], "sha256": hashlib.sha256(launcher.read_bytes()).hexdigest()}, indent=2) + "\n")
        # Spigot's official bundle includes its own server and all SDK libraries.
        with zipfile.ZipFile(launcher) as archive:
            for name in archive.namelist():
                if not name.endswith(".jar") or not name.startswith(("META-INF/libraries/", "META-INF/versions/")):
                    continue
                relative = Path(name).relative_to("META-INF")
                destination = cache / relative
                if not destination.resolve().is_relative_to(cache.resolve()):
                    raise RuntimeError("Unsafe server bundle path")
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(name))
    native = list((cache / "versions").rglob("*.jar"))
    if len(native) != 1:
        raise RuntimeError(f"Expected one native server in {cache / 'versions'}, got {len(native)}")
    return launcher, native[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=SERVERS, default="paper")
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
    cache = args.cache.resolve() / args.engine
    cache.mkdir(parents=True, exist_ok=True)
    launcher, server = prepare_engine(java, args.engine, cache)
    classpath = [server, *sorted((cache / "libraries").rglob("*.jar"))]
    work = MODULE / ("build/integration-" + args.engine)
    classes = work / "fixture-classes"
    classes.mkdir(parents=True, exist_ok=True)
    fixture = "FoliaIntegration" if args.engine == "folia" else "PortalIntegration"
    run_logged([str(javac), "--release", "25", "-cp", os.pathsep.join(map(str, classpath)),
                "-d", str(classes), str(MODULE / f"tools/{fixture}.java")],
               MODULE, work / "fixture-compile.log", timeout=90)
    jar = MODULE / "build/libs/portallinked-bukkit-1.1.0+26.3.jar"
    if not jar.is_file():
        raise RuntimeError("Build first: ./gradlew build")
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    stage = Path(tempfile.mkdtemp(prefix="run-", dir=work))
    (stage / "plugins").mkdir()
    copied = stage / "plugins" / jar.name
    shutil.copy2(jar, copied)
    with zipfile.ZipFile(stage / "plugins/PortalIntegration.jar", "w") as out:
        out.writestr("plugin.yml", "name: PortalIntegration\nversion: 1\n"
                     f"main: dev.eyecrasher.fixture.{fixture}\napi-version: '26.3'\ndepend: [PortalLinked]\nfolia-supported: true\n")
        for path in classes.rglob("*.class"):
            out.write(path, path.relative_to(classes))
    shutil.copy2(launcher, stage / "server.jar")
    if SERVERS[args.engine]["kind"] == "paperclip":
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
    if args.engine == "folia":
        (stage / "config").mkdir(exist_ok=True)
        (stage / "config/paper-global.yml").write_text("threaded-regions:\n  threads: 4\n")
    phases = []
    print(f"{args.engine} {SERVERS[args.engine]['version']} test directory: {stage}", flush=True)
    for phase in [1, 2]:
        if phase == 2:
            (stage / "plugins/PortalLinked/config.yml").write_text(
                "enabled: null\nexcluded-worlds: []\ndebug: false\n")
        log = stage / f"console-{phase}.log"
        run_logged([java, "-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=4",
                    "-Dterminal.jline=false", "-Dterminal.ansi=false", f"-Dportal.phase={phase}",
                    "-jar", "server.jar", "--nogui"], stage, log)
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
    report = {"jar_sha256": digest, "engine": args.engine, "server": SERVERS[args.engine], "phases": phases,
              "passed": sum(len(phase["checks"]) for phase in phases), "stage": str(stage)}
    (work / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "jar_sha256": digest}), flush=True)


if __name__ == "__main__":
    main()
