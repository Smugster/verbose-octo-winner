/* Screen settings come from the app (shell-config.json, inside the APK), never from the public update host */
window.POC_CONFIG = (function () { try { return JSON.parse(window.SctvShell.config()); } catch (e) { return null; } })();
