"""
ChildFocus - Flask App Factory
backend/app/__init__.py
"""

import os
from flask import Flask
from app.config import config_map


def create_app(config=None):
    if config is None:
        env = os.getenv("FLASK_ENV", "development")
        config = config_map.get(env, config_map["default"])

    app = Flask(__name__)
    app.config.from_object(config)

    # Register blueprints
    from app.routes.classify import classify_bp
    from app.routes.metadata import metadata_bp

    app.register_blueprint(classify_bp)
    app.register_blueprint(metadata_bp)

    return app
