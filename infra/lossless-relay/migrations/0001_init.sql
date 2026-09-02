-- Rotation state only. Tokens live in the QOBUZ_ACCOUNTS secret, joined by label.
CREATE TABLE accounts (
  label         TEXT PRIMARY KEY,
  state         TEXT    NOT NULL DEFAULT 'live',   -- live | reserve | dead
  role          TEXT    NOT NULL DEFAULT 'playback', -- spec §5.3: kept for a later catalog/playback split; nothing reads it yet
  cooling_until INTEGER NOT NULL DEFAULT 0,        -- unix seconds; a transient failure parks an account here
  last_used_at  INTEGER NOT NULL DEFAULT 0,        -- the LRU key (spec §5.3)
  hour_key      TEXT    NOT NULL DEFAULT '',       -- "2026-09-01T17" (UTC)
  hour_n        INTEGER NOT NULL DEFAULT 0,
  day_key       TEXT    NOT NULL DEFAULT '',       -- "2026-09-01" (UTC)
  day_n         INTEGER NOT NULL DEFAULT 0,
  dead_reason   TEXT    NOT NULL DEFAULT ''
);

-- Shared mint cache (spec §6.3): one Qobuz call serves everyone playing that track for ~1 h.
CREATE TABLE mints (
  track_id      INTEGER NOT NULL,
  format_id     INTEGER NOT NULL,   -- the REQUESTED format; got_format_id is what Qobuz returned
  url           TEXT    NOT NULL,
  got_format_id INTEGER NOT NULL,
  bit_depth     INTEGER NOT NULL,
  sample_rate   INTEGER NOT NULL,   -- Hz
  etsp          INTEGER NOT NULL,   -- unix seconds, parsed from the URL itself
  PRIMARY KEY (track_id, format_id)
);

-- Daily counters: key = 'global' or 'i:<install id>'. The cron prunes old days.
CREATE TABLE quota (
  day TEXT NOT NULL,
  key TEXT NOT NULL,
  n   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, key)
);
