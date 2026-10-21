"""Put src/scripts on sys.path so tests import the scripts the way they run: as plain modules."""
import sys
from pathlib import Path

SCRIPTS_DIR = str(Path(__file__).resolve().parents[1])
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)
