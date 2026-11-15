-- Core schema for friendbook data migration from Firestore
-- Run after existing migrations (0001-0013)

-- Users table
CREATE TABLE IF NOT EXISTS users (
    uid TEXT PRIMARY KEY,
    display_name TEXT NOT NULL DEFAULT 'User',
    email TEXT,
    photo_url TEXT,
    bio TEXT,
    cover_url TEXT,
    birthday TEXT,
    work TEXT,
    education TEXT,
    current_city TEXT,
    hometown TEXT,
    relationship_status TEXT,
    relationship TEXT,
    website TEXT,
    pronouns TEXT,
    phone TEXT,
    phone_number TEXT,
    is_admin INTEGER DEFAULT 0,
    is_moderator INTEGER DEFAULT 0,
    banned INTEGER DEFAULT 0,
    is_verified INTEGER DEFAULT 0,
    suspended_until INTEGER DEFAULT 0,
    suspended_lifetime INTEGER DEFAULT 0,
    deactivated INTEGER DEFAULT 0,
    login_alerts INTEGER DEFAULT 0,
    tag_review_enabled INTEGER DEFAULT 0,
    professional_mode INTEGER DEFAULT 0,
    hide_reaction_counts INTEGER DEFAULT 0,
    show_active_status INTEGER DEFAULT 1,
    last_seen INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    friends_count INTEGER DEFAULT 0,
    stars INTEGER DEFAULT 0,
    tick_level TEXT DEFAULT 'none',
    subscriptions_enabled INTEGER DEFAULT 0,
    subscription_price INTEGER DEFAULT 0,
    profile_locked INTEGER DEFAULT 0,
    profile_pic_guard INTEGER DEFAULT 0,
    lookup_privacy TEXT DEFAULT 'Everyone',
    friends_list_privacy TEXT DEFAULT 'Public',
    email_privacy TEXT DEFAULT 'Only me',
    contact_privacy TEXT DEFAULT 'Only me',
    contact_info_privacy TEXT DEFAULT 'Only me',
    followers_privacy TEXT DEFAULT 'Public',
    tag_privacy TEXT DEFAULT 'Everyone',
    two_factor_pin TEXT DEFAULT '',
    favorite_uids TEXT DEFAULT '[]',
    restricted_uids TEXT DEFAULT '[]',
    close_friends_uids TEXT DEFAULT '[]',
    highlight_story_ids TEXT DEFAULT '[]',
    saved_reel_ids TEXT DEFAULT '[]',
    hobbies TEXT DEFAULT '[]',
    pinned_post_id TEXT,
    status_note TEXT,
    status_note_at INTEGER DEFAULT 0,
    daily_streak INTEGER DEFAULT 0,
    last_bonus_at INTEGER DEFAULT 0,
    followers TEXT DEFAULT '[]',
    following TEXT DEFAULT '[]',
    blocked TEXT DEFAULT '[]'
);

-- Posts table
CREATE TABLE IF NOT EXISTS posts (
    id TEXT PRIMARY KEY,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT 'User',
    author_photo TEXT,
    content TEXT,
    media_url TEXT,
    media_type TEXT DEFAULT 'none',
    mentions TEXT DEFAULT '[]',
    feeling TEXT,
    privacy TEXT DEFAULT 'Public',
    shared_from_id TEXT,
    shared_from_author TEXT,
    shared_from_content TEXT,
    shared_from_media_url TEXT,
    shared_from_media_type TEXT,
    location TEXT,
    group_id TEXT,
    post_type TEXT DEFAULT 'regular',
    group_approval_status TEXT DEFAULT 'approved',
    life_event TEXT,
    life_event_icon TEXT,
    life_event_title TEXT,
    qa_prompt TEXT,
    market_title TEXT,
    market_price TEXT,
    market_category TEXT,
    market_condition TEXT,
    market_sold INTEGER DEFAULT 0,
    page_id TEXT,
    page_name TEXT,
    page_photo TEXT,
    archived INTEGER DEFAULT 0,
    trashed_at INTEGER DEFAULT 0,
    bg_color INTEGER DEFAULT 0,
    likes INTEGER DEFAULT 0,
    comments INTEGER DEFAULT 0,
    shares INTEGER DEFAULT 0,
    views INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    saved_by TEXT DEFAULT '[]',
    subscribed_uids TEXT DEFAULT '[]',
    subscribed_by TEXT DEFAULT '[]',
    media_urls TEXT DEFAULT '[]',
    tagged_uids TEXT DEFAULT '[]',
    tagged_names TEXT DEFAULT '[]',
    poll_options TEXT DEFAULT '[]',
    poll_votes TEXT DEFAULT '{}',
    pinned_comment_id TEXT DEFAULT '',
    inline_comments TEXT DEFAULT '[]',
    reactions_map TEXT DEFAULT '{}',
    author_verified INTEGER DEFAULT 0,
    author_tick_level TEXT DEFAULT 'none'
);

-- Comments table (separate for scalability)
CREATE TABLE IF NOT EXISTS comments (
    id TEXT PRIMARY KEY,
    post_id TEXT NOT NULL,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT 'User',
    author_photo TEXT,
    content TEXT,
    image_url TEXT,
    parent_id TEXT,
    edited INTEGER DEFAULT 0,
    pinned INTEGER DEFAULT 0,
    likes INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    liked_by TEXT DEFAULT '[]',
    reactions TEXT DEFAULT '{}'
);

-- Messages table
CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    chat_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    sender_name TEXT NOT NULL,
    sender_photo TEXT,
    receiver_id TEXT,
    text TEXT,
    image_url TEXT,
    video_url TEXT,
    type TEXT DEFAULT 'text',
    voice_url TEXT,
    broadcast_id TEXT DEFAULT '',
    live_id TEXT DEFAULT '',
    live_host_id TEXT DEFAULT '',
    live_host_name TEXT DEFAULT '',
    live_host_photo TEXT DEFAULT '',
    live_title TEXT DEFAULT '',
    reply_to_text TEXT,
    reply_to_sender TEXT,
    call_type TEXT,
    call_status TEXT,
    call_duration_sec INTEGER DEFAULT 0,
    forwarded INTEGER DEFAULT 0,
    voice_duration_sec INTEGER DEFAULT 0,
    is_seen INTEGER DEFAULT 0,
    is_delivered INTEGER DEFAULT 0,
    is_deleted INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    reactions TEXT DEFAULT '{}',
    reactor_names TEXT DEFAULT '{}',
    reactor_photos TEXT DEFAULT '{}'
);

-- Chat rooms table
CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY,
    last_message TEXT,
    last_message_time INTEGER DEFAULT 0,
    is_group INTEGER DEFAULT 0,
    group_name TEXT,
    group_photo TEXT,
    created_by TEXT,
    only_admins_can_send INTEGER DEFAULT 0,
    admin_ids TEXT DEFAULT '[]',
    participants TEXT DEFAULT '[]',
    typing TEXT DEFAULT '{}',
    participant_names TEXT DEFAULT '{}',
    participant_photos TEXT DEFAULT '{}'
);

-- Groups table
CREATE TABLE IF NOT EXISTS groups (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL DEFAULT 'Group',
    about TEXT,
    category TEXT DEFAULT 'Community',
    photo_url TEXT,
    cover_url TEXT,
    admin_id TEXT NOT NULL,
    privacy TEXT DEFAULT 'Public',
    rules TEXT,
    pinned_post_id TEXT,
    post_permission TEXT DEFAULT 'Everyone',
    require_post_approval INTEGER DEFAULT 0,
    require_join_approval INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    members TEXT DEFAULT '[]',
    admin_ids TEXT DEFAULT '[]',
    moderator_ids TEXT DEFAULT '[]',
    pending_member_ids TEXT DEFAULT '[]',
    banned_member_ids TEXT DEFAULT '[]',
    invited_uids TEXT DEFAULT '[]'
);

-- Friend requests table
CREATE TABLE IF NOT EXISTS friend_requests (
    id TEXT PRIMARY KEY,
    from_uid TEXT NOT NULL,
    from_name TEXT NOT NULL,
    from_photo TEXT,
    to_uid TEXT NOT NULL,
    status TEXT DEFAULT 'pending',
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
);

-- Notifications table
CREATE TABLE IF NOT EXISTS notifications (
    id TEXT PRIMARY KEY,
    to_user_id TEXT NOT NULL,
    from_user_id TEXT NOT NULL,
    from_user_name TEXT NOT NULL DEFAULT 'Someone',
    from_user_photo TEXT,
    type TEXT NOT NULL,
    post_id TEXT,
    group_id TEXT,
    text TEXT,
    is_read INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
);

-- Stories table
CREATE TABLE IF NOT EXISTS stories (
    id TEXT PRIMARY KEY,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT 'User',
    author_photo TEXT,
    media_url TEXT,
    media_type TEXT DEFAULT 'image',
    caption TEXT,
    music_mood TEXT,
    privacy TEXT DEFAULT 'Public',
    bg_color INTEGER DEFAULT 0,
    font_style INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    expires_at INTEGER DEFAULT (strftime('%s', 'now') * 1000 + 86400000),
    viewed_by TEXT DEFAULT '[]',
    reactions TEXT DEFAULT '{}'
);

-- Reels table
CREATE TABLE IF NOT EXISTS reels (
    id TEXT PRIMARY KEY,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT 'User',
    author_photo TEXT,
    video_url TEXT,
    thumb_url TEXT,
    caption TEXT,
    likes INTEGER DEFAULT 0,
    comments INTEGER DEFAULT 0,
    shares INTEGER DEFAULT 0,
    favorites INTEGER DEFAULT 0,
    views INTEGER DEFAULT 0,
    duration_sec INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    is_from_post INTEGER DEFAULT 0,
    mirror_horizontal INTEGER DEFAULT 0,
    overlay_text TEXT DEFAULT '',
    overlay_color INTEGER DEFAULT 0xFFFFFFFF,
    overlay_bg_color INTEGER DEFAULT 0xCC000000,
    overlay_x REAL DEFAULT 0.5,
    overlay_y REAL DEFAULT 0.24,
    sticker_text TEXT DEFAULT '',
    sticker_x REAL DEFAULT 0.78,
    sticker_y REAL DEFAULT 0.32,
    sticker_scale REAL DEFAULT 1.0,
    filter_preset TEXT DEFAULT 'none',
    filter_overlay_color INTEGER DEFAULT 0,
    beauty_level INTEGER DEFAULT 0,
    playback_speed REAL DEFAULT 1.0,
    mute_original_audio INTEGER DEFAULT 0,
    audio_title TEXT DEFAULT '',
    audio_url TEXT DEFAULT '',
    sound_author_photo TEXT DEFAULT '',
    trim_start_ms INTEGER DEFAULT 0,
    trim_end_ms INTEGER DEFAULT 0,
    liked_by TEXT DEFAULT '[]',
    saved_by TEXT DEFAULT '[]',
    reactions TEXT DEFAULT '{}',
    inline_comments TEXT DEFAULT '[]'
);

-- Active calls table (replaces RTDB activeCall)
CREATE TABLE IF NOT EXISTS active_calls (
    chat_id TEXT PRIMARY KEY,
    caller_uid TEXT NOT NULL,
    callee_uid TEXT NOT NULL,
    offer TEXT,
    answer TEXT,
    ice_candidates TEXT DEFAULT '[]',
    status TEXT DEFAULT 'ringing',
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
);

-- User settings table (for misc settings not in users table)
CREATE TABLE IF NOT EXISTS user_settings (
    uid TEXT PRIMARY KEY,
    settings_json TEXT DEFAULT '{}',
    updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_posts_author ON posts(author_id);
CREATE INDEX IF NOT EXISTS idx_posts_created ON posts(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_posts_group ON posts(group_id);
CREATE INDEX IF NOT EXISTS idx_posts_type ON posts(post_type);
CREATE INDEX IF NOT EXISTS idx_posts_privacy ON posts(privacy);
CREATE INDEX IF NOT EXISTS idx_comments_post ON comments(post_id, created_at);
CREATE INDEX IF NOT EXISTS idx_comments_author ON comments(author_id);
CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id);
CREATE INDEX IF NOT EXISTS idx_chats_updated ON chats(last_message_time DESC);
CREATE INDEX IF NOT EXISTS idx_friend_requests_to ON friend_requests(to_uid, status);
CREATE INDEX IF NOT EXISTS idx_friend_requests_from ON friend_requests(from_uid);
CREATE INDEX IF NOT EXISTS idx_notifications_to ON notifications(to_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_read ON notifications(to_user_id, is_read);
CREATE INDEX IF NOT EXISTS idx_stories_author ON stories(author_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stories_expires ON stories(expires_at);
CREATE INDEX IF NOT EXISTS idx_reels_author ON reels(author_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reels_created ON reels(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_active_calls_caller ON active_calls(caller_uid);
CREATE INDEX IF NOT EXISTS idx_active_calls_callee ON active_calls(callee_uid);
CREATE INDEX IF NOT EXISTS idx_groups_admin ON groups(admin_id);
CREATE INDEX IF NOT EXISTS idx_groups_privacy ON groups(privacy);