import base64
import copy
import json
import secrets
import sys
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import Config, EncryptedStore, EnableBanking, BridgeError, create_app, now, stamp, normalize_transaction
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

class FakeProvider:
    configured = True
    def __init__(self):
        self.calls=[]
        self.pages=[{'transactions':[], 'continuation_key':None}]
        self.session={'session_id':'00000000-0000-4000-8000-000000000001','aspsp':{'name':'Ibercaja Banco','country':'ES'},'psu_type':'personal','access':{'valid_until':stamp(now()+timedelta(days=30))},'accounts':[{'uid':'00000000-0000-4000-8000-000000000002','currency':'EUR','identification_hash':'synthetic-stable-account'}]}
    def call(self, method, path, params=None, payload=None):
        self.calls.append((method,path,copy.deepcopy(params),copy.deepcopy(payload)))
        if path=='/aspsps':return {'aspsps':[{'name':'Ibercaja Banco','country':'ES','psu_types':['personal'],'maximum_consent_validity':15552000}]}
        if path=='/auth':return {'url':'https://auth.enablebanking.com/ais/start?sessionid=synthetic'}
        if path=='/sessions':return self.session
        if path.endswith('/transactions'):return self.pages.pop(0)
        if method=='DELETE':return {}
        raise AssertionError('Unexpected provider operation')

def movement(ref='ref1', amount='1234.56', status='BOOK'):
    return {'entry_reference':ref,'transaction_amount':{'currency':'EUR','amount':amount},'credit_debit_indicator':'DBIT','status':status,'booking_date':now().date().isoformat(),'remittance_information':['Compra ficticia']}

class BridgeTests(unittest.TestCase):
    def setUp(self):
        tmpbase=Path.home()/'.aki/tmp';tmpbase.mkdir(parents=True,exist_ok=True)
        self.tmp=tempfile.TemporaryDirectory(dir=tmpbase)
        d=Path(self.tmp.name)
        self.token=secrets.token_urlsafe(32)
        (d/'token').write_text(self.token);(d/'key').write_bytes(secrets.token_bytes(32))
        self.cfg=Config('https://bridge.example.test',d/'token',d/'key',d/'session.enc')
        self.store=EncryptedStore(self.cfg.state_file,self.cfg.state_key_file)
        self.provider=FakeProvider()
        self.app=create_app(self.cfg,self.provider,self.store)
        self.client=self.app.test_client();self.headers={'Authorization':'Bearer '+self.token}
    def tearDown(self):self.tmp.cleanup()
    def authorize(self):
        result=self.client.post('/v1/connect',json={},headers=self.headers)
        self.assertEqual(result.status_code,200)
        payload=self.provider.calls[-1][3]
        self.assertFalse(payload['access']['balances']);self.assertTrue(payload['access']['transactions'])
        state=payload['state']
        result=self.client.get('/callback',query_string={'state':state,'code':'synthetic-code'})
        self.assertEqual(result.status_code,200)
        return self.store.read()['session']['accounts'][0]['key'],state
    def test_authentication_required_and_no_public_data(self):
        self.assertEqual(self.client.get('/v1/status').status_code,401)
        self.assertEqual(self.client.get('/v1/accounts').status_code,401)
        self.assertEqual(self.provider.calls,[])
    def test_callback_csrf_and_replay_blocked(self):
        self.assertEqual(self.client.get('/callback?state=bad&code=bad').status_code,400)
        key,state=self.authorize()
        self.assertEqual(self.client.get('/callback',query_string={'state':state,'code':'synthetic-code'}).status_code,400)
        self.assertEqual(len([c for c in self.provider.calls if c[1]=='/sessions']),1)
    def test_flow_pagination_including_empty_page_and_exact_cents(self):
        key,_=self.authorize()
        self.provider.pages=[{'transactions':[],'continuation_key':'page-2'},{'transactions':[movement(),movement(status='PDNG')],'continuation_key':None}]
        result=self.client.post('/v1/accounts/'+key+'/sync',json={},headers=self.headers)
        self.assertEqual(result.status_code,200)
        self.assertTrue(result.json['complete']);self.assertEqual(len(result.json['transactions']),1)
        self.assertEqual(result.json['transactions'][0]['amount_cents'],-123456)
        self.assertNotIn('uid',result.json['account'])
        first,second=self.provider.calls[-2:]
        self.assertEqual(first[2]['date_from'],second[2]['date_from']);self.assertEqual(second[2]['continuation_key'],'page-2')
    def test_missing_stable_id_aborts_without_partial_list(self):
        key,_=self.authorize();bad=movement();bad.pop('entry_reference')
        self.provider.pages=[{'transactions':[movement(),bad]}]
        result=self.client.post('/v1/accounts/'+key+'/sync',json={},headers=self.headers)
        self.assertEqual(result.status_code,422);self.assertNotIn('transactions',result.json)
    def test_conflicting_id_aborts_and_repeated_page_aborts(self):
        key,_=self.authorize();self.provider.pages=[{'transactions':[movement(),movement(amount='5.00')]}]
        result=self.client.post('/v1/accounts/'+key+'/sync',json={},headers=self.headers)
        self.assertEqual(result.json['error'],'CONFLICTING_REFERENCE')
        self.app=create_app(self.cfg,self.provider,self.store);self.client=self.app.test_client()
        self.provider.pages=[{'transactions':[],'continuation_key':'same'},{'transactions':[],'continuation_key':'same'}]
        result=self.client.post('/v1/accounts/'+key+'/sync',json={},headers=self.headers)
        self.assertEqual(result.json['error'],'INCOMPLETE_SYNC')
    def test_foreign_currency_bad_precision_date_and_unknown_status_rejected(self):
        invalids=[movement(amount='1.001'),movement(amount='1.000000000000000000000000000001'),movement(status='UNRECOGNIZED'),movement(amount='NaN')]
        a=movement();a['transaction_amount']['currency']='USD';invalids.append(a)
        b=movement();b['booking_date']='2026-02-31';invalids.append(b)
        for row in invalids:
            with self.assertRaises(BridgeError):normalize_transaction(row)
        self.assertEqual(normalize_transaction(movement(amount='0.01'))['amount_cents'],-1)
    def test_invalid_nested_data_aborts_with_422(self):
        key,_=self.authorize()
        bad_amount=movement();bad_amount['transaction_amount']=None
        bad_party=movement();bad_party['remittance_information']=[];bad_party['creditor']='not-an-object'
        for row in (bad_amount,bad_party):
            with self.assertRaises(BridgeError):normalize_transaction(row)
            self.provider.pages=[{'transactions':[movement(),row]}]
            self.client=create_app(self.cfg,self.provider,self.store).test_client()
            response=self.client.post('/v1/accounts/'+key+'/sync',json={},headers=self.headers)
            self.assertEqual(response.status_code,422)
            self.assertEqual(response.json['error'],'INVALID_BANK_DATA')
            self.assertNotIn('transactions',response.json)
    def test_existing_connection_requires_explicit_revoke(self):
        self.authorize()
        self.client=create_app(self.cfg,self.provider,self.store).test_client()
        response=self.client.post('/v1/connect',json={},headers=self.headers)
        self.assertEqual(response.status_code,409)
        self.assertEqual(response.json['error'],'CONNECTION_EXISTS')

    def test_expired_session_and_revoke_failure_preserve_handle(self):
        key,_=self.authorize();s=self.store.read();s['session']['valid_until']=stamp(now()-timedelta(seconds=1));self.store.write(s)
        self.assertEqual(self.client.get('/v1/accounts',headers=self.headers).status_code,401)
        with patch.object(self.provider,'call',side_effect=BridgeError('PROVIDER_UNAVAILABLE','No disponible',502)):
            self.assertEqual(self.client.delete('/v1/connection',headers=self.headers).status_code,502)
        self.assertIn('session',self.store.read())
        self.assertEqual(self.client.delete('/v1/connection',headers=self.headers).status_code,200)
        self.assertEqual(self.store.read(),{})
    def test_invalid_account_identifier_object_is_rejected(self):
        self.provider.session['accounts'][0]['account_id']='malformed'
        result=self.client.post('/v1/connect',json={},headers=self.headers)
        state=self.provider.calls[-1][3]['state']
        response=self.client.get('/callback',query_string={'state':state,'code':'synthetic-code'})
        self.assertEqual(response.status_code,422)
        self.assertEqual(response.json['error'],'INVALID_BANK_DATA')
        self.assertIn('session',self.store.read())  # revocation handle retained
        self.assertNotIn('accounts',response.json)

    def test_at_rest_encryption_and_tamper_detection(self):
        self.authorize();raw=self.cfg.state_file.read_bytes()
        self.assertNotIn(b'accounts',raw);self.assertNotIn(b'Ibercaja',raw)
        self.cfg.state_file.write_bytes(raw[:-1]+bytes([raw[-1]^1]))
        with self.assertRaises(BridgeError):self.store.read()
    def test_jwt_rs256_verifies_and_payment_path_is_blocked(self):
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        pem=Path(self.tmp.name)/'synthetic.pem';pem.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        cfg=Config(self.cfg.public_url,self.cfg.token_file,self.cfg.state_key_file,self.cfg.state_file,'00000000-0000-4000-8000-000000000003',pem)
        provider=EnableBanking(cfg);header,body,signature=provider.jwt().split('.')
        key.public_key().verify(base64.urlsafe_b64decode(signature+'='*(-len(signature)%4)),(header+'.'+body).encode(),padding.PKCS1v15(),hashes.SHA256())
        claims=json.loads(base64.urlsafe_b64decode(body+'='*(-len(body)%4)))
        self.assertEqual(claims['aud'],'api.enablebanking.com');self.assertEqual(claims['exp']-claims['iat'],300)
        with self.assertRaises(BridgeError):provider.call('POST','/payments',payload={})
    def test_error_and_headers_do_not_disclose_bank_content(self):
        with patch.object(self.provider,'call',side_effect=RuntimeError('SECRET BANK TEXT')):
            r=self.client.post('/v1/connect',json={},headers=self.headers)
        self.assertEqual(r.status_code,500);self.assertNotIn(b'SECRET',r.data)
        self.assertEqual(r.headers['Cache-Control'],'no-store');self.assertEqual(r.headers['Referrer-Policy'],'no-referrer')

if __name__=='__main__':unittest.main(verbosity=2)
