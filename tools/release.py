#!/usr/bin/env python3
"""Sign a player release into site/, the folder the update host serves (for example GitHub Pages).

    python3 tools/release.py --source ../poc-showcase/web --build 2 --version "Showcase 2" [--rollout 100] [--min-shell 1]
                             [--broken] [--floor]

  --source    a folder holding the player (its index.html and every file it uses)
  --build     a whole number, higher than any build released before (screens ignore anything not newer)
  --rollout   the share of screens that take this build, 0-100 (the same screens go first every time)
  --broken    a test build that never reports ready, to watch the screen roll back
  --floor     also put this build inside the app (android/.../assets/floor), as the player a new screen starts with

Writes site/manifest.json, site/manifest.sig and site/versions/<build>/. Earlier versions stay, so a screen that is still
downloading one isn't cut off. Push site/ to the host after each release."""
import argparse, base64, hashlib, json, os, shutil, sys, datetime
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
a = argparse.ArgumentParser(); a.add_argument('--source', required=True); a.add_argument('--build', type=int, required=True); a.add_argument('--version', default='')
a.add_argument('--rollout', type=int, default=100); a.add_argument('--min-shell', type=int, default=1); a.add_argument('--entry', default='index.html')
a.add_argument('--broken', action='store_true'); a.add_argument('--floor', action='store_true'); a.add_argument('--site', default=os.path.join(HERE, 'site')); a.add_argument('--key', default=os.path.join(HERE, 'keys', 'private.pem'))
o = a.parse_args()
if not os.path.exists(o.key): sys.exit('no signing key: run tools/keygen.py first')
man_path = os.path.join(o.site, 'manifest.json')
if os.path.exists(man_path):
    last = json.load(open(man_path))['build']
    if o.build <= last: sys.exit(f'build {o.build} is not newer than the last release ({last}): screens would ignore it')
HOOK = """<script>/* SCTV+ thin-shell hook (added by tools/release.py) */
(function () {
  var B = window.SctvShell, build = %d, broken = %s, sent = false;
  window.sctvPlayerBuild = build;
  function ready() { if (sent || broken) return; sent = true; try { if (B) B.ready(String(build)); } catch (e) {} }
  var t0 = Date.now();   /* ready once the first slide is on screen (or the page has loaded and 20 s have passed) */
  (function wait() { if (document.querySelector('#stage .pl-layer.on') || (Date.now() - t0 > 20000 && document.readyState === 'complete')) ready(); else setTimeout(wait, 500); })();
  /* A safe moment to switch builds: outside serving times. Placeholder windows until the device package carries the schedule. */
  window.sctvSafeToSwitch = function () { var d = new Date(), m = d.getHours() * 60 + d.getMinutes();
    return ![[405, 540], [630, 825]].some(function (w) { return m >= w[0] && m < w[1]; }); };
  document.addEventListener('DOMContentLoaded', function () { var t = document.createElement('div'); t.id = 'shellBuildTag';
    t.textContent = 'Player build ' + build + (broken ? ' (test: never reports ready)' : '');
    t.style.cssText = 'position:fixed;left:8px;bottom:6px;z-index:99999;font:600 13px system-ui,sans-serif;color:#fff;background:rgba(0,0,0,.55);padding:3px 8px;border-radius:6px;pointer-events:none';
    document.body.appendChild(t); });
})();
</script>""" % (o.build, 'true' if o.broken else 'false')
out = os.path.join(o.site, 'versions', str(o.build)); shutil.rmtree(out, ignore_errors=True); shutil.copytree(o.source, out)
# Screen settings (device ID, lines) never go to the public host: the player reads them from the shell (shell-config.json, inside the APK)
pc = os.path.join(out, 'poc-config.js')
if os.path.exists(pc):
    open(pc, 'w').write("/* Screen settings come from the app (shell-config.json, inside the APK), never from the public update host */\n"
                        "window.POC_CONFIG = (function () { try { return JSON.parse(window.SctvShell.config()); } catch (e) { return null; } })();\n")
entry = os.path.join(out, o.entry); html = open(entry, encoding='utf-8').read()
html = html.replace('<head>', '<head>\n' + HOOK, 1) if '<head>' in html else HOOK + html
open(entry, 'w', encoding='utf-8').write(html)
files = []
for d, _, fs in os.walk(out):
    for f in sorted(fs):
        p = os.path.join(d, f); rel = os.path.relpath(p, out).replace(os.sep, '/'); b = open(p, 'rb').read()
        files.append({'path': rel, 'size': len(b), 'sha256': hashlib.sha256(b).hexdigest()})
man = {'build': o.build, 'version': o.version or f'build {o.build}', 'created': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds'),
       'entry': o.entry, 'rollout': max(0, min(100, o.rollout)), 'minShell': o.min_shell, 'files': sorted(files, key=lambda x: x['path'])}
raw = (json.dumps(man, indent=1) + '\n').encode()
key = serialization.load_pem_private_key(open(o.key, 'rb').read(), password=None)
open(man_path, 'wb').write(raw); open(os.path.join(o.site, 'manifest.sig'), 'w').write(base64.b64encode(key.sign(raw, ec.ECDSA(hashes.SHA256()))).decode() + '\n')
open(os.path.join(o.site, '.nojekyll'), 'w').write('')
import re   # the host is public: warn about anything that looks like a device or record ID
ids = sorted({m for f in files for m in re.findall(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', open(os.path.join(out, f['path']), encoding='utf-8', errors='ignore').read(), re.I)})
if ids: print('WARNING: the release contains ID-like values; the update host is public, so check before pushing:', ', '.join(ids[:5]))   # GitHub Pages: serve every file as-is
if o.floor:
    fl = os.path.join(HERE, 'android/app/src/main/assets/floor'); shutil.rmtree(fl, ignore_errors=True); shutil.copytree(out, fl)
print(f"released build {o.build} ({man['version']}): {len(files)} file(s), rollout {man['rollout']}%{', test build that never reports ready' if o.broken else ''}{', and put in the app as its built-in player' if o.floor else ''}")
