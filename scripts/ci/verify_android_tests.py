"""Require executed, passing Android core suites before packaging candidates."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET

SUITES = ('RealtimeSoundGateTest', 'BannerSizeTest', 'R2VideoClientTest',
          'ProfileReadFlightsTest', 'AdSocialReadGateTest', 'RequestWindowCounterTest')


def verify(root):
    results = []
    for suite in SUITES:
        paths = list(Path(root).glob('**/TEST-com.friendbook.social.core.' + suite + '.xml'))
        if len(paths) != 1:
            raise ValueError('Missing or ambiguous Android test result: ' + suite)
        doc = ET.parse(paths[0]).getroot()
        counts = {name: int(doc.get(name, '0')) for name in ('tests', 'failures', 'errors', 'skipped')}
        if counts['tests'] < 1 or any(counts[name] for name in ('failures', 'errors', 'skipped')):
            raise ValueError('Android suite did not fully pass: ' + suite)
        if len(list(doc.iter('testcase'))) != counts['tests']:
            raise ValueError('Android suite execution count mismatch: ' + suite)
        results.append((suite, counts['tests']))
    return results


if __name__ == '__main__':
    results = verify('app/build/test-results/testReleaseUnitTest')
    lines = ['### Executed Android/JVM core suites (loopback/mocked auth; NOT device/capacity proof)']
    lines.extend(f'- {name}: {count} tests PASS, no skips' for name, count in results)
    print('\n'.join(lines))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf8') as stream:
            stream.write('\n'.join(lines) + '\n')
