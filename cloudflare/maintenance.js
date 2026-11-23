import { drainBroadcastPush } from './fcm-push.js';
import { retryVideoCleanup } from './video-r2.js';

export async function runMaintenance(env, {now = Date.now(), videoCleanup = retryVideoCleanup, broadcastPush = drainBroadcastPush, report = console.error} = {}) {
  const jobs = [['temporary-video', () => videoCleanup(env)]];
  if (broadcastPush) jobs.push(['broadcast-push', () => broadcastPush(env,now)]);
  await Promise.all(jobs.map(async ([job, run]) => {
    try { await run(); }
    catch { report(JSON.stringify({event:'friendbook.maintenance_failed',job})); }
  }));
}
