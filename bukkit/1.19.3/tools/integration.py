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
            builder.mkdir(exist_ok=True)
            buildtools = cache / "BuildTools.jar"
            download(definition["buildtools_url"], buildtools, definition["buildtools_sha256"])
            run_logged([java, "-Xmx2G", "-jar", str(buildtools), "--rev", definition.get("revision", definition["version"]), "--compile", "SPIGOT",
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
                payload = archive.read(name)
                if not destination.exists() or hashlib.sha256(destination.read_bytes()).digest() != hashlib.sha256(payload).digest():
                    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temporary:
                        temporary.write(payload)
                    os.replace(temporary.name, destination)
    native = list((cache / "versions").rglob("*.jar"))
    if len(native) != 1:
        raise RuntimeError(f"Expected one native server in {cache / 'versions'}, got {len(native)}")
    with zipfile.ZipFile(native[0]) as archive:
        actual = json.loads(archive.read("version.json"))
    expected = definition.get("minecraft_id", MODULE.name)
    if expected not in {MODULE.name, MODULE.name + "_unobfuscated"} or actual["id"] != expected:
        raise RuntimeError(f"SDK targets Minecraft {actual['id']}; expected exact {expected}")
    return launcher, native[0], actual["id"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=SERVERS, default="paper")
    parser.add_argument("--scenario", choices=["blocked-return", "departure-contact", "swept-contact"])
    parser.add_argument("--accept-eula", action="store_true",
                        help="accept https://www.minecraft.net/eula for disposable test servers")
    parser.add_argument("--cache", type=Path, default=MODULE / "build/integration-cache")
    parser.add_argument("--sync-chunk-writes", choices=["true", "false"], default="true")
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java")
                        if "JAVA_HOME" in os.environ else "java")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Read the Minecraft EULA, then pass --accept-eula to run test servers")
    java = shutil.which(args.java)
    if java is None:
        raise RuntimeError("Java 17 was not found")
    javac = Path(java).resolve().with_name("javac")
    cache = args.cache.resolve() / args.engine
    cache.mkdir(parents=True, exist_ok=True)
    launcher, server, minecraft = prepare_engine(java, args.engine, cache)
    jar = MODULE / "build/libs/portallinked-bukkit-1.2.0+1.19.3.jar"
    if not jar.is_file():
        raise RuntimeError("Build first: ./gradlew build")
    development = server
    # All 1.19.3 Bukkit implementations use the Spigot runtime namespace.
    # Compose private SDK mappings from the exact pinned BuildTools release;
    # retain Paper/Folia's native additions when preparing their development JAR.
    spigot_cache = cache.parent / "spigot"
    spigot_cache.mkdir(exist_ok=True)
    if args.engine != "spigot":
        prepare_engine(java, "spigot", spigot_cache)
    if args.engine == "spigot":
        sdk = list((cache / "builder/Spigot/Spigot-Server/target").glob("*-remapped-mojang.jar"))
        if len(sdk) != 1:
            raise RuntimeError("Expected one exact Spigot development SDK")
        development = sdk[0]
    else:
        development = cache / "portallinked-development.jar"
        marker = cache / "portallinked-development.sha256"
        sdk_digest = hashlib.sha256(server.read_bytes()).hexdigest()
        if not development.exists() or not marker.exists() or marker.read_text() != sdk_digest:
            run_logged([os.sys.executable, str(MODULE.parents[1] / "tools/spigot-fixture.py"),
                        "--builder", str(spigot_cache / "builder"), "--minecraft", MODULE.name, "--java", java,
                        "--input", str(server), "--output", str(development), "--reverse"],
                       MODULE, cache / "development-remap.log", timeout=180)
            marker.write_text(sdk_digest)
    classpath = [development, jar, *sorted((cache / "libraries").rglob("*.jar"))]
    work = MODULE / ("build/integration-" + args.engine)
    classes = work / "fixture-classes"
    classes.mkdir(parents=True, exist_ok=True)
    fixture = "PortalIntegration"
    run_logged([str(javac), "--release", "17", "-cp", os.pathsep.join(map(str, classpath)),
                "-d", str(classes), str(MODULE / f"tools/{fixture}.java")],
               MODULE, work / "fixture-compile.log", timeout=180)
    jar = MODULE / "build/libs/portallinked-bukkit-1.2.0+1.19.3.jar"
    if not jar.is_file():
        raise RuntimeError("Build first: ./gradlew build")
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    stage = Path(tempfile.mkdtemp(prefix="run-", dir=work))
    (stage / "plugins").mkdir()
    copied = stage / "plugins" / jar.name
    shutil.copy2(jar, copied)
    with zipfile.ZipFile(stage / "plugins/PortalIntegration.jar", "w") as out:
        out.writestr("plugin.yml", "name: PortalIntegration\nversion: 1\n"
                     f"main: dev.eyecrasher.fixture.{fixture}\napi-version: '1.19'\ndepend: [PortalLinked]\n")
        for path in classes.rglob("*.class"):
            out.write(path, path.relative_to(classes))
    original = stage / "fixture-mojang.jar"
    (stage / "plugins/PortalIntegration.jar").rename(original)
    run_logged([os.sys.executable, str(MODULE.parents[1] / "tools/spigot-fixture.py"),
                "--builder", str(spigot_cache / "builder"), "--minecraft", MODULE.name, "--java", java,
                "--input", str(original), "--output", str(stage / "plugins/PortalIntegration.jar")],
               MODULE, work / "fixture-remap.log", timeout=120)
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
        "pause-when-empty-seconds=-1\nmax-tick-time=300000\n"
        f"sync-chunk-writes={args.sync_chunk_writes}\n")
    (stage / "spigot.yml").write_text("settings:\n  timeout-time: 300\n")
    phases = []
    print(f"{args.engine} {SERVERS[args.engine]['version']} test directory: {stage}", flush=True)
    for phase in ([1] if args.scenario else [1, 2]):
        if phase == 2:
            (stage / "plugins/PortalLinked/config.yml").write_text(
                "enabled: null\nexcluded-worlds: []\ndebug: false\n")
        log = stage / f"console-{phase}.log"
        run_logged([java, "-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=4",
                    "-Dterminal.jline=false", "-Dterminal.ansi=false", f"-Dportal.phase={phase}",
                    f"-Dportallinked.scenario={args.scenario or ''}",
                    "-jar", "server.jar", "--nogui"], stage, log,
                   timeout=600)
        # Event dispatch catches plugin exceptions and may let vanilla travel
        # continue, so a successful fixture result alone must not hide them.
        if any("Could not pass event" in line and ("to PortalLinked " in line or "to PortalIntegration " in line)
               for line in log.read_text(errors="replace").splitlines()):
            raise RuntimeError(f"Plugin event handler failed; inspect {log}")
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
    report = {"minecraft": MODULE.name, "server_minecraft_id": minecraft, "jar_sha256": digest, "engine": args.engine, "sync_chunk_writes": args.sync_chunk_writes == "true", "scenario": args.scenario, "server": SERVERS[args.engine], "phases": phases,
              "passed": sum(len(phase["checks"]) for phase in phases), "stage": str(stage)}
    (work / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "jar_sha256": digest}), flush=True)


if __name__ == "__main__":
    main()
