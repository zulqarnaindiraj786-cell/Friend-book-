package com.friendbook.social.chat;

import com.friendbook.social.d1.documents.D1DocumentStoreException;

/** A missing acknowledgement or absent read is not proof that a write rolled back. */
final class ChatSendRecovery {
    enum State { DENIED, CONFIRMED, UNKNOWN }
    static State failure(Exception error) {
        return error instanceof D1DocumentStoreException &&
                ((D1DocumentStoreException) error).getCode()==D1DocumentStoreException.Code.PERMISSION_DENIED
                ? State.DENIED : State.UNKNOWN;
    }
    static State read(boolean exists, String sender, String actor) {
        return exists && actor != null && actor.equals(sender) ? State.CONFIRMED : State.UNKNOWN;
    }
}
