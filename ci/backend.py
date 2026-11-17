#!/usr/bin/env python3
"""Disposable Compose lifecycle. Never forward container output/configuration."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.request

RUNTIME = Path('.ci-runtime/backend.env')

def compose(*args):
    result = subprocess.run(['docker', 'compose', '--project-name', 'bitbrush-ci',
                             '--env-file', str(RUNTIME), '-f', 'ci/backend/compose.yml', *args],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    if result.returncode: raise RuntimeError('E_COMPOSE')

def down():
    if RUNTIME.exists():
        compose('down', '--volumes', '--remove-orphans', '--timeout', '10')
        RUNTIME.unlink()

def up():
    if RUNTIME.exists(): raise RuntimeError('E_EXISTING_FIXTURE')
    os.umask(0o077)
    RUNTIME.parent.mkdir(exist_ok=True)
    image = os.environ.get('BITBRUSH_TEST_IMAGE', '')
    if not image or any(c.isspace() for c in image): raise RuntimeError('E_IMAGE')
    RUNTIME.write_text(f'BITBRUSH_TEST_IMAGE={image}\nCI_DATABASE_PASSWORD={secrets.token_hex(32)}\n'
                       f'CI_PIN_PEPPER={base64.b64encode(secrets.token_bytes(32)).decode()}\n')
    try:
        compose('up', '-d', '--wait', '--wait-timeout', '90')
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen('http://localhost:8080/actuator/health', timeout=3) as response:
                    if response.status == 200 and json.load(response).get('status') == 'UP':
                        print('BACKEND_HEALTH_OK')
                        return
            except Exception: pass
            time.sleep(2)
        raise RuntimeError('E_BACKEND_HEALTH_TIMEOUT')
    except BaseException:
        down()
        raise

if __name__ == '__main__':
    try:
        {'up': up, 'down': down}[sys.argv[1]]()
    except BaseException:
        print('E_BACKEND_FIXTURE', file=sys.stderr)
        sys.exit(1)
