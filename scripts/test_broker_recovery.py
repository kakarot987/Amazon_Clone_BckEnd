"""Mock-only tests: never invoke real Docker or HTTP requests."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

file = Path(__file__).resolve().parent / "verify-broker-recovery.py"
spec = importlib.util.spec_from_file_location("broker_recovery", file)
drill = importlib.util.module_from_spec(spec)
spec.loader.exec_module(drill)


class FakeClient:
    base_url = "http://localhost:8443"

    def __init__(self, create_status=202):
        self.create_status = create_status

    def request(self, method, path, payload=None, token="user", headers=None):
        if method == "POST" and path == "/product-composite":
            return self.create_status, None
        if method == "GET" and path.startswith("/product-composite/"):
            return 404, None
        raise AssertionError("Unexpected request: " + method + " " + path)


class FakeDocker:
    def __init__(self, remote=False, failed_stop=False, failed_start=False, wrong_label=False):
        self.running = True
        self.remote = remote
        self.failed_stop = failed_stop
        self.failed_start = failed_start
        self.wrong_label = wrong_label
        self.calls = []

    def __call__(self, command, timeout=50):
        self.calls.append(command)
        if command[:3] == ["docker", "context", "inspect"]:
            return json.dumps("tcp://remote.example.invalid:2376" if self.remote
                              else "unix:///var/run/docker.sock")
        if command[:3] == ["docker", "inspect", "--format"]:
            return "other-project" if self.wrong_label else "portfolio-recovery-tests"
        if command[-3:] == ["ps", "-q", "rabbitmq"]:
            return "test-rabbitmq"
        if command[-5:] == ["ps", "--status", "running", "-q", "rabbitmq"]:
            return "test-rabbitmq" if self.running else ""
        if command[-2:] == ["stop", "rabbitmq"]:
            self.running = False
            if self.failed_stop:
                raise drill.DemoError("Fake stop failed")
            return ""
        if command[-2:] == ["start", "rabbitmq"]:
            if self.failed_start:
                raise drill.DemoError("Fake start failed")
            self.running = True
            return ""
        if "rabbitmq-diagnostics" in command:
            if not self.running:
                raise drill.DemoError("Fake broker not healthy")
            return ""
        raise AssertionError("Unexpected Docker call: " + repr(command))


def args(save=True):
    from argparse import Namespace
    return Namespace(execute=True, disposable_stack=True,
                     project_name="portfolio-recovery-tests", save_evidence=save, base_url="http://localhost:8443",
                     product_id=301123, attempts=3, interval=0.01)


class BrokerRecoveryTests(unittest.TestCase):
    def environment(self):
        return patch.dict(os.environ, {"ACCESS_TOKEN": "test-secret-user",
                                       "OPERATOR_TOKEN": "test-secret-operator",
                                       "DOCKER_HOST": ""}, clear=True)

    def test_requires_double_opt_in(self):
        for options in ([], ["--execute"], ["--disposable-stack"]):
            with self.subTest(options=options), self.assertRaises(SystemExit):
                drill.parse_args(options)
        self.assertTrue(drill.parse_args(
            ["--execute", "--disposable-stack",
             "--project-name", "portfolio-recovery-tests"]).execute)
        with self.assertRaises(SystemExit):
            drill.parse_args(["--execute", "--disposable-stack"])
        with self.assertRaises(SystemExit):
            drill.parse_args(["--execute", "--disposable-stack",
                              "--project-name", "default"])
        with self.environment():
            bad = args()
            bad.disposable_stack = False
            with self.assertRaises(drill.DemoError):
                drill.run_drill(bad, run=FakeDocker(), client=FakeClient())

    def test_remote_docker_refused_without_stopping(self):
        fake = FakeDocker(remote=True)
        with self.environment(), self.assertRaisesRegex(drill.DemoError, "nonlocal"):
            drill.run_drill(args(), run=fake, client=FakeClient())
        self.assertFalse(any(c[-2:] == ["stop", "rabbitmq"] for c in fake.calls))

    def test_unrelated_broker_project_is_rejected_before_stop(self):
        fake = FakeDocker(wrong_label=True)
        with self.environment(), self.assertRaisesRegex(drill.DemoError, "label"):
            drill.run_drill(args(), run=fake, client=FakeClient())
        self.assertFalse(any(c[-2:] == ["stop", "rabbitmq"] for c in fake.calls))

    def test_broker_stop_and_start_use_named_recovery_project(self):
        fake = FakeDocker()
        with self.environment(), patch.object(drill.demo, "check_gateway"), patch.object(
                drill.demo, "poll_aggregate"), patch.object(drill.demo, "check_operator"):
            drill.run_drill(args(save=False), run=fake, sleep=lambda _: None,
                            client=FakeClient())
        mutations = [c for c in fake.calls if c[-2:] in
                     (["stop", "rabbitmq"], ["start", "rabbitmq"])]
        self.assertEqual(2, len(mutations))
        for command in mutations:
            self.assertIn("docker-compose.recovery.yml", command)
            self.assertEqual("portfolio-recovery-tests",
                             command[command.index("--project-name") + 1])

    def test_failed_create_still_restores_without_report(self):
        fake = FakeDocker()
        with tempfile.TemporaryDirectory() as tmp, patch.object(
                drill.demo, "EVIDENCE_DIR", Path(tmp)), self.environment(), patch.object(
                drill.demo, "check_gateway"):
            with self.assertRaises(drill.DemoError):
                drill.run_drill(args(), run=fake, sleep=lambda _: None,
                                client=FakeClient(create_status=503))
            self.assertTrue(fake.running)
            self.assertEqual([], list(Path(tmp).iterdir()))
        self.assertTrue(any(c[-2:] == ["start", "rabbitmq"] for c in fake.calls))

    def test_success_saves_private_report_after_receipts(self):
        fake = FakeDocker()
        with tempfile.TemporaryDirectory() as tmp, patch.object(
                drill.demo, "EVIDENCE_DIR", Path(tmp)), self.environment(), patch.object(
                drill.demo, "check_gateway"), patch.object(
                drill.demo, "poll_aggregate") as poll, patch.object(
                drill.demo, "check_operator") as operator:
            self.assertEqual(0, drill.run_drill(args(), run=fake,
                                                sleep=lambda _: None,
                                                client=FakeClient()))
            poll.assert_called_once()
            operator.assert_called_once()
            reports = list(Path(tmp).glob("broker-recovery-*.json"))
            self.assertEqual(1, len(reports))
            self.assertEqual(0o600, reports[0].stat().st_mode & 0o777)
            content = reports[0].read_text()
            self.assertNotIn("test-secret-user", content)
            self.assertNotIn("test-secret-operator", content)
            self.assertEqual("passed",
                             json.loads(content)["checks"]["signed_consumer_receipts"])
        self.assertTrue(fake.running)

    def test_failed_restart_is_critical_and_not_reported_success(self):
        fake = FakeDocker(failed_start=True)
        with tempfile.TemporaryDirectory() as tmp, patch.object(
                drill.demo, "EVIDENCE_DIR", Path(tmp)), self.environment(), patch.object(
                drill.demo, "check_gateway"):
            with self.assertRaisesRegex(drill.DemoError, "CRITICAL"):
                drill.run_drill(args(), run=fake, sleep=lambda _: None,
                                client=FakeClient())
            self.assertEqual([], list(Path(tmp).iterdir()))


if __name__ == "__main__":
    unittest.main()
