import os
from pathlib import Path
import pty
import select
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]

class PushHookTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name) / 'repo'
        self.remote = Path(self.temp.name) / 'remote.git'
        self.repo.mkdir()
        self.git('init', '-b', 'topic')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'user.name', 'Fixture')
        self.git('commit', '--allow-empty', '-m', 'fixture')
        subprocess.run(['git', 'init', '--bare', str(self.remote)], capture_output=True, check=True)
        self.git('remote', 'add', 'origin', str(self.remote))
        shutil.copytree(ROOT / '.githooks', self.repo / '.githooks')
        shutil.copy(ROOT / 'ci/install-hooks.sh', self.repo / 'install-hooks.sh')
        result = self.command('sh', 'install-hooks.sh')
        self.assertEqual(result.returncode, 0)
    def tearDown(self): self.temp.cleanup()
    def command(self, *args, override=False):
        env = {k: v for k, v in os.environ.items() if k != 'BITBRUSH_ALLOW_MAIN_PUSH'}
        if override: env['BITBRUSH_ALLOW_MAIN_PUSH'] = '1'
        return subprocess.run(args, cwd=self.repo, env=env, capture_output=True, start_new_session=True)
    def git(self, *args, **kwargs): return self.command('git', *args, **kwargs)
    def terminalPush(self, args, answer):
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(self.repo)
            os.environ.pop('BITBRUSH_ALLOW_MAIN_PUSH', None)
            os.execvp('git', ['git', 'push', 'origin', *args])
        output = b''
        deadline = time.monotonic() + 10
        sent = False
        try:
            while time.monotonic() < deadline:
                if select.select([fd], [], [], .1)[0]:
                    try: data = os.read(fd, 4096)
                    except OSError: break
                    if not data: break
                    output += data
                    if b'[y/N]' in output and not sent:
                        os.write(fd, answer)
                        sent = True
                done, status = os.waitpid(pid, os.WNOHANG)
                if done: return os.waitstatus_to_exitcode(status), output
            done, status = os.waitpid(pid, os.WNOHANG)
            if not done:
                import signal
                os.kill(pid, signal.SIGKILL)
                _, status = os.waitpid(pid, 0)
            return os.waitstatus_to_exitcode(status), output
        finally: os.close(fd)
    def test_other_branch_and_main_without_terminal(self):
        self.assertEqual(self.git('push', 'origin', 'topic').returncode, 0)
        self.assertNotEqual(self.git('push', 'origin', 'topic:main').returncode, 0)
    def test_override_and_normal_main(self):
        self.assertEqual(self.git('push', 'origin', 'topic:main', override=True).returncode, 0)
        self.git('checkout', '-b', 'main')
        self.git('commit', '--allow-empty', '-m', 'next')
        status, output = self.terminalPush(['main'], b'YeS\n')
        self.assertEqual(status, 0)
        self.assertEqual(output.count(b'[y/N]'), 1)
    def test_explicit_multiple_refs_prompt_once(self):
        status, output = self.terminalPush(['topic:refs/heads/main', 'topic:refs/heads/other'], b'y\n')
        self.assertEqual(status, 0)
        self.assertEqual(output.count(b'[y/N]'), 1)
    def test_deletion_decline_default_eof_and_override(self):
        self.assertEqual(self.git('push', 'origin', 'topic:main', override=True).returncode, 0)
        for answer in [b'n\n', b'\n', b'\x04']:
            status, output = self.terminalPush([':refs/heads/main'], answer)
            self.assertNotEqual(status, 0)
            self.assertEqual(output.count(b'[y/N]'), 1)
        self.assertEqual(self.git('push', 'origin', ':main', override=True).returncode, 0)
    def test_installer_conflicts(self):
        self.assertNotEqual(self.command('sh', 'install-hooks.sh').returncode, 0)
        self.git('config', '--unset', 'core.hooksPath')
        target = self.repo / '.git/hooks/pre-push'
        target.write_text('#!/bin/sh\nexit 0\n')
        target.chmod(0o755)
        self.assertNotEqual(self.command('sh', 'install-hooks.sh').returncode, 0)
        target.unlink()
        self.git('config', 'core.hooksPath', '/nonexistent/shared/hooks')
        self.assertNotEqual(self.command('sh', 'install-hooks.sh').returncode, 0)

if __name__ == '__main__': unittest.main()
