-- Disabled, additive backend pilot. No app cutover or legacy-row deletion.
CREATE TABLE IF NOT EXISTS friendbook_cf_interaction_resources (
    resource TEXT PRIMARY KEY NOT NULL,
    state TEXT NOT NULL DEFAULT 'staging' CHECK(state IN ('staging','verified'))
);
CREATE TABLE IF NOT EXISTS friendbook_cf_reactions (
    resource TEXT NOT NULL REFERENCES friendbook_cf_interaction_resources(resource),
    uid TEXT NOT NULL,
    type TEXT CHECK(type IS NULL OR type IN ('like','love','haha','wow','sad','angry')),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(resource,uid)
);
CREATE TABLE IF NOT EXISTS friendbook_cf_reaction_totals (
    resource TEXT NOT NULL,
    type TEXT NOT NULL,
    total INTEGER NOT NULL CHECK(total >= 0),
    PRIMARY KEY(resource,type)
);
CREATE TRIGGER IF NOT EXISTS friendbook_cf_reaction_insert AFTER INSERT ON friendbook_cf_reactions
WHEN NEW.type IS NOT NULL BEGIN
    INSERT INTO friendbook_cf_reaction_totals VALUES(NEW.resource,NEW.type,1)
    ON CONFLICT(resource,type) DO UPDATE SET total=total+1;
END;
CREATE TRIGGER IF NOT EXISTS friendbook_cf_reaction_change AFTER UPDATE OF type ON friendbook_cf_reactions
WHEN OLD.type IS NOT NEW.type BEGIN
    UPDATE friendbook_cf_reaction_totals SET total=total-1 WHERE resource=OLD.resource AND type=OLD.type;
    INSERT INTO friendbook_cf_reaction_totals SELECT NEW.resource,NEW.type,1 WHERE NEW.type IS NOT NULL
    ON CONFLICT(resource,type) DO UPDATE SET total=total+1;
END;
CREATE TABLE IF NOT EXISTS friendbook_cf_comments (
    resource TEXT NOT NULL REFERENCES friendbook_cf_interaction_resources(resource),
    id TEXT NOT NULL,
    uid TEXT NOT NULL,
    text TEXT NOT NULL,
    parent_id TEXT,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(resource,id),
    FOREIGN KEY(resource,parent_id) REFERENCES friendbook_cf_comments(resource,id)
);
CREATE INDEX IF NOT EXISTS friendbook_cf_comments_page ON friendbook_cf_comments(resource,created_at DESC,id DESC);
