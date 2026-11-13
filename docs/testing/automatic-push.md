# Automatic notification onboarding — 2.8.14 (46)

New users: open app, sign in, accept Android's permission dialog. No Diagnostics visit, manual registration, server-status check or self-test button is part of onboarding. All account types use the central Firebase auth listener.

Acceptance: fresh account and restored account automatically create a per-install FCM token row on friendbook-push; repeat offline login/reconnect, token rotation, denied permission without crash/nagging, permission Allow, consent dialog focus recovery, cold restart, signout/switch accounts and a long transient gateway outage. Token registration must retry without the old fixed-attempt limit. Periodic maintenance is network-constrained and does not require a UI action; exact execution time is Android-controlled.

Native permission still requires consent. The app cannot guarantee receipt with denied permission, silent channels/DND, Force stop, no Google Play Services or connectivity restrictions. No physical-device acceptance is claimed by unit or structural wiring tests.

Settings still has ordinary notification preferences and an optional custom-chime preview. Internal status/error logging remains available to developers, not as user-required setup. Old support documents mentioning Diagnostics describe earlier builds, not the current flow.
