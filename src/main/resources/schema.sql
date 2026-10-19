-- "C" collation gives byte-order username comparison, matching Redis's tie-break
-- for equal scores, so the SQL and Redis rankings are identical.
CREATE TABLE IF NOT EXISTS players (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(32) COLLATE "C" NOT NULL UNIQUE,
    mmr        INT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_players_rank ON players (mmr DESC, username DESC);
