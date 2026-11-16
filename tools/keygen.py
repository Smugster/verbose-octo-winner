#!/usr/bin/env python3
"""Make the signing key pair for player releases.

    python3 tools/keygen.py            (refuses to replace an existing key; add --force to replace it)

Writes keys/private.pem (KEEP SECRET: never commit it; whoever holds it can ship code to every screen) and puts the public key
into the app (android/app/src/main/java/com/cybersoft/sctvshell/ShellKeys.java). Replacing the key means building and
installing a new APK, because screens only trust the key built into them."""
import os, sys, base64
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__))); KEYS = os.path.join(HERE, 'keys'); PRIV = os.path.join(KEYS, 'private.pem')
if os.path.exists(PRIV) and '--force' not in sys.argv: sys.exit('keys/private.pem exists; add --force to replace it (screens then need a new APK)')
k = ec.generate_private_key(ec.SECP256R1()); os.makedirs(KEYS, exist_ok=True)
open(PRIV, 'wb').write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())); os.chmod(PRIV, 0o600)
pub = base64.b64encode(k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
open(os.path.join(KEYS, 'public.b64'), 'w').write(pub + '\n')
J = os.path.join(HERE, 'android/app/src/main/java/com/cybersoft/sctvshell/ShellKeys.java'); os.makedirs(os.path.dirname(J), exist_ok=True)
open(J, 'w').write('package com.cybersoft.sctvshell;\n\n/** The public key screens trust for player releases (made by tools/keygen.py). */\nfinal class ShellKeys {\n    static final String PUBLIC_KEY = "' + pub + '";\n    private ShellKeys() {}\n}\n')
print('made keys/private.pem (keep it secret) and put the public key into ShellKeys.java')
