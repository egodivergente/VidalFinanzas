"""Single-owner AIS bridge. No payment API and no persistent transaction storage."""
from __future__ import annotations
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import threading
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation, localcontext
from pathlib import Path
from urllib.parse import quote, urlsplit
from uuid import UUID

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from flask import Flask, jsonify, request

UTC = timezone.utc
MAX_BODY = 20 * 1024 * 1024
MAX_ROWS = 50_000

class BridgeError(Exception):
    def __init__(self, code: str, message: str, status: int = 422):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status

def invalid():
    return BridgeError('INVALID_BANK_DATA', 'Datos bancarios incompletos o incompatibles. No se ha importado nada.')

def now():
    return datetime.now(UTC)

def stamp(value):
    return value.isoformat().replace('+00:00', 'Z')

def parse_time(value):
    try:
        d = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if d.tzinfo is None:
            raise ValueError()
        return d.astimezone(UTC)
    except (ValueError, TypeError, AttributeError):
        raise invalid()

def canonical(value):
    return json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode('utf-8')

def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')

@dataclass(frozen=True)
class Config:
    public_url: str
    token_file: Path
    state_key_file: Path
    state_file: Path
    application_id: str = ''
    private_key_file: Path | None = None
    environment: str = 'SANDBOX'
    bank_name: str = ''
    @classmethod
    def from_env(cls):
        root = Path(os.environ.get('VF_PRIVATE_DIR', Path.home() / '.vidal-finanzas-bridge'))
        return cls(os.environ.get('VF_PUBLIC_URL', ''), root/'pairing-token.txt', root/'state.key', root/'session.enc',
                   os.environ.get('VF_APPLICATION_ID', ''), Path(os.environ['VF_PROVIDER_KEY']) if os.environ.get('VF_PROVIDER_KEY') else None,
                   os.environ.get('VF_ENVIRONMENT', 'SANDBOX'), os.environ.get('VF_BANK_NAME', ''))
    def validate(self):
        u = urlsplit(self.public_url)
        if u.scheme != 'https' or not u.hostname or u.username or u.password or u.query or u.fragment or u.path not in ('', '/'):
            raise ValueError('VF_PUBLIC_URL debe ser un origen HTTPS, sin ruta, usuario ni parámetros.')
        if self.environment not in ('SANDBOX', 'PRODUCTION'):
            raise ValueError('Entorno no válido')
        if self.application_id:
            UUID(self.application_id)
        if self.environment == 'PRODUCTION' and self.bank_name:
            raise ValueError('En producción se descubre Ibercaja desde el catálogo, no se fuerza otro banco.')

class EncryptedStore:
    def __init__(self, path, key_file):
        self.path = Path(path)
        self.key = Path(key_file).read_bytes()
        if len(self.key) != 32:
            raise ValueError('Clave de almacenamiento no válida')
    def read(self):
        if not self.path.exists():
            return {}
        raw = self.path.read_bytes()
        try:
            return json.loads(AESGCM(self.key).decrypt(raw[:12], raw[12:], b'VFBRIDGE1'))
        except Exception:
            raise BridgeError('LOCAL_STATE_DAMAGED', 'Estado local dañado; no se sobrescribirá automáticamente.', 503)
    def write(self, value):
        nonce = secrets.token_bytes(12)
        encrypted = nonce + AESGCM(self.key).encrypt(nonce, canonical(value), b'VFBRIDGE1')
        temp = self.path.with_suffix('.new')
        with open(temp, 'wb') as f:
            os.chmod(temp, 0o600)
            f.write(encrypted)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp, self.path)

class EnableBanking:
    base = 'https://api.enablebanking.com'
    def __init__(self, config):
        self.config = config
    @property
    def configured(self):
        return bool(self.config.application_id and self.config.private_key_file and self.config.private_key_file.is_file())
    def jwt(self):
        if not self.configured:
            raise BridgeError('PROVIDER_NOT_CONFIGURED', 'Configura la aplicación y clave de Enable Banking en el servidor.', 503)
        key = serialization.load_pem_private_key(self.config.private_key_file.read_bytes(), password=None)
        if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 2048:
            raise BridgeError('INVALID_PROVIDER_KEY', 'La clave del proveedor debe ser RSA de al menos 2048 bits.', 503)
        ts = int(time.time())
        header = b64(canonical({'typ':'JWT', 'alg':'RS256', 'kid':self.config.application_id}))
        body = b64(canonical({'iss':'enablebanking.com', 'aud':'api.enablebanking.com', 'iat':ts, 'exp':ts+300}))
        signed = f'{header}.{body}'.encode('ascii')
        return f'{header}.{body}.{b64(key.sign(signed, padding.PKCS1v15(), hashes.SHA256()))}'
    def call(self, method, path, *, params=None, payload=None):
        permitted = (method == 'GET' and (path == '/aspsps' or re.fullmatch(r'/accounts/[0-9a-f-]{36}/transactions', path))) or (method == 'POST' and path in ('/auth', '/sessions')) or (method == 'DELETE' and re.fullmatch(r'/sessions/[0-9a-f-]{36}', path))
        if not permitted:
            raise BridgeError('OPERATION_NOT_ALLOWED', 'El puente solo permite consulta de cuentas y revocación.', 403)
        try:
            with requests.request(method, self.base+path, params=params, json=payload,
                                  headers={'Authorization':'Bearer '+self.jwt(), 'Accept':'application/json'},
                                  timeout=(10, 45), allow_redirects=False, stream=True) as response:
                raw = bytearray()
                for chunk in response.iter_content(65536):
                    raw.extend(chunk)
                    if len(raw) > MAX_BODY:
                        raise BridgeError('RESPONSE_TOO_LARGE', 'Respuesta demasiado grande; no se importó nada.', 502)
                try:
                    data = json.loads(raw) if raw else {}
                except ValueError:
                    raise invalid()
                if not 200 <= response.status_code < 300:
                    code = data.get('error') if isinstance(data, dict) else None
                    if method == 'DELETE' and (code == 'EXPIRED_SESSION' or response.status_code == 404):
                        return {}
                    if code == 'EXPIRED_SESSION':
                        raise BridgeError(code, 'El consentimiento ha caducado; vuelve a conectar.', 401)
                    if response.status_code == 429:
                        raise BridgeError('RATE_LIMITED', 'El proveedor limita las consultas. Inténtalo más tarde.', 429)
                    raise BridgeError('PROVIDER_ERROR', 'El proveedor no completó la operación. Revisa su panel sin compartir secretos.', 502)
                if not isinstance(data, dict):
                    raise invalid()
                return data
        except requests.RequestException:
            raise BridgeError('PROVIDER_UNAVAILABLE', 'No se pudo contactar con el proveedor. No se importó nada.', 502)

def normalize_transaction(t):
    if not isinstance(t, dict):
        raise invalid()
    status = t.get('status')
    if status != 'BOOK':
        if status in ('CNCL', 'HOLD', 'OTHR', 'PDNG', 'RJCT', 'SCHD'):
            return None
        raise invalid()
    ident = t.get('entry_reference')
    if not isinstance(ident, str) or not ident.strip() or len(ident) > 512:
        raise BridgeError('MISSING_STABLE_REFERENCE', 'El banco no aporta una referencia estable. Usa importación manual para evitar duplicados.')
    amount = t.get('transaction_amount', {})
    if not isinstance(amount, dict):
        raise invalid()
    raw = amount.get('amount')
    if amount.get('currency') != 'EUR' or not isinstance(raw, str) or len(raw) > 40 or not re.fullmatch(r'-?\d+(\.\d+)?', raw):
        raise invalid()
    try:
        with localcontext() as context:
            context.prec = 80
            value = abs(Decimal(raw)) * 100
            if value != value.to_integral_value() or value > 100_000_000_000:
                raise invalid()
            cents = int(value)
    except (InvalidOperation, ValueError):
        raise invalid()
    direction = t.get('credit_debit_indicator')
    if direction not in ('DBIT', 'CRDT'):
        raise invalid()
    cents = -cents if direction == 'DBIT' else cents
    booking = t.get('booking_date')
    value_date = t.get('value_date')
    try:
        for d in (booking, value_date):
            if d is None and d is value_date:
                continue
            if not isinstance(d, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', d) or not 1900 <= date.fromisoformat(d).year <= 2200:
                raise ValueError()
        if not booking:
            raise ValueError()
    except (ValueError, TypeError):
        raise invalid()
    info = t.get('remittance_information') or []
    if not isinstance(info, list) or any(not isinstance(x, str) for x in info):
        raise invalid()
    description = ' · '.join(x.strip() for x in info if x.strip())
    if not description:
        party = t.get('creditor' if direction == 'DBIT' else 'debtor') or {}
        if not isinstance(party, dict):
            raise invalid()
        description = party.get('name', '')
    if not isinstance(description, str) or not description.strip():
        description = 'Movimiento bancario ' + ident
    if len(description) > 10_000:
        raise invalid()
    return {'id':ident, 'date':booking, 'amount_cents':cents, 'description':description.strip(), 'value_date':value_date}

def normalize_accounts(raw):
    if not isinstance(raw, list) or not raw or len(raw) > 100:
        raise BridgeError('NO_LINKED_ACCOUNTS', 'No hay cuentas disponibles. En modo restricted vincula tu cuenta en el panel del proveedor.')
    result = []
    for a in raw:
        if not isinstance(a, dict) or a.get('currency') != 'EUR':
            raise invalid()
        identity = a.get('identification_hash')
        uid = a.get('uid')
        if not isinstance(identity, str) or not identity or len(identity) > 4096:
            raise invalid()
        try:
            uid = str(UUID(uid))
        except (ValueError, TypeError, AttributeError):
            raise invalid()
        key = hashlib.sha256(identity.encode()).hexdigest()
        account_id = a.get('account_id') or {}
        if not isinstance(account_id, dict):
            raise invalid()
        iban = account_id.get('iban', '')
        suffix = iban[-4:] if isinstance(iban, str) and re.fullmatch('[A-Z0-9]{10,34}', iban) else key[:6]
        result.append({'key':key, 'uid':uid, 'label':'Ibercaja · …'+suffix, 'currency':'EUR'})
    if len({a['key'] for a in result}) != len(result):
        raise invalid()
    return result

def create_app(config=None, provider=None, store=None):
    cfg = config or Config.from_env()
    cfg.validate()
    token = cfg.token_file.read_text(encoding='utf-8').strip()
    if len(token) < 32 or len(token) > 256:
        raise ValueError('Token de emparejamiento no válido')
    store = store or EncryptedStore(cfg.state_file, cfg.state_key_file)
    provider = provider or EnableBanking(cfg)
    app = Flask(__name__)
    app.config.update(MAX_CONTENT_LENGTH=4096, PROPAGATE_EXCEPTIONS=False)
    lock = threading.RLock()
    limits = {}

    @app.before_request
    def authenticate():
        if request.path.startswith('/v1/'):
            supplied = request.headers.get('Authorization', '')
            if not hmac.compare_digest(supplied, 'Bearer '+token):
                raise BridgeError('UNAUTHORIZED', 'Token del puente no válido.', 401)
            if request.method == 'POST' and request.get_json(silent=True) != {}:
                raise BridgeError('INVALID_REQUEST', 'Cuerpo de solicitud no válido.', 400)

    @app.after_request
    def safe_headers(response):
        response.headers['Cache-Control'] = 'no-store'
        response.headers['Pragma'] = 'no-cache'
        response.headers['Referrer-Policy'] = 'no-referrer'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['Content-Security-Policy'] = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"
        response.headers['Strict-Transport-Security'] = 'max-age=31536000'
        return response

    @app.errorhandler(BridgeError)
    def known_error(e):
        return jsonify(error=e.code, message=e.message), e.status

    @app.errorhandler(Exception)
    def safe_error(e):
        from werkzeug.exceptions import HTTPException
        if isinstance(e, HTTPException):
            return jsonify(error='HTTP_ERROR', message='Solicitud no válida.'), e.code
        # Never log exception details: provider/requests errors may contain financial data or tokens.
        return jsonify(error='INTERNAL_ERROR', message='No se completó la operación. Estado conservado.'), 500

    def limit(key, seconds=60):
        current = time.monotonic()
        if current-limits.get(key, -seconds) < seconds:
            raise BridgeError('RATE_LIMITED', 'Espera un minuto antes de repetir la operación.', 429)
        limits[key] = current

    def active(state):
        session = state.get('session')
        if not session or parse_time(session['valid_until']) <= now():
            raise BridgeError('EXPIRED_SESSION', 'No hay consentimiento activo. Conecta tu cuenta.', 401)
        return session

    def public_account(a):
        return {k:a[k] for k in ('key', 'label', 'currency')}

    @app.get('/v1/status')
    def status():
        with lock:
            session = store.read().get('session')
            connected = bool(session and parse_time(session['valid_until']) > now())
            return jsonify(provider='enablebanking', configured=provider.configured, connected=connected,
                           environment=cfg.environment, valid_until=session['valid_until'] if session else None)

    @app.post('/v1/connect')
    def connect():
        with lock:
            limit('connect')
            state = store.read()
            if state.get('session'):
                raise BridgeError('CONNECTION_EXISTS', 'Revoca la conexión anterior antes de autorizar otra.', 409)
            banks = provider.call('GET', '/aspsps', params={'country':'ES', 'psu_type':'personal', 'service':'AIS'}).get('aspsps', [])
            chosen = [b for b in banks if b.get('country') == 'ES' and 'personal' in b.get('psu_types', []) and
                      ((b.get('name') == cfg.bank_name) if cfg.bank_name and cfg.environment == 'SANDBOX' else 'ibercaja' in b.get('name', '').lower())]
            if len(chosen) != 1:
                raise BridgeError('BANK_NOT_AVAILABLE', 'No se ha encontrado una única integración Ibercaja compatible. Revisa el catálogo del proveedor.')
            bank = chosen[0]
            duration = bank.get('maximum_consent_validity')
            if not isinstance(duration, int) or duration < 60:
                raise invalid()
            secret_state = secrets.token_urlsafe(32)
            expiry = stamp(now()+timedelta(seconds=min(duration, 180*86400)-30))
            payload = {'access':{'valid_until':expiry, 'balances':False, 'transactions':True},
                       'aspsp':{'name':bank['name'], 'country':'ES'}, 'state':secret_state,
                       'redirect_url':cfg.public_url.rstrip('/')+'/callback', 'psu_type':'personal', 'language':'es'}
            response = provider.call('POST', '/auth', payload=payload)
            url = response.get('url', '')
            parsed = urlsplit(url)
            if parsed.scheme != 'https' or parsed.hostname != 'auth.enablebanking.com' or parsed.username or parsed.password or parsed.port not in (None, 443):
                raise invalid()
            state['pending'] = {'state_hash':hashlib.sha256(secret_state.encode()).hexdigest(), 'created':time.time(), 'aspsp':payload['aspsp']}
            store.write(state)
            return jsonify(authorization_url=url)

    @app.get('/callback')
    def callback():
        with lock:
            state = store.read()
            pending = state.get('pending', {})
            supplied = request.args.get('state', '')
            if len(request.args.getlist('state')) != 1 or not 20 <= len(supplied) <= 200 or not pending or not hmac.compare_digest(hashlib.sha256(supplied.encode()).hexdigest(), pending.get('state_hash', '')) or time.time()-pending.get('created', 0) > 600:
                raise BridgeError('INVALID_STATE', 'Autorización no válida o caducada. Inicia la conexión desde la app.', 400)
            del state['pending']
            store.write(state)  # consume before exchanging the single-use code
            if request.args.get('error'):
                return 'Autorización cancelada. No se ha conectado la cuenta. Vuelve a Vidal Finanzas.', 400
            code = request.args.get('code', '')
            if len(request.args.getlist('code')) != 1 or not 1 <= len(code) <= 4096:
                raise BridgeError('INVALID_CODE', 'No se recibió una autorización válida.', 400)
            result = provider.call('POST', '/sessions', payload={'code':code})
            session_id = str(UUID(result.get('session_id', '')))
            # Retain a revocation handle even if account validation fails, never orphan access silently.
            state['session'] = {'id':session_id, 'valid_until':stamp(now()), 'accounts':[]}
            store.write(state)
            if result.get('aspsp') != pending['aspsp'] or result.get('psu_type') != 'personal':
                raise invalid()
            valid_until = result.get('access', {}).get('valid_until')
            if parse_time(valid_until) <= now():
                raise invalid()
            accounts = normalize_accounts(result.get('accounts'))
            state['session'] = {'id':session_id, 'valid_until':valid_until, 'accounts':accounts}
            store.write(state)
            return 'Autorización recibida. Vuelve a Vidal Finanzas y pulsa Actualizar conexión. Todavía no se han importado movimientos.'

    @app.get('/v1/accounts')
    def accounts():
        with lock:
            session = active(store.read())
            return jsonify(accounts=[public_account(a) for a in session['accounts']], valid_until=session['valid_until'])

    @app.post('/v1/accounts/<key>/sync')
    def sync(key):
        with lock:
            session = active(store.read())
            account = next((a for a in session['accounts'] if a['key'] == key), None)
            if not account:
                raise BridgeError('ACCOUNT_NOT_ALLOWED', 'Cuenta no autorizada.', 403)
            limit('sync:'+key)
            until = now().date()
            start = until-timedelta(days=89)
            params = {'date_from':start.isoformat(), 'date_to':until.isoformat(), 'transaction_status':'BOOK'}
            rows, seen_pages, known = [], set(), {}
            deadline = time.monotonic()+150
            for _ in range(100):
                if time.monotonic() > deadline:
                    raise BridgeError('INCOMPLETE_SYNC', 'Consulta incompleta. No se ha importado nada.', 502)
                page = provider.call('GET', '/accounts/'+account['uid']+'/transactions', params=params.copy())
                transactions = page.get('transactions')
                if not isinstance(transactions, list):
                    raise invalid()
                for item in transactions:
                    row = normalize_transaction(item)
                    if row is None:
                        continue
                    if not start.isoformat() <= row['date'] <= until.isoformat():
                        raise invalid()
                    previous = known.get(row['id'])
                    if previous is not None:
                        if previous != row:
                            raise BridgeError('CONFLICTING_REFERENCE', 'Referencia bancaria repetida con datos diferentes. No se ha importado nada.')
                        continue
                    known[row['id']] = row
                    rows.append(row)
                    if len(rows) > MAX_ROWS:
                        raise invalid()
                continuation = page.get('continuation_key')
                if continuation is None:
                    result = {'account':public_account(account), 'transactions':rows, 'fetched_at':stamp(now()), 'date_from':start.isoformat(), 'date_to':until.isoformat(), 'complete':True}
                    if len(canonical(result)) > MAX_BODY:
                        raise invalid()
                    return jsonify(result)
                if not isinstance(continuation, str) or not continuation or len(continuation) > 8192 or continuation in seen_pages:
                    raise BridgeError('INCOMPLETE_SYNC', 'Paginación bancaria incompleta. No se ha importado nada.', 502)
                seen_pages.add(continuation)
                params['continuation_key'] = continuation
            raise BridgeError('INCOMPLETE_SYNC', 'Demasiadas páginas. No se ha importado nada.', 502)

    @app.delete('/v1/connection')
    def disconnect():
        with lock:
            state = store.read()
            if state.get('session'):
                provider.call('DELETE', '/sessions/'+state['session']['id'])
            store.write({})
            return jsonify(disconnected=True)
    return app

if __name__ == '__main__':
    import logging
    # The loopback development runner has no request logs, in particular no callback query strings.
    logging.getLogger('werkzeug').disabled = True
    create_app().run(host='127.0.0.1', port=8766, debug=False, use_reloader=False, threaded=False)
