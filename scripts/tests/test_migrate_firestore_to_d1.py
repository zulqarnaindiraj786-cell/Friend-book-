"""A migration that writes nothing must never report success.

The original script printed "CF_API_TOKEN not set, skipping actual upload" and then
returned True from send_batch, so every batch counted as a success against an empty
database. Combined with "Running in DRY RUN mode" - a mode that did not exist - a run
without credentials looked exactly like a completed cutover in the logs.

These tests pin the fail-closed behaviour without needing firebase-admin or a network.
"""
import importlib.util
import json
import os
import sys
from pathlib import Path
from unittest import mock
import unittest

PATH = Path(__file__).resolve().parents[1] / 'migrate_firestore_to_d1.py'


def load():
    """Import the script with its third-party deps stubbed out.

    firebase_admin and requests are not installed in every environment these tests run
    in, and neither is needed to check the token/exit-code logic.
    """
    stubs = {}
    for name in ('firebase_admin', 'firebase_admin.credentials', 'firebase_admin.firestore', 'requests'):
        stubs[name] = mock.MagicMock()
    with mock.patch.dict(sys.modules, stubs):
        spec = importlib.util.spec_from_file_location('migrate_d1', PATH)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    return module


class FailClosedWithoutToken(unittest.TestCase):
    def setUp(self):
        self.m = load()

    def test_send_batch_raises_without_a_token(self):
        self.m.CF_API_TOKEN = ''
        with self.assertRaises(self.m.MigrationError):
            self.m.send_batch('posts', [{'__id': 'p1'}])

    def test_send_batch_does_not_claim_success_without_a_token(self):
        # The old behaviour returned True here; nothing may silently succeed now.
        self.m.CF_API_TOKEN = ''
        try:
            result = self.m.send_batch('posts', [{'__id': 'p1'}])
        except self.m.MigrationError:
            return
        self.assertFalse(result, 'send_batch must not return True when nothing was uploaded')

    def test_main_refuses_to_start_without_a_token(self):
        self.m.CF_API_TOKEN = ''
        with mock.patch.object(self.m, 'init_firebase') as init:
            self.assertEqual(self.m.main(), 2)
            init.assert_not_called()

    def test_a_non_200_response_is_an_error(self):
        self.m.CF_API_TOKEN = 'token'
        response = mock.MagicMock(status_code=500, text='boom')
        with mock.patch.object(self.m.requests, 'post', return_value=response):
            self.assertFalse(self.m.send_batch('posts', [{'__id': 'p1'}]))

    def test_a_network_error_is_an_error(self):
        self.m.CF_API_TOKEN = 'token'
        with mock.patch.object(self.m.requests, 'post', side_effect=OSError('network down')):
            self.assertFalse(self.m.send_batch('posts', [{'__id': 'p1'}]))

    def test_only_a_200_is_success(self):
        self.m.CF_API_TOKEN = 'token'
        response = mock.MagicMock(status_code=200, text='')
        with mock.patch.object(self.m.requests, 'post', return_value=response):
            self.assertTrue(self.m.send_batch('posts', [{'__id': 'p1'}]))


class RefusesToReportEmptyMigration(unittest.TestCase):
    def test_the_script_never_exits_zero_having_written_nothing(self):
        text = PATH.read_text()
        self.assertIn('if total_docs == 0:', text)
        self.assertIn('Refusing to report success', text)
        # The old fake dry-run message must be gone.
        self.assertNotIn('Running in DRY RUN mode', text)
        # subcollection uploads must be checked, not fired and forgotten
        self.assertIn('if not send_batch("comments", batch):', text)
        self.assertIn('if not send_batch("messages", batch):', text)

    def test_exit_status_is_the_scripts_return_value(self):
        text = PATH.read_text()
        self.assertIn('sys.exit(main())', text)
        self.assertNotIn('\n    main()\n', text)


class UploadPayloadContract(unittest.TestCase):
    """The worker reads body.documents; the script must send exactly that."""

    def test_payload_shape_matches_the_worker(self):
        m = load()
        m.CF_API_TOKEN = 'token'
        captured = {}

        def post(url, json=None, headers=None, timeout=None):
            captured['url'] = url
            captured['json'] = json
            captured['headers'] = headers
            return mock.MagicMock(status_code=200, text='')

        with mock.patch.object(m.requests, 'post', side_effect=post):
            m.send_batch('posts', [{'__id': 'p1', 'content': 'hi'}])

        self.assertEqual(captured['json'], {'documents': [{'__id': 'p1', 'content': 'hi'}]})
        self.assertTrue(captured['url'].endswith('/migrate/posts'), captured['url'])
        self.assertTrue(captured['headers']['Authorization'].startswith('Bearer '))


if __name__ == '__main__':
    unittest.main()

class ServiceAccountSource(unittest.TestCase):
    """FIREBASE_SERVICE_ACCOUNT is a path in the docs but a JSON body in CI."""

    def test_json_body_is_used_as_credentials_not_as_a_path(self):
        m = load()
        body = '{"type": "service_account", "project_id": "friendbook-17551"}'
        m.SERVICE_ACCOUNT = body
        captured = {}

        def certificate(arg):
            captured['arg'] = arg
            return 'CRED'

        m.firebase_admin._apps = {}
        with mock.patch.object(m.credentials, 'Certificate', side_effect=certificate), \
             mock.patch.object(m.credentials, 'ApplicationDefault') as adc, \
             mock.patch.object(m.firebase_admin, 'initialize_app'), \
             mock.patch.object(m.firestore, 'client', return_value='FS'):
            m.init_firebase()
        self.assertEqual(captured['arg'], json.loads(body), 'the parsed JSON must be passed')
        adc.assert_not_called()

    def test_a_real_path_is_still_supported(self):
        m = load()
        m.SERVICE_ACCOUNT = __file__
        captured = {}
        m.firebase_admin._apps = {}
        with mock.patch.object(m.credentials, 'Certificate', side_effect=lambda a: captured.setdefault('arg', a)), \
             mock.patch.object(m.firebase_admin, 'initialize_app'), \
             mock.patch.object(m.firestore, 'client', return_value='FS'):
            m.init_firebase()
        self.assertEqual(captured['arg'], __file__)

    def test_invalid_json_raises_instead_of_falling_back_to_adc(self):
        m = load()
        m.SERVICE_ACCOUNT = '{not valid json'
        m.firebase_admin._apps = {}
        with mock.patch.object(m.credentials, 'ApplicationDefault') as adc:
            with self.assertRaises(ValueError):
                m.init_firebase()
        adc.assert_not_called()
