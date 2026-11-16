#!/usr/bin/env python3
"""Used by the "Build app" workflow, before building the APK:
  - points shell-config.json at this repository's releases (raw files, no GitHub Pages needed)
  - puts the screen's settings in from the SCREEN_DEVICE_ID secret (and optional SCREEN_LINES / SCREEN_MEAL variables)
  - signs the current player/ into the app as its built-in player, so a new screen plays offline from its first start

Environment: GITHUB_REPOSITORY (owner/repo), GITHUB_REF_NAME (branch), SCREEN_DEVICE_ID, SCREEN_LINES ("Deli Line, Basket Line"),
SCREEN_MEAL ("Lunch", "Breakfast" or "auto")."""
import json, os, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
repo, branch = os.environ['GITHUB_REPOSITORY'], os.environ.get('GITHUB_REF_NAME', 'main')
cfgp = os.path.join(HERE, 'android/app/src/main/assets/shell-config.json'); cfg = json.load(open(cfgp))
cfg['updateUrl'] = f'https://raw.githubusercontent.com/{repo}/{branch}/site/'
p = cfg.setdefault('player', {})
p['deviceId'] = os.environ.get('SCREEN_DEVICE_ID', '').strip()
if os.environ.get('SCREEN_LINES', '').strip(): p['lines'] = [x.strip() for x in os.environ['SCREEN_LINES'].split(',') if x.strip()]
if os.environ.get('SCREEN_MEAL', '').strip(): p['meal'] = os.environ['SCREEN_MEAL'].strip()
json.dump(cfg, open(cfgp, 'w'), indent=1)
if not p['deviceId']: print('WARNING: no SCREEN_DEVICE_ID secret: the player will ask for the screen settings on the TV')
man = os.path.join(HERE, 'site', 'manifest.json'); floor_build = json.load(open(man))['build'] if os.path.exists(man) else 0
subprocess.run([sys.executable, os.path.join(HERE, 'tools', 'release.py'), '--source', os.path.join(HERE, 'player'), '--build', str(floor_build),
                '--version', f'built-in (as build {floor_build})', '--site', tempfile.mkdtemp(), '--floor'], check=True)
print(f"app points at {cfg['updateUrl']}; built-in player = build {floor_build}; checks every {cfg.get('checkMinutes')} min, switch policy {cfg.get('switchPolicy')}")
