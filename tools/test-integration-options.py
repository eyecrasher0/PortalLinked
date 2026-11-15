#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 EyeCrasher
"""Exercise native runner argument contracts without downloading or starting SDKs."""

import json
from pathlib import Path
import subprocess
import sys
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


if __name__ == '__main__':
    unittest.main()
