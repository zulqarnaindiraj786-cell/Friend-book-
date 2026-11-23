-- Inbox reads canonical and legacy membership separately. Put source before
-- path so each join seeks only the selected store rather than walking both.
-- The authoritative room document and its ACL are unchanged.
CREATE INDEX IF NOT EXISTS idx_cf_chat_participant_source
  ON friendbook_cf_chat_participants(participant_uid, source, chat_path);
