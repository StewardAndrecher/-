#!/usr/bin/env python3
"""Run current client/desktop logic verification from the repository root; no compiler needed."""
from pathlib import Path
import subprocess,sys
if __name__=='__main__':
 client=Path(__file__).resolve().parents[1]/'campus-market-client'
 raise SystemExit(subprocess.call([sys.executable,'-B',str(client/'tools/test_quick.py'),*sys.argv[1:]],cwd=client))
