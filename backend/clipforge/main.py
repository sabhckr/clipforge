"""ASGI entry point: `uvicorn clipforge.main:app`, or `python -m clipforge`."""
from .api import create_app

app = create_app()
