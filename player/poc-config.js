/* Screen settings come from the app (the SCREEN_DEVICE_ID secret, built into the APK), never from this public repository */
window.POC_CONFIG = (function () { try { return JSON.parse(window.SctvShell.config()); } catch (e) { return null; } })();
