#!/usr/bin/env python3
"""Process/log IPC regressions for startup gating, independent of Minecraft/GL availability."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from multiplayer_startup import wait_for_graphics_ready

class StartupGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='discraft-startup-test-')
        self.log = Path(self.directory.name) / 'Alice.log'
        self.log.write_text('booting\n')
        self.children = []
    def tearDown(self):
        for child in self.children:
            if child.poll() is None: child.terminate()
            child.wait(timeout=5)
        self.directory.cleanup()
    def child(self, script):
        environment = os.environ.copy()
        for key in ('discord_token','discord_channel','DISCRAFT_DISCORD_BOT_TOKEN','DISCRAFT_DISCORD_CHANNEL'):
            environment.pop(key, None)
        child = subprocess.Popen([sys.executable, '-c', script, str(self.log)], env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.children.append(child)
        return child
    def test_readiness_must_arrive_before_next_client_starts(self):
        child = self.child("from pathlib import Path;import sys,time;p=Path(sys.argv[1]);time.sleep(.15);p.write_text('GL info: local test context ready\\n');time.sleep(5)")
        wait_for_graphics_ready(child, self.log, 'Alice', timeout=5, interval=.01)
        self.assertIn('GL info:', self.log.read_text())
        self.assertIsNone(child.poll())
    def test_actual_forge_failure_signature_rejects_alive_dialog_process(self):
        child = self.child('import time;time.sleep(5)')
        self.log.write_text('[pool-2-thread-1/ERROR] [EARLYDISPLAY/]: ERROR DISPLAY\nTimed out trying to setup the Game Window.\ny/n: ')
        with self.assertRaisesRegex(RuntimeError, 'graphical startup failed'):
            wait_for_graphics_ready(child, self.log, 'Alice', timeout=5, interval=.01)
        self.assertIsNone(child.poll())
    def test_main_exception_does_not_pass_a_readiness_marker(self):
        child = self.child('import time;time.sleep(5)')
        self.log.write_text('GL info: stale marker\nException in thread "main" java.lang.IllegalStateException\n')
        with self.assertRaisesRegex(RuntimeError, 'graphical startup failed'):
            wait_for_graphics_ready(child, self.log, 'Alice', timeout=5, interval=.01)
    def test_child_exit_and_missing_marker_are_bounded(self):
        child = self.child('pass');child.wait(timeout=5)
        with self.assertRaisesRegex(RuntimeError, 'exited during'):
            wait_for_graphics_ready(child, self.log, 'Alice', timeout=5, interval=.01)
        child = self.child('import time;time.sleep(5)')
        with self.assertRaisesRegex(RuntimeError, 'startup timeout'):
            wait_for_graphics_ready(child, self.log, 'Alice', timeout=.05, interval=.01)
    def test_native_game_progress_is_ready_when_optional_early_window_is_absent(self):
        child = self.child('import time;time.sleep(5)')
        self.log.write_text('MULTIPLAYER Alice stage=0 screen=TitleScreen\n')
        wait_for_graphics_ready(child, self.log, 'Alice', timeout=5, interval=.01)

if __name__ == '__main__': unittest.main()
