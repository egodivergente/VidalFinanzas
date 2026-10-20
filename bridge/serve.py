"""Single process, single owner, loopback listener behind explicitly configured HTTPS."""
import logging
from waitress import serve
from server import create_app
logging.getLogger('waitress').setLevel(logging.CRITICAL)
app=create_app()
serve(app,host='127.0.0.1',port=8766,threads=1,clear_untrusted_proxy_headers=True,channel_timeout=240)
