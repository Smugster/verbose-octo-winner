import sys, base64
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
k = serialization.load_pem_private_key(open(sys.argv[1], 'rb').read(), password=None); raw = open(sys.argv[2], 'rb').read()
open(sys.argv[3], 'w').write(base64.b64encode(k.sign(raw, ec.ECDSA(hashes.SHA256()))).decode() + '\n')
