#!/usr/bin/env python3
"""Used by the "Release player" workflow: signs the next player build from player/ into site/.

    python3 tools/ci_release.py --kind normal|broken|rollout-0|tampered

The build number is one more than the last release. The key comes from keys/private.pem (the workflow writes it from the
SIGNING_KEY secret). "tampered" makes a normal release, then changes the manifest without re-signing, so screens should refuse it."""
import argparse, json, os, subprocess, sys
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
a = argparse.ArgumentParser(); a.add_argument('--kind', default='normal', choices=['normal', 'broken', 'rollout-0', 'tampered']); o = a.parse_args()
man = os.path.join(HERE, 'site', 'manifest.json')
build = (json.load(open(man))['build'] if os.path.exists(man) else 0) + 1
cmd = [sys.executable, os.path.join(HERE, 'tools', 'release.py'), '--source', os.path.join(HERE, 'player'), '--build', str(build), '--version', f'build {build} ({o.kind})']
if o.kind == 'broken': cmd.append('--broken')
if o.kind == 'rollout-0': cmd += ['--rollout', '0']
subprocess.run(cmd, check=True)
if o.kind == 'tampered':
    t = open(man).read().replace('"rollout": 100', '"rollout": 99'); open(man, 'w').write(t)
    print('tampered: manifest changed after signing; screens should refuse it')
summary = os.environ.get('GITHUB_STEP_SUMMARY')
if summary: open(summary, 'a').write(f'Released player build {build} ({o.kind}).\n')
print(f'BUILD={build}')
