#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 EyeCrasher
"""Run the released mod and a separate native fixture on a disposable server."""

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

ROOT = Path(__file__).resolve().parents[1]
SERVERS = json.loads((ROOT / 'tools/mod-servers.json').read_text())


def run_logged(command, cwd, log, timeout=360):
    with log.open('w') as output:
        process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                process.stdin.write(b'stop\n'); process.stdin.flush(); process.wait(timeout=30)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                process.kill(); process.wait()
            raise RuntimeError(f'Test timed out; inspect {log}') from None
        finally:
            process.stdin.close()
    if code:
        raise RuntimeError(f'Command exited {code}; inspect {log}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--loader', choices=SERVERS, required=True)
    parser.add_argument('--scenario', choices=['blocked-return'])
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--cache', type=Path, default=ROOT / 'build/mod-integration-cache')
    parser.add_argument('--java', default=str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java')
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Read https://www.minecraft.net/eula and pass --accept-eula for disposable test servers')
    java = shutil.which(args.java)
    if java is None:
        raise RuntimeError('Java 25 is required')
    loader = args.loader
    definition = SERVERS[loader]
    module = ROOT / loader / '26.3'
    jar = module / f'build/libs/portallinked-{loader}-1.1.0+26.3.jar'
    fixture = module / f'build/libs/portallinked-integration-{loader}-1.jar'
    if not jar.is_file() or not fixture.is_file():
        raise RuntimeError(f'First run ./gradlew build integrationFixture in {module}')
    cache = args.cache.resolve() / loader
    cache.mkdir(parents=True, exist_ok=True)
    launcher = cache / ('fabric.jar' if definition['kind'] == 'fabric' else 'installer.jar')
    if not launcher.exists() or hashlib.sha256(launcher.read_bytes()).hexdigest() != definition['sha256']:
        request = urllib.request.Request(definition['url'], headers={'User-Agent': 'PortalLinked-integration/1.1 (https://github.com/eyecrasher0/PortalLinked)'})
        with urllib.request.urlopen(request, timeout=90) as response:
            payload = response.read()
        if hashlib.sha256(payload).hexdigest() != definition['sha256']:
            raise RuntimeError('Loader SDK checksum mismatch')
        launcher.write_bytes(payload)
    if definition['kind'] == 'installer' and not (cache / definition['args']).is_file():
        run_logged([java, '-jar', str(launcher), '--installServer', str(cache)], cache, cache / 'installer.log')
    work = module / 'build/integration'
    work.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='run-', dir=work))
    (stage / 'mods').mkdir()
    shutil.copy2(jar, stage / 'mods' / jar.name)
    shutil.copy2(fixture, stage / 'mods' / fixture.name)
    if definition['kind'] == 'installer':
        (stage / 'libraries').symlink_to(cache / 'libraries', target_is_directory=True)
        for shim in cache.glob('*-shim.jar'):
            (stage / shim.name).symlink_to(shim)
    (stage / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as available:
        available.bind(('127.0.0.1', 0)); port = available.getsockname()[1]
    generator = json.dumps({'layers': [{'block': 'minecraft:bedrock', 'height': 1},
                                      {'block': 'minecraft:grass_block', 'height': 1}], 'biome': 'minecraft:plains'})
    (stage / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n'
        f'level-type=minecraft:flat\ngenerator-settings={generator}\nlevel-seed=123456789\n'
        'generate-structures=false\nview-distance=2\nsimulation-distance=2\n'
        'pause-when-empty-seconds=-1\nmax-tick-time=300000\n')
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    print(f'{loader} {definition["version"]} native test directory: {stage}', flush=True)
    phases = []
    for phase in ([1] if args.scenario else [1, 2]):
        if phase == 2:
            (stage / 'config/portallinked.json').write_text('{"enabled":null,"excluded-worlds":[],"debug":false}\n')
        command = [java, '-Xms512M', '-Xmx2G', '-XX:ActiveProcessorCount=4', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                   '-Dportallinked.integration=true', f'-Dportallinked.phase={phase}',
                   f'-Dportallinked.scenario={args.scenario or ""}']
        command += ['-jar', str(launcher)] if definition['kind'] == 'fabric' else ['@' + str(cache / definition['args'])]
        command += ['--nogui']
        log = stage / f'console-{phase}.log'
        run_logged(command, stage, log)
        result = stage / f'result-{phase}.json'
        if not result.is_file():
            raise RuntimeError(f'No fixture result; inspect {log}')
        outcome = json.loads(result.read_text())
        if outcome.get('failure'):
            raise RuntimeError(f'{outcome["failure"]}; inspect {log}')
        if not outcome['checks']:
            raise RuntimeError('No checks executed')
        phases.append(outcome)
        print(f'Phase {phase}: {len(outcome["checks"])} checks passed', flush=True)
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path in [jar, stage / 'mods' / jar.name]):
        raise RuntimeError('Runtime artifact changed during integration')
    report = {'loader': loader, 'scenario': args.scenario, 'server': definition, 'jar_sha256': digest, 'phases': phases,
              'passed': sum(len(phase['checks']) for phase in phases), 'stage': str(stage)}
    (work / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'loader': loader, 'passed': report['passed'], 'jar_sha256': digest}), flush=True)


if __name__ == '__main__':
    main()
