import copy
import tomllib
import unittest
from pathlib import Path
from scripts.admin.verify_worker_rollout import validate


class WorkerRolloutGuard(unittest.TestCase):
    def setUp(self):
        self.config = tomllib.loads(Path('wrangler.toml').read_text())
        self.settings = {'bindings': [
            {'name': 'DB', 'type': 'd1', 'id': self.config['d1_databases'][0]['database_id']},
            {'name': 'ASSETS', 'type': 'assets'},
            {'name': 'ANALYTICS_ENGINE', 'type': 'analytics_engine', 'dataset': 'friendbook_views'},
            {'name': 'VIDEO_BUCKET', 'type': 'r2_bucket', 'bucket_name': 'friendbook'},
            {'name': 'LIVE_RELAY', 'type': 'durable_object_namespace', 'class_name': 'LiveRelay'},
            {'name': 'CALL_RELAY', 'type': 'durable_object_namespace', 'class_name': 'CallRelay'},
            {'name': 'PRIVATE', 'type': 'secret_text', 'text': 'must-not-export'},
        ] + [{'name': key, 'type': 'plain_text', 'text': value} for key, value in self.config['vars'].items()]}
        self.schedules = {'schedules': [{'cron': '*/15 * * * *'}]}

    def test_valid_and_no_secret_values_exported(self):
        result = validate(self.settings, self.schedules, self.config)
        self.assertNotIn('must-not-export', str(result))
        self.assertNotIn('text', {k for row in result for k in row})

    def test_wrong_resources_flags_and_cron_fail_closed(self):
        cases = []
        for name, key, value in [('DB', 'id', 'wrong'), ('VIDEO_BUCKET', 'bucket_name', 'wrong'),
                                  ('LIVE_RELAY', 'class_name', 'wrong'),
                                  ('D1_INTERACTIONS_ENABLED', 'text', 'false')]:
            settings = copy.deepcopy(self.settings)
            next(row for row in settings['bindings'] if row['name'] == name)[key] = value
            cases.append((settings, self.schedules))
        settings = copy.deepcopy(self.settings)
        settings['bindings'].append({'name': 'EXTRA', 'type': 'kv_namespace'})
        cases.append((settings, self.schedules))
        with self.assertRaisesRegex(ValueError, 'expected=.*actual=.*EXTRA'):
            validate(settings, self.schedules, self.config)
        cases.append((self.settings, {'schedules': []}))
        for settings, schedules in cases:
            with self.subTest(settings=settings), self.assertRaises(ValueError):
                validate(settings, schedules, self.config)
