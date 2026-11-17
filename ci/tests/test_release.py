import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
SHA = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
ID = 'sha256:' + 'c' * 64

def machine(digest=DIGEST, state='started', health='passing', services=True):
    return {'state': state, 'image_ref': {'registry': 'registry.fly.io', 'repository': 'bitbrush', 'digest': digest},
            'config': {'services': [{}] if services else []}, 'checks': [{'name': 'health', 'status': health}]}

class ReleaseTest(unittest.TestCase):
    def test_admission(self):
        for event in ['push', 'workflow_dispatch', 'pull_request', 'other']:
            for ref in ['refs/heads/main', 'refs/heads/topic']:
                for auto in [False, True]:
                    for manual in [False, True]:
                        for verified in [False, True]:
                            expected = verified and ref == 'refs/heads/main' and ((event == 'push' and auto) or (event == 'workflow_dispatch' and manual))
                            self.assertEqual(release.admitted(event, ref, auto, manual, verified), expected)
    def test_freshness_is_fail_closed(self):
        with patch.object(release, 'run', return_value=SHA + '\trefs/heads/main\n'):
            self.assertTrue(release.fresh(SHA))
            self.assertFalse(release.fresh('d' * 40))
        for value in ['', SHA, 'bad\trefs/heads/main\n', SHA + '\trefs/heads/other\n']:
            with patch.object(release, 'run', return_value=value):
                with self.assertRaises(release.ReleaseError): release.fresh(SHA)
        with patch.object(release, 'run', side_effect=release.ReleaseError('E_COMMAND')):
            with self.assertRaises(release.ReleaseError): release.fresh(SHA)
    def test_archive_checksum_revision_and_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            archive = directory / 'image.tar'
            archive.write_bytes(b'original image')
            metadata = {'revision': SHA, 'image_id': ID, 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}
            (directory / 'image.json').write_text(json.dumps(metadata))
            inspect = {'Id': ID, 'Architecture': 'amd64', 'Os': 'linux', 'Config': {'Labels': {'org.opencontainers.image.revision': SHA}}}
            with patch.object(release, 'run', return_value='') as runner, patch.object(release, 'inspect_image', return_value=inspect):
                self.assertEqual(release.load_archive(directory, SHA), ID)
                self.assertEqual(runner.call_args.args[0][:2], ['docker', 'load'])
            for changed in [{'revision': 'd' * 40}, {'image_id': 'bad'}, {'sha256': 'e' * 64}]:
                (directory / 'image.json').write_text(json.dumps({**metadata, **changed}))
                with patch.object(release, 'run') as runner:
                    with self.assertRaises(release.ReleaseError): release.load_archive(directory, SHA)
                    runner.assert_not_called()
            (directory / 'image.json').write_text(json.dumps(metadata))
            for changed in [{'Id': 'sha256:' + 'd' * 64}, {'Config': {'Labels': {}}}, {'Architecture': 'arm64'}]:
                with patch.object(release, 'run', return_value=''), patch.object(release, 'inspect_image', return_value={**inspect, **changed}):
                    with self.assertRaises(release.ReleaseError): release.load_archive(directory, SHA)
    def test_save_original_image_without_rebuilding(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / 'transfer'
            inspect = {'Id': ID, 'Architecture': 'amd64', 'Os': 'linux', 'Config': {'Labels': {'org.opencontainers.image.revision': SHA}}}
            def save(args, **kwargs):
                self.assertEqual(args[:3], ['docker', 'save', '--output'])
                self.assertEqual(args[-1], 'bitbrush:verified')
                Path(args[3]).write_bytes(b'original-image')
                return ''
            with patch.object(release, 'inspect_image', return_value=inspect), patch.object(release, 'run', side_effect=save) as runner:
                release.save_archive('bitbrush:verified', SHA, directory, ID)
                self.assertEqual(runner.call_count, 1)
                self.assertEqual(json.loads((directory / 'image.json').read_text())['image_id'], ID)
            with patch.object(release, 'inspect_image', return_value={**inspect, 'Id': DIGEST}), patch.object(release, 'run') as runner:
                with self.assertRaises(release.ReleaseError): release.save_archive('bitbrush:verified', SHA, Path(tmp) / 'invalid', ID)
                runner.assert_not_called()

    def test_serving_machines(self):
        self.assertTrue(release.machines_ready([machine()], DIGEST))
        for machines in [[], [machine(state='stopped')], [machine(health='critical')],
                         [machine(), machine('sha256:' + 'd' * 64)],
                         [machine(), machine('sha256:' + 'd' * 64, state='stopped')],
                         [machine(), {'config': {'services': [{}]}}], [machine(services=False)]]:
            self.assertFalse(release.machines_ready(machines, DIGEST))
        self.assertTrue(release.machines_ready([machine(), machine(services=False, state='stopped')], DIGEST))
    def test_unreachable_machine_cannot_hide_old_serving_release(self):
        incomplete = machine('sha256:' + 'd' * 64)
        incomplete['incomplete_config'] = incomplete.pop('config')
        self.assertFalse(release.machines_ready([machine(), incomplete], DIGEST))
        for unknown in [{'state': 'started'}, {'state': 'stopped', 'config': None},
                        {'state': 'suspended', 'config': {}, 'incomplete_config': {'services': [{}]}}]:
            self.assertFalse(release.machines_ready([machine(), unknown], DIGEST))
        self.assertTrue(release.machines_ready([machine(), {'state': 'destroyed'}], DIGEST))

    def test_observed_digests_are_allowlisted(self):
        self.assertEqual(release.observed_digests([machine(), machine('not-a-digest'),
                         {'image_ref': {'digest': 'credential-material'}}]), DIGEST)
        self.assertEqual(release.observed_digests([]), 'unknown')
        self.assertEqual(release.observed_digests(None), 'unknown')

    def test_deploy_uses_digest_and_second_freshness_check(self):
        with patch.object(release, 'fresh', return_value=False), patch.object(release, 'run') as runner:
            self.assertFalse(release.deploy(SHA, DIGEST))
            runner.assert_not_called()
        with patch.object(release, 'fresh', return_value=True), patch.object(release, 'run', return_value='') as runner:
            self.assertTrue(release.deploy(SHA, DIGEST))
            args = runner.call_args.args[0]
            self.assertIn('registry.fly.io/bitbrush@' + DIGEST, args)
            self.assertIn('--ha=false', args)
            self.assertNotIn('--build-only', args)
            self.assertIn('--strategy', args)
        with patch.object(release, 'fresh', return_value=True), patch.object(release, 'run', side_effect=release.ReleaseError('E_COMMAND')):
            with self.assertRaises(release.ReleaseError): release.deploy(SHA, DIGEST)
    def test_publication_checks_freshness(self):
        with patch.object(release, 'fresh', return_value=False), patch.object(release, 'run') as runner:
            self.assertIsNone(release.publish(SHA, ID, '1', '1'))
            runner.assert_not_called()
        with patch.object(release, 'fresh', return_value=True), patch.object(release, 'run', side_effect=['', '', '', json.dumps({'digest': DIGEST})]) as runner:
            self.assertEqual(release.publish(SHA, ID, '1', '2'), DIGEST)
            self.assertEqual([c.args[0][:2] for c in runner.call_args_list],
                             [['flyctl', 'auth'], ['docker', 'tag'], ['docker', 'push'], ['docker', 'buildx']])
    def test_failed_commands_hide_raw_diagnostics(self):
        with self.assertRaises(release.ReleaseError) as failure:
            release.run([sys.executable, '-c',
                         "import sys; print('PRIVATE_SENTINEL'); print('PRIVATE_SENTINEL', file=sys.stderr); sys.exit(2)"])
        self.assertEqual(str(failure.exception), 'E_COMMAND')

    def test_smoke_failure_is_failure(self):
        with patch.object(release, 'run', side_effect=release.ReleaseError('E_COMMAND')):
            with self.assertRaises(release.ReleaseError): release.smoke()

if __name__ == '__main__': unittest.main()
