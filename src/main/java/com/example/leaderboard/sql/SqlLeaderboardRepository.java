package com.example.leaderboard.sql;

import com.example.leaderboard.model.RankedPlayer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Postgres-only implementation: source of truth and the baseline to compare Redis against. */
@Repository
public class SqlLeaderboardRepository {

    private final JdbcTemplate jdbc;

    public SqlLeaderboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RankedPlayer> top(int n) {
        List<RankedPlayer> result = new ArrayList<>(n);
        jdbc.query("SELECT username, mmr FROM players ORDER BY mmr DESC, username DESC LIMIT ?",
                rs -> {
                    result.add(new RankedPlayer(result.size() + 1L, rs.getString(1), rs.getInt(2)));
                }, n);
        return result;
    }

    public Optional<RankedPlayer> rank(String username) {
        return jdbc.query("""
                SELECT p.username, p.mmr,
                       (SELECT count(*) FROM players o
                         WHERE (o.mmr, o.username) > (p.mmr, p.username)) + 1 AS rank
                FROM players p WHERE p.username = ?
                """,
                (rs, i) -> new RankedPlayer(rs.getLong("rank"), rs.getString("username"), rs.getInt("mmr")),
                username).stream().findFirst();
    }

    public void upsert(String username, int mmr) {
        jdbc.update("""
                INSERT INTO players (username, mmr) VALUES (?, ?)
                ON CONFLICT (username) DO UPDATE SET mmr = EXCLUDED.mmr, updated_at = now()
                """, username, mmr);
    }

    /** Replaces all players with {@code count} fake ones with bell-curve-ish MMR around 1500. */
    public void seed(int count) {
        jdbc.execute("TRUNCATE players RESTART IDENTITY");
        jdbc.update("""
                INSERT INTO players (username, mmr)
                SELECT 'player_' || g, (1500 + (random() + random() + random() - 1.5) * 800)::int
                FROM generate_series(1, ?) g
                """, count);
        jdbc.execute("ANALYZE players");
    }

    /** Keyset-paged read of every player, so a million rows never sit in memory at once. */
    public long forEachBatch(int batchSize, Consumer<List<RankedPlayer>> consumer) {
        long lastId = 0;
        long total = 0;
        while (true) {
            long after = lastId;
            List<Long> ids = new ArrayList<>(batchSize);
            List<RankedPlayer> batch = new ArrayList<>(batchSize);
            jdbc.query("SELECT id, username, mmr FROM players WHERE id > ? ORDER BY id LIMIT ?",
                    rs -> {
                        ids.add(rs.getLong(1));
                        batch.add(new RankedPlayer(0, rs.getString(2), rs.getInt(3)));
                    }, after, batchSize);
            if (batch.isEmpty()) {
                return total;
            }
            consumer.accept(batch);
            total += batch.size();
            lastId = ids.get(ids.size() - 1);
        }
    }
}
