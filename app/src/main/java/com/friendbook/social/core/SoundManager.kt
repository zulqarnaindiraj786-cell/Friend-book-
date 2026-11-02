package com.friendbook.social.core

import android.content.Context

/** Java-callable interaction facade; uses one preloaded chat-only SoundPool.
 * Local feedback is not delivery confirmation. Do not call from adapter binding or history.
 */
object SoundManager {
    @JvmStatic fun playMessageSent(context: Context?) = action(context, SoundEffects.SENT, Fb.uid())
    // Call only after the existing fresh-server-message gate, never on history/receipt updates.
    @JvmStatic fun playNewMessage(context: Context?) = SoundEffects.incoming(context)
    @JvmStatic fun action(context: Context?, effect: String, actor: String?) =
        SoundEffects.action(context, effect, actor)
    @JvmStatic fun preload(context: Context) = SoundEffects.warmUp(context)
    @JvmStatic fun stopAll() = SoundEffects.stopAll()
    @JvmStatic fun release() = SoundEffects.release()
}
