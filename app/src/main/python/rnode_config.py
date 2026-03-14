"""
rnode_config.py â€” persisted RNode radio parameters.
Stores config as JSON. configure_rnode() in rns_worker.py reads from here
instead of using hardcoded values.

Defaults match the original hardcoded values:
  Frequency : 433.025 MHz
  Bandwidth : 31250 Hz
  TX Power  : 17 dBm
  SF        : 8
  CR        : 6  (means 4/6)
"""

import json
import os
import threading

_CONFIG_PATH = "/data/data/com.example.rnshello/files/rnode_config.json"
_lock = threading.Lock()

DEFAULTS = {
    "frequency": 433025000,   # Hz
    "bandwidth": 125000,   # Hz
    "txpower":          17,   # dBm  (0â€“17)
    "sf": 7,   # Spreading factor (6â€“12)
    "cr":                6,   # Coding rate denominator (5â€“8, meaning 4/5 to 4/8)
}

_config: dict = {}

def _load():
    global _config
    try:
        if os.path.exists(_CONFIG_PATH):
            with open(_CONFIG_PATH, "r") as f:
                loaded = json.load(f)
            # Merge with defaults so any new keys added later are present
            _config = {**DEFAULTS, **loaded}
        else:
            _config = dict(DEFAULTS)
    except Exception as e:
        print(f"rnode_config: load error {e}")
        _config = dict(DEFAULTS)

def _save():
    """Must be called under _lock."""
    try:
        os.makedirs(os.path.dirname(_CONFIG_PATH), exist_ok=True)
        with open(_CONFIG_PATH, "w") as f:
            json.dump(_config, f, indent=2)
    except Exception as e:
        print(f"rnode_config: save error {e}")

def get() -> dict:
    """Return a copy of the current config."""
    with _lock:
        return dict(_config)

def save(frequency: int, bandwidth: int, txpower: int, sf: int, cr: int) -> str:
    """Validate and persist new radio parameters."""
    errors = []
    if not (400_000_000 <= frequency <= 510_000_000):
        errors.append("Frequency must be between 400â€“510 MHz")
    if bandwidth not in (7800, 10400, 15600, 20800, 31250, 41700, 62500, 125000, 250000, 500000):
        errors.append("Invalid bandwidth value")
    if not (0 <= txpower <= 17):
        errors.append("TX power must be 0â€“17 dBm")
    if not (6 <= sf <= 12):
        errors.append("Spreading factor must be 6â€“12")
    if not (5 <= cr <= 8):
        errors.append("Coding rate must be 5â€“8 (meaning 4/5 to 4/8)")
    if errors:
        return "Error: " + "; ".join(errors)

    with _lock:
        _config["frequency"] = frequency
        _config["bandwidth"]  = bandwidth
        _config["txpower"]    = txpower
        _config["sf"]         = sf
        _config["cr"]         = cr
        _save()
    return "OK"

# Load on import
_load()

