"""Create private LOCAL files only, never register accounts or print private keys/tokens."""
import argparse
import os
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory',required=True,type=Path)
parser.add_argument('--generate-provider-key',action='store_true')
args=parser.parse_args()
d=args.directory.expanduser().resolve()
if d.exists() and any(d.iterdir()):
    raise SystemExit('La carpeta no está vacía. No se sobrescribe ninguna clave existente.')
d.mkdir(parents=True,exist_ok=True);os.chmod(d,0o700)
def private(name,data):
    fd=os.open(d/name,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as f:f.write(data)
private('pairing-token.txt',(secrets.token_urlsafe(32)+'\n').encode())
private('state.key',secrets.token_bytes(32))
if args.generate_provider_key:
    key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    private('provider-private.pem',key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Vidal Finanzas personal AIS')])
    cert=(x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(datetime.now(timezone.utc)-timedelta(minutes=5))
          .not_valid_after(datetime.now(timezone.utc)+timedelta(days=365)).sign(key,hashes.SHA256()))
    (d/'provider-public.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
print('Archivos privados creados en',d)
print('No se ha contactado con el proveedor ni con el banco. No compartas la clave privada ni el token en el chat.')
if os.name=='nt':print('Windows: configura ACL de esta carpeta para tu usuario antes de usar datos reales. Recomendado ejecutar en Linux privado con permisos 0700/0600.')
