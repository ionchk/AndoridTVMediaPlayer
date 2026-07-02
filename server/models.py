"""
Database models for Smart TV Media Player
"""
from flask_sqlalchemy import SQLAlchemy
from werkzeug.security import generate_password_hash, check_password_hash
from datetime import datetime

db = SQLAlchemy()


class User(db.Model):
    """Admin user for web panel"""
    __tablename__ = 'users'

    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(80), unique=True, nullable=False)
    password_hash = db.Column(db.String(256), nullable=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    def set_password(self, password):
        self.password_hash = generate_password_hash(password)

    def check_password(self, password):
        return check_password_hash(self.password_hash, password)

    def to_dict(self):
        return {
            'id': self.id,
            'username': self.username,
            'created_at': self.created_at.isoformat()
        }


class MediaFile(db.Model):
    """Uploaded media file with display settings"""
    __tablename__ = 'media_files'

    id = db.Column(db.Integer, primary_key=True)
    filename = db.Column(db.String(256), nullable=False)  # stored filename (UUID-based)
    original_name = db.Column(db.String(256), nullable=False)  # original upload name
    file_type = db.Column(db.String(10), nullable=False)  # mp4, jpg, jpeg, xlsx
    display_type = db.Column(db.String(10), nullable=False)  # video, image (after conversion)
    file_size = db.Column(db.Integer, default=0)  # bytes
    display_duration = db.Column(db.Integer, default=10)  # seconds to display (images)
    priority = db.Column(db.Integer, default=5)  # 1-10, higher = more frequent
    sort_order = db.Column(db.Integer, default=0)  # base ordering
    is_active = db.Column(db.Boolean, default=True)  # whether to include in playlist
    thumbnail = db.Column(db.String(256), nullable=True)  # thumbnail filename
    # For XLSX: stores multiple converted images
    converted_files = db.Column(db.Text, nullable=True)  # JSON list of converted filenames
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    def to_dict(self):
        import json
        result = {
            'id': self.id,
            'filename': self.filename,
            'original_name': self.original_name,
            'file_type': self.file_type,
            'display_type': self.display_type,
            'file_size': self.file_size,
            'display_duration': self.display_duration,
            'priority': self.priority,
            'sort_order': self.sort_order,
            'is_active': self.is_active,
            'thumbnail': self.thumbnail,
            'converted_files': json.loads(self.converted_files) if self.converted_files else [],
            'created_at': self.created_at.isoformat(),
            'updated_at': self.updated_at.isoformat() if self.updated_at else None
        }
        return result


class Settings(db.Model):
    """Global application settings"""
    __tablename__ = 'settings'

    id = db.Column(db.Integer, primary_key=True)
    key = db.Column(db.String(100), unique=True, nullable=False)
    value = db.Column(db.Text, nullable=True)

    @staticmethod
    def get(key, default=None):
        setting = Settings.query.filter_by(key=key).first()
        return setting.value if setting else default

    @staticmethod
    def set(key, value):
        setting = Settings.query.filter_by(key=key).first()
        if setting:
            setting.value = str(value)
        else:
            setting = Settings(key=key, value=str(value))
            db.session.add(setting)
        db.session.commit()
