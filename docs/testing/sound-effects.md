# Sound effects — current behavior (2.8.21 / 53)

Only foreground chat send/receive tones remain. All post/reel reactions, comments and Live gift audio has been removed at the user's request, along with those WAV assets. Existing Live animations and gift prices remain. The older Like/Comment replaceable assets and public helper methods are no longer available.

`core/SoundManager.kt` delegates to one shared SoundPool in SoundEffects.java. `raw/sfx_message_sent.wav` is a 95ms original send tap; `raw/message_pop.wav` is a 140ms original incoming tap. Generated reproducibly by `scripts/audio/make_effects.py`. They are not licensed WhatsApp recordings and are not claimed to be identical. Only original generated samples are bundled.

Send audio is immediate local action feedback, not delivery proof. Incoming chat tones remain gated against first history/cache/pending writes/duplicate events. The visible chat suppresses its matching FCM channel ding; background/other-chat notification behavior and delivery limitations are unchanged. Quiet/in-app sound preferences, silent/vibrate/DND, media volume, foreground/account checks, stale-load rejection and lifecycle stop/release remain. No audio focus is requested.

See `comments-and-mentions.md` for this release's composer/mention changes and pending physical acceptance checks. CI/build checks do not establish auditory quality, device latency or real push delivery.
