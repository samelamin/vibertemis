#!/usr/bin/env python3
"""Exercise production lifecycle code in real subprocesses, without a GUI pump."""
import fcntl
import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import tempfile
import time
import threading
import hashlib
import unittest

ROOT = Path(__file__).resolve().parents[2]

class LifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix="vb-life-build-")
        supplied = os.environ.get("VIBERTEMIS_LIFECYCLE_PROBE")
        if supplied:
            cls.probe = supplied
        else:
            subprocess.run(["qmake6", str(ROOT / "tests/processlifecycle/processlifecycle.pro")], cwd=cls.build.name, check=True, stdout=subprocess.DEVNULL)
            subprocess.run(["make", "-j2"], cwd=cls.build.name, check=True, stdout=subprocess.DEVNULL)
            cls.probe = str(Path(cls.build.name) / "lifecycle_probe")

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="vb-life-")
        self.data = Path(self.tmp.name) / "data"
        self.runtime = Path(self.tmp.name) / "run"
        self.runtime.mkdir()
        self.children = []

    def tearDown(self):
        for child in self.children:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=3)
            child.stdout.close()
            child.stderr.close()
        self.tmp.cleanup()

    def start(self, mode, *args):
        child = subprocess.Popen([self.probe, mode, *map(str, args)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.children.append(child)
        return child

    def line(self, child, timeout=3):
        # Use unbuffered byte-level readiness to avoid TextIO read-ahead hiding
        # the next READY line from select(). Only one line is needed per child.
        self.assertTrue(select.select([child.stdout], [], [], timeout)[0], "child produced no output")
        line = child.stdout.readline().strip()
        self.assertTrue(line, f"child exited {child.poll()}")
        return line

    def primary(self, gaming=True, hung=False):
        child = self.start("hung-instance" if hung else "instance", self.data, self.runtime, "gaming" if gaming else "desktop")
        self.assertTrue(self.line(child).startswith("PRIMARY"))
        return child

    def request(self, request):
        path = next(self.runtime.glob("vb-*/ipc"))
        with socket.socket(socket.AF_UNIX) as client:
            client.settimeout(2)
            client.connect(str(path))
            client.sendall(json.dumps(request).encode() + b"\n")
            return json.loads(client.recv(2048))

    def test_sigterm_and_sigint_without_event_pump(self):
        for sig in (signal.SIGTERM, signal.SIGINT):
            child = self.start("signal")
            self.assertEqual(self.line(child), "READY")
            child.send_signal(sig)
            self.assertEqual(child.wait(timeout=2), 0)

    def test_fixed_deadline_with_blocked_main(self):
        child = self.start("stuck")
        self.assertEqual(self.line(child), "READY")
        start = time.monotonic(); child.terminate()
        self.assertEqual(child.wait(timeout=2), 70)
        self.assertGreater(time.monotonic() - start, .30)
        self.assertLess(time.monotonic() - start, 1.5)

    def test_repeated_requests_cannot_extend_deadline(self):
        start = time.monotonic(); child = self.start("repeat")
        self.assertEqual(child.wait(timeout=2), 70)
        self.assertLess(time.monotonic() - start, 1.5)

    def test_late_session_registration_observes_replacement(self):
        self.assertEqual(self.start("late").wait(timeout=2), 0)

    def test_unregister_waits_for_notification(self):
        self.assertEqual(self.start("registry").wait(timeout=2), 0)

    def test_cleanup_completion_owns_quit(self):
        started = time.monotonic()
        child = self.start("delayed-cleanup")
        self.assertEqual(child.wait(timeout=2), 0)
        self.assertGreater(time.monotonic() - started, .10)

    def test_old_cleanup_cannot_unregister_new_starting_session(self):
        self.assertEqual(self.start("owner-transition").wait(timeout=2), 0)

    def test_retained_recovery_descriptor_lifetime(self):
        self.assertEqual(self.start("retained").wait(timeout=2), 0)

    def test_replacement_during_pending_handoff_keeps_target(self):
        first = self.primary()
        pending = self.start("pending-instance", self.data, self.runtime, "gaming")
        self.assertEqual(self.line(pending), "PRIMARY paired-host 42")
        first.wait(timeout=2)
        replacement = self.start("instance", self.data, self.runtime, "gaming")
        self.assertEqual(self.line(replacement), "PRIMARY paired-host 42")
        self.assertEqual(pending.wait(timeout=2), 0)

    def test_replacement_after_sleep_recovery_keeps_target(self):
        recovery = self.start("recovery-instance", self.data, self.runtime, "gaming")
        self.assertTrue(self.line(recovery).startswith("PRIMARY"))
        replacement = self.start("instance", self.data, self.runtime, "gaming")
        self.assertEqual(self.line(replacement), "PRIMARY paired-host 42")
        self.assertEqual(recovery.wait(timeout=2), 0)

    def test_desktop_duplicate_activates_without_new_primary(self):
        primary = self.primary(False)
        duplicate = self.start("instance", self.data, self.runtime, "desktop")
        self.assertEqual(duplicate.communicate(timeout=3)[0].strip(), "ACTIVATED")
        self.assertIsNone(primary.poll())

    def test_gaming_launch_cannot_replace_desktop_primary(self):
        primary = self.primary(False)
        duplicate = self.start("instance", self.data, self.runtime, "gaming")
        self.assertEqual(duplicate.communicate(timeout=3)[0].strip(), "ACTIVATED")
        self.assertIsNone(primary.poll())

    def test_replacement_waits_for_death_and_transfers_handoff(self):
        primary = self.primary()
        replacement = self.start("instance", self.data, self.runtime, "gaming")
        self.assertEqual(self.line(replacement), "PRIMARY paired-host 42")
        self.assertEqual(primary.wait(timeout=2), 0)
        with open(self.data / "artemis-single-instance.lock", "rb") as lock:
            with self.assertRaises(BlockingIOError):
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_watchdog_releases_stuck_primary_lock(self):
        primary = self.primary(hung=True)
        replacement = self.start("instance", self.data, self.runtime, "gaming")
        self.assertEqual(self.line(replacement), "PRIMARY paired-host 42")
        self.assertEqual(primary.wait(timeout=2), 70)

    def test_stale_socket_after_crash(self):
        primary = self.primary(); primary.kill(); primary.wait(timeout=2)
        self.primary()

    def test_wrong_generation_does_not_replace(self):
        primary = self.primary()
        self.assertEqual(self.request({"op": "replace", "generation": "stale"})["state"], "stale")
        self.assertIsNone(primary.poll())

    def test_silent_client_cannot_stall_service(self):
        self.primary(False)
        path = next(self.runtime.glob("vb-*/ipc"))
        with socket.socket(socket.AF_UNIX) as client:
            client.connect(str(path))
            client.sendall(b'{"op":')
            time.sleep(1.15)
            self.assertIn("generation", self.request({"op": "status"}))

    def test_lost_replace_reply_never_activates_old_primary(self):
        self.data.mkdir()
        digest = hashlib.sha256(str(self.data.resolve()).encode()).hexdigest()[:16]
        socket_dir = self.runtime / ("vb-" + digest)
        socket_dir.mkdir(mode=0o700)
        operations = []
        stop = threading.Event()
        server = socket.socket(socket.AF_UNIX)
        server.bind(str(socket_dir / "ipc")); server.listen(); server.settimeout(.1)
        def serve():
            while not stop.is_set():
                try:
                    client, _ = server.accept()
                except socket.timeout:
                    continue
                with client:
                    client.settimeout(1)
                    request = json.loads(client.recv(2048))
                    operations.append(request["op"])
                    if request["op"] == "status":
                        reply = {"generation": "old", "started": "1", "gaming": True, "shuttingDown": False}
                        client.sendall(json.dumps(reply).encode() + b"\n")
                    # Simulate losing a replace request/reply. Never acknowledge.
        thread = threading.Thread(target=serve)
        thread.start()
        try:
            with open(self.data / "artemis-single-instance.lock", "wb") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                child = self.start("instance", self.data, self.runtime, "gaming")
                child.communicate(timeout=8)
                self.assertEqual(child.returncode, 10)
                self.assertEqual(operations.count("replace"), 1)
                self.assertNotIn("activate", operations)
        finally:
            stop.set(); thread.join(timeout=2); server.close()

    def test_missing_ipc_fails_without_second_primary(self):
        self.data.mkdir()
        with open(self.data / "artemis-single-instance.lock", "wb") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            child = self.start("instance", self.data, self.runtime, "gaming")
            stdout, stderr = child.communicate(timeout=8)
            self.assertEqual(child.returncode, 10)
            self.assertNotIn("PRIMARY", stdout)
            self.assertIn("still closing", stderr)

    def test_rapid_waiters_leave_one_primary(self):
        old = self.primary()
        waiters = [self.start("instance", self.data, self.runtime, "gaming") for _ in range(3)]
        lines = [self.line(child, 5) for child in waiters]
        self.assertEqual(sum(line.startswith("PRIMARY") for line in lines), 1, lines)
        old.wait(timeout=2)
        for child, line in zip(waiters, lines):
            if line == "ACTIVATED": self.assertEqual(child.wait(timeout=2), 0)

if __name__ == "__main__":
    unittest.main(verbosity=2)
