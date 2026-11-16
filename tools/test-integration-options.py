#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 EyeCrasher
"""Exercise native runner argument contracts without downloading or starting SDKs."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class IntegrationOptionsTest(unittest.TestCase):
    def invoke(self, runner, *arguments):
        return subprocess.run([sys.executable, str(runner), *arguments],
                              capture_output=True, text=True, timeout=10)

    def test_legacy_fixture_rejects_unimplemented_scenarios(self):
        runner = ROOT / 'bukkit/1.12.2/tools/integration.py'
        for scenario in ['blocked-return', 'departure-contact', 'swept-contact', 'source-exit']:
            with self.subTest(scenario=scenario):
                result = self.invoke(runner, '--scenario', scenario)
                self.assertEqual(result.returncode, 2)
                self.assertIn('unrecognized arguments: --scenario', result.stderr)

    def test_non_folia_runners_reject_folia_only_scenarios(self):
        for sdk in sorted((ROOT / 'bukkit').glob('*/tools/servers.json')):
            if 'folia' in json.loads(sdk.read_text()):
                continue
            runner = sdk.with_name('integration.py')
            for scenario in ['pending-contact', 'teleport-lock', 'source-exit', 'native-policy']:
                with self.subTest(minecraft=sdk.parent.parent.name, scenario=scenario):
                    result = self.invoke(runner, '--scenario', scenario)
                    self.assertEqual(result.returncode, 2)
                    self.assertTrue('invalid choice:' in result.stderr
                                    or 'unrecognized arguments: --scenario' in result.stderr,
                                    result.stderr)

    def test_supported_scenarios_reach_the_eula_boundary(self):
        for sdk in sorted((ROOT / 'bukkit').glob('*/tools/servers.json')):
            if sdk.parent.parent.name == '1.12.2':
                continue
            engines = json.loads(sdk.read_text())
            engine = 'folia' if 'folia' in engines else next(iter(engines))
            runner = sdk.with_name('integration.py')
            scenarios = ['blocked-return', 'departure-contact', 'swept-contact']
            if engine == 'folia':
                scenarios += ['teleport-lock', 'source-exit']
            for scenario in scenarios:
                with self.subTest(minecraft=sdk.parent.parent.name, scenario=scenario):
                    result = self.invoke(runner, '--engine', engine, '--scenario', scenario)
                    self.assertEqual(result.returncode, 2)
                    self.assertIn('Read the Minecraft EULA', result.stderr)

    def test_mod_runner_rejects_unimplemented_scenarios_before_startup(self):
        runner = ROOT / 'tools/mod-integration.py'
        targets = [
            (version, 'forge', scenario)
            for version in ['1.7.10', '1.8.9', '1.12.2']
            for scenario in ['blocked-return', 'dimension-identifiers', 'adjacent-arrival', 'native-save']
        ]
        targets += [
            ('1.16.5', 'fabric', 'adjacent-arrival'),
            ('1.17', 'fabric', 'adjacent-arrival'),
            ('1.17.1', 'fabric', 'native-save'),
            ('1.18.1', 'forge', 'native-save'),
            ('26.3', 'fabric', 'adjacent-arrival'),
            ('26.3', 'neoforge', 'native-save'),
            ('26.3', 'forge', 'adjacent-arrival'),
        ]
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / 'cache'
            missing_java = Path(directory) / 'java-must-not-start'
            for version, loader, scenario in targets:
                with self.subTest(minecraft=version, loader=loader, scenario=scenario):
                    result = self.invoke(
                        runner, '--loader', loader, '--minecraft', version,
                        '--scenario', scenario, '--accept-eula', '--java', str(missing_java),
                        '--cache', str(cache))
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertIn(f'--scenario {scenario} is not implemented', result.stderr)
                    self.assertIn('full two-phase restart suite', result.stderr)
                    self.assertFalse(cache.exists())

    def test_mod_supported_scenarios_and_full_suites_reach_the_eula_boundary(self):
        runner = ROOT / 'tools/mod-integration.py'
        definitions = [ROOT / 'tools/mod-servers.json',
                       *sorted((ROOT / 'tools/mod-servers').glob('*.json'))]
        for definition in definitions:
            for loader, server in json.loads(definition.read_text()).items():
                version = server['minecraft']
                scenarios = [None]
                if version not in ['1.7.10', '1.8.9', '1.12.2']:
                    scenarios += ['blocked-return', 'dimension-identifiers']
                if version == '1.17':
                    scenarios += ['native-save']
                if version == '1.18.1':
                    scenarios += ['adjacent-arrival']
                for scenario in scenarios:
                    with self.subTest(minecraft=version, loader=loader, scenario=scenario):
                        arguments = ['--loader', loader, '--minecraft', version]
                        if scenario:
                            arguments += ['--scenario', scenario]
                        result = self.invoke(runner, *arguments)
                        self.assertEqual(result.returncode, 2, result.stderr)
                        self.assertIn('Read https://www.minecraft.net/eula', result.stderr)


if __name__ == '__main__':
    unittest.main()
