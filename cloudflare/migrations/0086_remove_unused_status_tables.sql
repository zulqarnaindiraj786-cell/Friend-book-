-- The application no longer uses these ephemeral indicators. Do not touch any
-- document, feed, message, request, reaction, comment or call data.
DROP TABLE IF EXISTS friendbook_cf_presence;
DROP TABLE IF EXISTS friendbook_cf_presence_sessions;
DROP TABLE IF EXISTS friendbook_cf_presence_sessions_fullcopy;
DROP TABLE IF EXISTS friendbook_cf_presence_sessions_copy;
DROP TABLE IF EXISTS friendbook_cf_typing;
DROP TABLE IF EXISTS friendbook_cf_typing_sessions;
DROP TABLE IF EXISTS friendbook_cf_typing_sessions_fullcopy;
