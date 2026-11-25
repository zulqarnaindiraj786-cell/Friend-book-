-- The comment-page reaction aggregate needs both total reaction types and the
-- viewer's own type. Cover both from one indexed range scan rather than reading
-- reaction rows from the table after the resource/comment key lookup.
CREATE INDEX IF NOT EXISTS idx_cf_comment_reaction_cover
ON friendbook_cf_comment_reactions(resource,id,uid,type);
