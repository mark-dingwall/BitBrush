#!/usr/bin/env python3
"""Tested-image transfer and guarded Fly release. Diagnostics are constant codes.

Docker image IDs verify archive transfer. Fly registry manifest digests identify
releases; these identifiers are deliberately never compared to each other.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

APP = 'bitbrush'
REVISION = 'org.opencontainers.image.revision'
SHA = re.compile(r'^[0-9a-f]{40}$')
DIGEST = re.compile(r'^sha256:[0-9a-f]{64}$')

class ReleaseError(Exception): pass

def require(condition, code):
    if not condition: raise ReleaseError(code)

def run(args, timeout=300):
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout, encoding='utf-8')
        require(result.returncode == 0, 'E_COMMAND')
        return result.stdout
    except (OSError, subprocess.SubprocessError):
        raise ReleaseError('E_COMMAND') from None

def admitted(event, ref, auto, manual, verified):
    return bool(verified and ref == 'refs/heads/main' and
                ((event == 'push' and auto) or (event == 'workflow_dispatch' and manual)))

def fresh(revision):
    require(bool(SHA.fullmatch(revision)), 'E_REVISION')
    result = run(['git', 'ls-remote', '--exit-code', 'origin', 'refs/heads/main'], timeout=30).strip().splitlines()
    require(len(result) == 1, 'E_FRESHNESS')
    fields = result[0].split()
    require(len(fields) == 2 and SHA.fullmatch(fields[0]) and fields[1] == 'refs/heads/main', 'E_FRESHNESS')
    return fields[0] == revision

def inspect_image(image):
    data = json.loads(run(['docker', 'image', 'inspect', image]))
    require(isinstance(data, list) and len(data) == 1, 'E_IMAGE_INSPECT')
    return data[0]

def verify_image(data, image_id, revision):
    require(data.get('Id') == image_id and data.get('Architecture') == 'amd64' and
            data.get('Os') == 'linux' and data.get('Config', {}).get('Labels', {}).get(REVISION) == revision,
            'E_IMAGE_ID_OR_REVISION')

def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''): digest.update(chunk)
    return digest.hexdigest()

def save_archive(image, revision, directory, expected_id):
    require(bool(SHA.fullmatch(revision)) and bool(DIGEST.fullmatch(expected_id)), 'E_TRANSFER_METADATA')
    verify_image(inspect_image(image), expected_id, revision)
    directory.mkdir(parents=True, exist_ok=False)
    run(['docker', 'save', '--output', str(directory / 'image.tar'), image], timeout=300)
    metadata = {'revision': revision, 'image_id': expected_id, 'sha256': checksum(directory / 'image.tar')}
    (directory / 'image.json').write_text(json.dumps(metadata) + '\n')

def load_archive(directory, revision):
    data = json.loads((directory / 'image.json').read_text())
    require(SHA.fullmatch(revision) and data.get('revision') == revision and
            DIGEST.fullmatch(data.get('image_id', '')) and
            re.fullmatch('[0-9a-f]{64}', data.get('sha256', '')), 'E_TRANSFER_METADATA')
    require(checksum(directory / 'image.tar') == data['sha256'], 'E_ARCHIVE_CHECKSUM')
    run(['docker', 'load', '--input', str(directory / 'image.tar')], timeout=300)
    verify_image(inspect_image(data['image_id']), data['image_id'], revision)
    return data['image_id']

def publish(revision, image_id, run_id, attempt):
    # The checked-out commit has already passed verification in this workflow run.
    if not fresh(revision): return None
    require(bool(DIGEST.fullmatch(image_id)) and run_id.isdigit() and attempt.isdigit(), 'E_TAG')
    tag = f'registry.fly.io/{APP}:{revision}-{run_id}-{attempt}'
    run(['flyctl', 'auth', 'docker'])
    run(['docker', 'tag', image_id, tag])
    run(['docker', 'push', tag], timeout=300)
    manifest = json.loads(run(['docker', 'buildx', 'imagetools', 'inspect', '--format', '{{json .Manifest}}', tag]))
    digest = manifest.get('digest', '')
    require(bool(DIGEST.fullmatch(digest)), 'E_REGISTRY_DIGEST')
    return digest

def deploy(revision, digest):
    require(bool(DIGEST.fullmatch(digest)), 'E_REGISTRY_DIGEST')
    if not fresh(revision): return False
    run(['flyctl', 'deploy', '--app', APP, '--config', 'fly.toml', '--image',
         f'registry.fly.io/{APP}@{digest}', '--strategy', 'immediate', '--ha=false', '--yes'], timeout=600)
    return True

def machines_ready(machines, digest):
    if not isinstance(machines, list): return False
    active = [machine for machine in machines if machine.get('state') != 'destroyed']
    # An unreachable host can return incomplete_config instead of config. It may
    # recover and serve an old image: never infer 'no services' from missing data.
    if any(not isinstance(machine.get('config'), dict)
           or machine.get('incomplete_config') is not None for machine in active):
        return False
    serving = [machine for machine in active if machine['config'].get('services')]
    if not serving: return False
    # Stopped/suspended Machines can autostart: require their digest too.
    for machine in serving:
        image = machine.get('image_ref', {})
        if (image.get('registry') != 'registry.fly.io' or image.get('repository') != APP
                or image.get('digest') != digest): return False
    running = [machine for machine in serving if machine.get('state') == 'started']
    if not running: return False
    for machine in running:
        checks = machine.get('checks', [])
        if not checks or not any(check.get('name') == 'health' for check in checks): return False
        if not all(check.get('status') == 'passing' for check in checks): return False
    return True

def observed_digests(machines):
    if not isinstance(machines, list): return 'unknown'
    values = {machine.get('image_ref', {}).get('digest', '') for machine in machines}
    safe = sorted(value for value in values if isinstance(value, str) and DIGEST.fullmatch(value))
    return ','.join(safe) or 'unknown'

def wait_machines(digest):
    require(bool(DIGEST.fullmatch(digest)), 'E_REGISTRY_DIGEST')
    deadline = time.monotonic() + 180
    observed = 'unknown'
    while time.monotonic() < deadline:
        try:
            request = urllib.request.Request(f'https://api.machines.dev/v1/apps/{APP}/machines',
                headers={'Authorization': 'Bearer ' + os.environ['FLY_API_TOKEN']})
            with urllib.request.urlopen(request, timeout=10) as response:
                machines = json.load(response)
            observed = observed_digests(machines)
            if machines_ready(machines, digest):
                output('observed', digest)
                print(f'commit={os.environ["GITHUB_SHA"]} expected={digest} observed={digest} machines=passing')
                return
        except Exception:
            # A failed lookup never counts as healthy; retry within the bounded window.
            pass
        time.sleep(3)
    output('observed', observed)
    print(f'commit={os.environ["GITHUB_SHA"]} expected={digest} observed={observed} machines=failed')
    raise ReleaseError('E_SERVING_IMAGE_OR_HEALTH')

def smoke():
    # Safe reporter persists allowlisted test results, including on smoke failure.
    run(['npm', '--prefix', 'e2e', 'run', 'test:smoke'], timeout=180)

def output(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as target: target.write(f'{name}={value}\n')

def main():
    command = sys.argv[1]
    revision = os.environ.get('GITHUB_SHA', '')
    if command == 'save':
        expected_id = Path('.ci-runtime/tested-image-id').read_text().strip()
        save_archive(os.environ['BITBRUSH_TEST_IMAGE'], revision, Path('.ci-runtime/transfer'), expected_id)
    elif command == 'admit':
        allowed = admitted(os.environ.get('GITHUB_EVENT_NAME'), os.environ.get('GITHUB_REF'),
                           os.environ.get('AUTO_DEPLOY_ENABLED') == 'true',
                           os.environ.get('MANUAL_DEPLOY') == 'true', True)
        output('eligible', str(allowed).lower())
    elif command == 'publish':
        image_id = load_archive(Path('.ci-runtime/transfer'), revision)
        digest = publish(revision, image_id, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'])
        output('digest', digest or '')
        print(f'commit={revision} publication={"passed" if digest else "skipped-stale"}')
    elif command == 'deploy':
        deployed = deploy(revision, os.environ['RELEASE_DIGEST'])
        output('deployed', str(deployed).lower())
        print(f'commit={revision} deployment={"passed" if deployed else "skipped-stale"}')
    elif command == 'machines': wait_machines(os.environ['RELEASE_DIGEST'])
    elif command == 'smoke':
        smoke()
        print(f'commit={revision} smoke=passed')
    else: raise ReleaseError('E_COMMAND')

if __name__ == '__main__':
    try: main()
    except Exception as error:
        # Never print exceptions, chained subprocess output or machine configuration.
        print(str(error) if isinstance(error, ReleaseError) else 'E_RELEASE', file=sys.stderr)
        sys.exit(1)
