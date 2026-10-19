#!/usr/bin/env python3
"""Run the canonical backend verifier from the multi-project repository root."""
from pathlib import Path
import subprocess,sys
if __name__=='__main__':
    backend=Path(__file__).resolve().parents[1]/'campus-market-server'
    raise SystemExit(subprocess.call([sys.executable,'-B',str(backend/'scripts/test_backend.py'),*sys.argv[1:]],cwd=backend))
