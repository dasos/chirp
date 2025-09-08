from app import create_app

# WSGI entrypoint for production servers like gunicorn
app = create_app()

