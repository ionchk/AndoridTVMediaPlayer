"""
JWT Authentication module for Smart TV Media Player
"""
import jwt
import datetime
import secrets
from functools import wraps
from flask import request, jsonify


SECRET_KEY = None
API_KEY = None


def init_auth(app_secret_key, api_key):
    """Initialize auth module with keys"""
    global SECRET_KEY, API_KEY
    SECRET_KEY = app_secret_key
    API_KEY = api_key


def generate_token(user_id, username):
    """Generate JWT token for web panel admin"""
    payload = {
        'user_id': user_id,
        'username': username,
        'exp': datetime.datetime.utcnow() + datetime.timedelta(hours=24),
        'iat': datetime.datetime.utcnow()
    }
    return jwt.encode(payload, SECRET_KEY, algorithm='HS256')


def decode_token(token):
    """Decode and validate JWT token"""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=['HS256'])
        return payload
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None


def require_auth(f):
    """Decorator: require JWT token for web panel endpoints"""
    @wraps(f)
    def decorated(*args, **kwargs):
        token = None

        # Check Authorization header
        auth_header = request.headers.get('Authorization')
        if auth_header and auth_header.startswith('Bearer '):
            token = auth_header.split(' ')[1]

        # Check cookie
        if not token:
            token = request.cookies.get('auth_token')

        if not token:
            return jsonify({'error': 'Требуется авторизация'}), 401

        payload = decode_token(token)
        if not payload:
            return jsonify({'error': 'Недействительный или истёкший токен'}), 401

        request.user_id = payload['user_id']
        request.username = payload['username']
        return f(*args, **kwargs)

    return decorated


def require_api_key(f):
    """Decorator: require API key for TV device endpoints"""
    @wraps(f)
    def decorated(*args, **kwargs):
        api_key = request.headers.get('X-API-Key')
        if not api_key:
            api_key = request.args.get('api_key')

        if not api_key or api_key != API_KEY:
            return jsonify({'error': 'Недействительный API-ключ'}), 403

        return f(*args, **kwargs)

    return decorated


def generate_api_key():
    """Generate a random API key for TV devices"""
    return secrets.token_urlsafe(32)
