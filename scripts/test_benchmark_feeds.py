import json
import unittest
from benchmark_feeds import observations, summary, SOURCES, timestamp

class BenchmarkTests(unittest.TestCase):
    def test_gbfs_observation_age_does_not_reset_when_feed_updates(self):
        data = {'version': '2.3', 'last_updated': 10000, 'data': {'stations': [
            {'station_id': '7046', 'last_reported': 9000, 'num_docks_available': 8,
             'is_installed': True, 'is_returning': True}]}}
        row = observations(json.dumps(data), 'lyon_gbfs', 10001)['7046']
        self.assertEqual(1001, row['effective_age_s'])
        self.assertFalse(row['fresh'])
        self.assertTrue(row['known'])

    def test_missing_and_malformed_data_never_count_as_fresh(self):
        data = {'version': '2.3', 'last_updated': 10000, 'data': {'stations': [
            {'station_id': '7046', 'last_reported': 10000, 'num_docks_available': None,
             'is_installed': True, 'is_returning': True}]}}
        rows = observations(json.dumps(data), 'lyon_gbfs', 10001)
        self.assertFalse(rows['7046']['known'])
        self.assertEqual('missing', rows['7013']['state'])

    def test_geojson_preserves_station_and_feed_times(self):
        data = {'features': [{'properties': {'number': 7046, 'status': 'OPEN', 'available_bike_stands': 4,
                'last_update': '2026-10-13T20:00:00Z', 'last_update_fme': '2026-10-13T20:00:30Z'}}]}
        now = timestamp('2026-10-13T20:01:00Z')
        row = observations(json.dumps(data), 'lyon_geojson', now)['7046']
        self.assertEqual(60, row['effective_age_s'])
        self.assertEqual(4, row['count'])
        self.assertTrue(row['fresh'])

    def test_failure_report_never_invents_a_fast_winner(self):
        records = [{'source': key, 'round': 1, 'within_5s': False, 'parsed': False,
                    'stations': {}, 'error': 'DNS failed', 'time_total': 0.001} for key, _ in SOURCES]
        result = summary(records, 12)
        self.assertIn('NO VALID DATA', result)
        self.assertIn('n/a', result)
        self.assertNotIn('0.001', result)

if __name__ == '__main__':
    unittest.main()
