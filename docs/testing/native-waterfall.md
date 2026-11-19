# Native waterfall rollout

Feed and Reels continue requesting through their existing AdMob native unit IDs. Add AppLovin as a **Waterfall** source in each matching Android Native mediation group, map the AppLovin SDK key and native zone supplied by your AppLovin dashboard, and disable any unwanted sources in those groups. The existing Meta adapter is pinned at 6.16.0.0 for compatibility but its placement should not be mapped to these groups. Unity cannot be set up as a new waterfall placement; Google lists that support as ended January 31, 2026.

Verify consent and AppLovin's native-zone eligibility, then check both native units separately in Ad Inspector on a device. Never substitute provider IDs for the AdMob native unit IDs in the app. Adapter compilation does not verify dashboard changes or ad fill.
