#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 EyeCrasher
"""Inspect the exact-version backport queue and run implemented target checks."""

import argparse
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PLATFORMS = ['paper', 'spigot', 'purpur', 'folia', 'fabric', 'neoforge', 'forge']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['list', 'build', 'native', 'ci-matrix'])
    parser.add_argument('--minecraft')
    parser.add_argument('--platform', choices=PLATFORMS)
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--installer-java', help='optional separate JDK for a mod-loader installer')
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'tools/backport-targets.json').read_text())
    if manifest['schema'] != 1:
        parser.error('Unsupported target manifest schema')
    rows = manifest['targets']
    if len({row['minecraft'] for row in rows}) != len(rows):
        parser.error('Duplicate Minecraft target')
    if args.minecraft and not any(row['minecraft'] == args.minecraft for row in rows):
        parser.error('Minecraft version is not a resolved target; see unresolved_requests in the manifest')
    selected = [row for row in rows if not args.minecraft or row['minecraft'] == args.minecraft]
    if args.command == 'ci-matrix':
        entries = []
        for row in selected:
            if row['minecraft'] == '26.3':
                continue  # The released target retains its existing workflow.
            for platform in PLATFORMS:
                target = row['platforms'][platform]
                if not target['available'] or target['state'] == 'planned':
                    continue
                if args.platform and platform != args.platform:
                    continue
                loader = 'bukkit' if platform in PLATFORMS[:4] else platform
                module = Path(loader) / row['minecraft']
                if not (ROOT / module / 'build.gradle').is_file():
                    parser.error(f'Implemented target is missing its build project: {module}')
                legacy = loader == 'forge' and row['java'] == 8
                entries.append({
                    'minecraft': row['minecraft'], 'platform': platform, 'module': str(module),
                    'java': row['java'], 'build_java': target.get('build_java',
                        25 if loader == 'forge' and not legacy else row['java']),
                    'legacy_forge': legacy,
                    'setup_decomp': loader == 'forge' and row['minecraft'] in ['1.7.10', '1.8.9'],
                })
        print(json.dumps({'include': entries}, separators=(',', ':')))
        return
    if args.command == 'list':
        if args.json:
            print(json.dumps(selected, indent=2))
        else:
            print('Minecraft  Java  ' + '  '.join(PLATFORMS))
            for row in selected:
                states = [row['platforms'][platform].get('state', 'unavailable') for platform in PLATFORMS]
                print(row['minecraft'] + '  ' + str(row['java']) + '  ' + '  '.join(states))
        return
    if not args.minecraft or not args.platform:
        parser.error('build/native require --minecraft and --platform')
    target = selected[0]['platforms'][args.platform]
    if not target['available']:
        parser.error(target['reason'])
    if target['state'] == 'planned':
        parser.error('Upstream SDK exists, but the PortalLinked adapter has not been implemented yet')
    bukkit = args.platform in ['paper', 'spigot', 'purpur', 'folia']
    loader = 'bukkit' if bukkit else args.platform
    module = ROOT / loader / args.minecraft
    if not (module / 'build.gradle').is_file():
        parser.error('Implemented target is missing its build project')
    if args.command == 'build':
        command = ['./gradlew', 'build', '--console=plain']
        if not bukkit:
            command.insert(2, 'integrationFixture')
        subprocess.run(command, cwd=module, check=True)
    else:
        if not args.accept_eula:
            parser.error('Read https://www.minecraft.net/eula and pass --accept-eula for disposable servers')
        if bukkit:
            command = [sys.executable, 'tools/integration.py', '--engine', args.platform, '--accept-eula']
            cwd = module
        else:
            command = [sys.executable, 'tools/mod-integration.py', '--loader', loader,
                       '--minecraft', args.minecraft, '--accept-eula']
            cwd = ROOT
        if args.installer_java:
            if bukkit:
                parser.error('--installer-java is only used by mod loaders')
            command += ['--installer-java', args.installer_java]
        if args.cache:
            command += ['--cache', str(args.cache.resolve())]
        if args.minecraft != '26.3':
            command += ['--sync-chunk-writes', 'false']
        subprocess.run(command, cwd=cwd, check=True)


if __name__ == '__main__':
    main()
