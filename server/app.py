"""
Smart TV Media Player - Main Application
Flask server with REST API and web admin panel
"""
import os
import sys
import json
import secrets

# Fix Windows console encoding
if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
from flask import (
    Flask, request, jsonify, render_template,
    send_from_directory, redirect, url_for, make_response
)
from flask_cors import CORS
from models import db, User, MediaFile, Settings
from auth import (
    init_auth, generate_token, require_auth,
    require_api_key, generate_api_key
)
from media_processor import (
    init_processor, save_uploaded_file, allowed_file,
    delete_media_files
)
from playlist_engine import get_playlist_for_device

# ── App Configuration ────────────────────────────────────────────────
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
UPLOAD_FOLDER = os.path.join(BASE_DIR, 'uploads')
THUMBNAIL_FOLDER = os.path.join(BASE_DIR, 'thumbnails')

app = Flask(__name__,
            static_folder=os.path.join(BASE_DIR, 'static'),
            template_folder=os.path.join(BASE_DIR, 'templates'))

app.config['SECRET_KEY'] = secrets.token_hex(32)
app.config['SQLALCHEMY_DATABASE_URI'] = f"sqlite:///{os.path.join(BASE_DIR, 'media_player.db')}"
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
app.config['MAX_CONTENT_LENGTH'] = 500 * 1024 * 1024  # 500 MB max upload

CORS(app)
db.init_app(app)

# ── Initialization ───────────────────────────────────────────────────
def initialize_app():
    """Initialize database, create default admin, set up directories"""
    db.create_all()

    # Create default admin if none exists
    if not User.query.first():
        admin = User(username='admin')
        admin.set_password('admin')
        db.session.add(admin)
        db.session.commit()
        print("[OK] Default admin created: admin / admin")

    # Generate API key if not set
    if not Settings.get('api_key'):
        api_key = generate_api_key()
        Settings.set('api_key', api_key)
        print(f"[OK] API key for TV devices: {api_key}")

    # Default display duration
    if not Settings.get('default_duration'):
        Settings.set('default_duration', '10')

    # Initialize auth
    api_key = Settings.get('api_key')
    init_auth(app.config['SECRET_KEY'], api_key)

    # Initialize media processor
    init_processor(UPLOAD_FOLDER, THUMBNAIL_FOLDER)

    print("[OK] Server initialized")


# ══════════════════════════════════════════════════════════════════════
#  WEB PANEL ROUTES
# ══════════════════════════════════════════════════════════════════════

@app.route('/')
def index():
    """Redirect to login or dashboard"""
    token = request.cookies.get('auth_token')
    if token:
        from auth import decode_token
        if decode_token(token):
            return redirect(url_for('dashboard'))
    return redirect(url_for('login_page'))


@app.route('/login')
def login_page():
    """Render login page"""
    return render_template('login.html')


@app.route('/dashboard')
def dashboard():
    """Render admin dashboard"""
    token = request.cookies.get('auth_token')
    if not token:
        return redirect(url_for('login_page'))
    from auth import decode_token
    if not decode_token(token):
        return redirect(url_for('login_page'))
    return render_template('dashboard.html')


# ══════════════════════════════════════════════════════════════════════
#  AUTH API
# ══════════════════════════════════════════════════════════════════════

@app.route('/api/auth/login', methods=['POST'])
def api_login():
    """Authenticate admin user"""
    data = request.get_json()
    if not data:
        return jsonify({'error': 'Нет данных'}), 400

    username = data.get('username', '').strip()
    password = data.get('password', '')

    if not username or not password:
        return jsonify({'error': 'Введите логин и пароль'}), 400

    user = User.query.filter_by(username=username).first()
    if not user or not user.check_password(password):
        return jsonify({'error': 'Неверный логин или пароль'}), 401

    token = generate_token(user.id, user.username)

    response = make_response(jsonify({
        'message': 'Успешная авторизация',
        'token': token,
        'user': user.to_dict()
    }))
    response.set_cookie('auth_token', token, httponly=True, max_age=86400)
    return response


@app.route('/api/auth/logout', methods=['POST'])
def api_logout():
    """Logout admin user"""
    response = make_response(jsonify({'message': 'Вы вышли из системы'}))
    response.delete_cookie('auth_token')
    return response


@app.route('/api/auth/change-password', methods=['POST'])
@require_auth
def api_change_password():
    """Change admin password"""
    data = request.get_json()
    old_password = data.get('old_password', '')
    new_password = data.get('new_password', '')

    if not old_password or not new_password:
        return jsonify({'error': 'Заполните все поля'}), 400

    if len(new_password) < 4:
        return jsonify({'error': 'Минимальная длина пароля — 4 символа'}), 400

    user = User.query.get(request.user_id)
    if not user.check_password(old_password):
        return jsonify({'error': 'Неверный текущий пароль'}), 401

    user.set_password(new_password)
    db.session.commit()
    return jsonify({'message': 'Пароль успешно изменён'})


# ══════════════════════════════════════════════════════════════════════
#  MEDIA API (Admin)
# ══════════════════════════════════════════════════════════════════════

@app.route('/api/media', methods=['GET'])
@require_auth
def get_media():
    """Get all media files"""
    media_files = MediaFile.query.order_by(MediaFile.sort_order, MediaFile.created_at.desc()).all()
    return jsonify([m.to_dict() for m in media_files])


@app.route('/api/media/upload', methods=['POST'])
@require_auth
def upload_media():
    """Upload one or more media files"""
    if 'files' not in request.files:
        return jsonify({'error': 'Файлы не выбраны'}), 400

    files = request.files.getlist('files')
    if not files:
        return jsonify({'error': 'Файлы не выбраны'}), 400

    default_duration = int(Settings.get('default_duration', '10'))
    max_sort = db.session.query(db.func.max(MediaFile.sort_order)).scalar() or 0

    uploaded = []
    errors = []

    for file in files:
        if not file.filename:
            continue

        if not allowed_file(file.filename):
            errors.append(f"Неподдерживаемый формат: {file.filename}")
            continue

        result = save_uploaded_file(file)
        if result:
            max_sort += 1
            media = MediaFile(
                filename=result['filename'],
                original_name=result['original_name'],
                file_type=result['file_type'],
                display_type=result['display_type'],
                file_size=result['file_size'],
                display_duration=result.get('duration') or default_duration,
                priority=5,
                sort_order=max_sort,
                thumbnail=result['thumbnail'],
                converted_files=result['converted_files']
            )
            db.session.add(media)
            db.session.flush()
            uploaded.append(media.to_dict())
        else:
            errors.append(f"Ошибка загрузки: {file.filename}")

    db.session.commit()

    return jsonify({
        'uploaded': uploaded,
        'errors': errors,
        'message': f'Загружено файлов: {len(uploaded)}'
    })


@app.route('/api/media/<int:media_id>', methods=['PUT'])
@require_auth
def update_media(media_id):
    """Update media file settings (duration, priority, active, sort_order)"""
    media = MediaFile.query.get_or_404(media_id)
    data = request.get_json()

    if 'display_duration' in data:
        duration = int(data['display_duration'])
        if duration < 1:
            duration = 1
        if duration > 3600:
            duration = 3600
        media.display_duration = duration

    if 'priority' in data:
        priority = int(data['priority'])
        if priority < 1:
            priority = 1
        if priority > 10:
            priority = 10
        media.priority = priority

    if 'is_active' in data:
        media.is_active = bool(data['is_active'])

    if 'sort_order' in data:
        media.sort_order = int(data['sort_order'])

    db.session.commit()
    return jsonify(media.to_dict())


@app.route('/api/media/<int:media_id>', methods=['DELETE'])
@require_auth
def delete_media(media_id):
    """Delete a media file"""
    media = MediaFile.query.get_or_404(media_id)

    # Delete physical files
    delete_media_files(media.filename, media.thumbnail, media.converted_files)

    db.session.delete(media)
    db.session.commit()

    return jsonify({'message': 'Файл удалён'})


@app.route('/api/media/reorder', methods=['POST'])
@require_auth
def reorder_media():
    """Update sort order for all media files"""
    data = request.get_json()
    order = data.get('order', [])  # List of {id, sort_order}

    for item in order:
        media = MediaFile.query.get(item['id'])
        if media:
            media.sort_order = item['sort_order']

    db.session.commit()
    return jsonify({'message': 'Порядок обновлён'})


@app.route('/api/media/bulk-delete', methods=['POST'])
@require_auth
def bulk_delete_media():
    """Delete multiple media files"""
    data = request.get_json()
    ids = data.get('ids', [])

    deleted = 0
    for media_id in ids:
        media = MediaFile.query.get(media_id)
        if media:
            delete_media_files(media.filename, media.thumbnail, media.converted_files)
            db.session.delete(media)
            deleted += 1

    db.session.commit()
    return jsonify({'message': f'Удалено файлов: {deleted}'})


# ══════════════════════════════════════════════════════════════════════
#  SETTINGS API
# ══════════════════════════════════════════════════════════════════════

@app.route('/api/settings', methods=['GET'])
@require_auth
def get_settings():
    """Get application settings"""
    return jsonify({
        'api_key': Settings.get('api_key', ''),
        'default_duration': int(Settings.get('default_duration', '10'))
    })


@app.route('/api/settings', methods=['PUT'])
@require_auth
def update_settings():
    """Update application settings"""
    data = request.get_json()

    if 'default_duration' in data:
        duration = max(1, min(3600, int(data['default_duration'])))
        Settings.set('default_duration', str(duration))

    if 'regenerate_api_key' in data and data['regenerate_api_key']:
        new_key = generate_api_key()
        Settings.set('api_key', new_key)
        init_auth(app.config['SECRET_KEY'], new_key)
        return jsonify({
            'message': 'API-ключ обновлён',
            'api_key': new_key,
            'default_duration': int(Settings.get('default_duration', '10'))
        })

    return jsonify({
        'message': 'Настройки сохранены',
        'api_key': Settings.get('api_key', ''),
        'default_duration': int(Settings.get('default_duration', '10'))
    })


# ══════════════════════════════════════════════════════════════════════
#  STATS API
# ══════════════════════════════════════════════════════════════════════

@app.route('/api/stats', methods=['GET'])
@require_auth
def get_stats():
    """Get dashboard statistics"""
    total_files = MediaFile.query.count()
    active_files = MediaFile.query.filter_by(is_active=True).count()
    total_size = db.session.query(db.func.sum(MediaFile.file_size)).scalar() or 0

    # Count by type
    type_counts = {}
    for file_type in ['mp4', 'jpg', 'jpeg', 'xlsx']:
        count = MediaFile.query.filter_by(file_type=file_type).count()
        if count > 0:
            type_counts[file_type] = count

    return jsonify({
        'total_files': total_files,
        'active_files': active_files,
        'total_size': total_size,
        'type_counts': type_counts
    })


# ══════════════════════════════════════════════════════════════════════
#  TV DEVICE API (API Key Auth)
# ══════════════════════════════════════════════════════════════════════

@app.route('/api/tv/playlist', methods=['GET'])
@require_api_key
def tv_playlist():
    """Get current playlist for TV device with sync info"""
    media_files = MediaFile.query.filter_by(is_active=True).order_by(
        MediaFile.sort_order
    ).all()

    playlist_data = get_playlist_for_device(media_files)

    return jsonify({
        'playlist': playlist_data['items'],
        'total_items': len(playlist_data['items']),
        'total_cycle_duration': playlist_data['total_cycle_duration'],
        'server_time': playlist_data['server_time'],
        'version': _get_playlist_version()
    })


@app.route('/api/tv/file/<path:filename>', methods=['GET'])
@require_api_key
def tv_get_file(filename):
    """Download media file for TV device"""
    return send_from_directory(UPLOAD_FOLDER, filename)


@app.route('/api/tv/heartbeat', methods=['POST'])
@require_api_key
def tv_heartbeat():
    """TV device heartbeat (for monitoring)"""
    data = request.get_json() or {}
    device_name = data.get('device_name', 'Unknown')
    current_item = data.get('current_item', '')

    # For now, just acknowledge. Could be extended to track devices.
    return jsonify({
        'status': 'ok',
        'playlist_version': _get_playlist_version()
    })


# ── TV Pairing Logic ──────────────────────────────────────────────────
# In-memory store for active pairing codes
# Format: code -> { "api_key": str, "paired": bool, "created_at": float }
pairing_codes = {}

@app.route('/api/tv/discover', methods=['GET'])
def tv_discover():
    """Endpoint for TV auto-discovery"""
    return jsonify({
        'status': 'ok',
        'app': 'smart_tv_media_player'
    })

@app.route('/api/tv/pair/request', methods=['POST'])
def tv_pair_request():
    """TV requests a new pairing code"""
    import time
    import random
    
    # Clean up expired pairing codes (older than 5 mins)
    now = time.time()
    expired = [c for c, data in pairing_codes.items() if now - data['created_at'] > 300]
    for c in expired:
        pairing_codes.pop(c, None)

    # Generate a unique 6-digit code
    for _ in range(10):
        code = f"{random.randint(100000, 999999)}"
        if code not in pairing_codes:
            break
    else:
        return jsonify({'error': 'Не удалось сгенерировать код. Попробуйте позже.'}), 500

    api_key = Settings.get('api_key', '')
    pairing_codes[code] = {
        'api_key': api_key,
        'paired': False,
        'created_at': now
    }

    return jsonify({
        'pairing_code': code
    })

@app.route('/api/tv/pair/poll', methods=['GET'])
def tv_pair_poll():
    """TV polls to check if pairing code was confirmed by user"""
    import time
    code = request.args.get('code')
    if not code or code not in pairing_codes:
        return jsonify({'status': 'expired'}), 404

    data = pairing_codes[code]
    # Check if expired (5 minutes)
    if time.time() - data['created_at'] > 300:
        pairing_codes.pop(code, None)
        return jsonify({'status': 'expired'}), 404

    if data['paired']:
        # TV paired successfully, return API key
        api_key = data['api_key']
        # Remove from active codes after successful pairing
        pairing_codes.pop(code, None)
        return jsonify({
            'status': 'paired',
            'api_key': api_key
        })

    return jsonify({'status': 'pending'})

@app.route('/api/tv/pair/confirm', methods=['POST'])
@require_auth
def tv_pair_confirm():
    """Admin confirms pairing code from Web UI"""
    import time
    req_data = request.get_json() or {}
    code = req_data.get('code', '').strip()

    if not code or code not in pairing_codes:
        return jsonify({'error': 'Недействительный или истёкший код сопряжения'}), 400

    data = pairing_codes[code]
    if time.time() - data['created_at'] > 300:
        pairing_codes.pop(code, None)
        return jsonify({'error': 'Срок действия кода сопряжения истёк'}), 400

    # Mark as paired
    data['paired'] = True
    return jsonify({'status': 'ok', 'message': 'ТВ успешно сопряжен'})


def _get_playlist_version():
    """Get a version hash of the current playlist state"""
    import hashlib
    media_files = MediaFile.query.filter_by(is_active=True).all()
    version_str = '|'.join([
        f"{m.id}:{m.priority}:{m.display_duration}:{m.sort_order}:{m.is_active}"
        for m in media_files
    ])
    return hashlib.md5(version_str.encode()).hexdigest()[:12]


# ══════════════════════════════════════════════════════════════════════
#  FILE SERVING
# ══════════════════════════════════════════════════════════════════════

@app.route('/uploads/<path:filename>')
def serve_upload(filename):
    """Serve uploaded files (for admin preview)"""
    return send_from_directory(UPLOAD_FOLDER, filename)


@app.route('/thumbnails/<path:filename>')
def serve_thumbnail(filename):
    """Serve thumbnail files"""
    return send_from_directory(THUMBNAIL_FOLDER, filename)


# ══════════════════════════════════════════════════════════════════════
#  ERROR HANDLERS
# ══════════════════════════════════════════════════════════════════════

@app.errorhandler(404)
def not_found(error):
    if request.path.startswith('/api/'):
        return jsonify({'error': 'Не найдено'}), 404
    return redirect(url_for('login_page'))


@app.errorhandler(413)
def too_large(error):
    return jsonify({'error': 'Файл слишком большой (макс. 500 МБ)'}), 413


# ══════════════════════════════════════════════════════════════════════
#  MAIN
# ══════════════════════════════════════════════════════════════════════

if __name__ == '__main__':
    with app.app_context():
        initialize_app()

    print("\n" + "=" * 60)
    print("  Smart TV Media Player Server")
    print("  Панель управления: http://0.0.0.0:5000")
    print("  Логин по умолчанию: admin / admin")
    print("=" * 60 + "\n")

    app.run(host='0.0.0.0', port=5000, debug=True)
