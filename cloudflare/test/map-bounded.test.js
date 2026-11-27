import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mapBounded } from '../map-bounded.js';

test('a slow item does not prevent the next item from using a free slot', async () => {
  const started = [];
  const finish = [];
  const result = mapBounded([0, 1, 2, 3], 2, item => {
    started.push(item);
    return new Promise(resolve => { finish[item] = () => resolve(item * 10); });
  });
  assert.deepEqual(started, [0, 1]);
  finish[1]();
  await Promise.resolve();
  assert.deepEqual(started, [0, 1, 2]);
  finish[2]();
  await Promise.resolve();
  assert.deepEqual(started, [0, 1, 2, 3]);
  finish[3]();
  finish[0]();
  assert.deepEqual(await result, [0, 10, 20, 30]);
});
