package com.example.leaderboard.redis;

import com.example.leaderboard.model.RankedPlayer;
import com.example.leaderboard.sql.SqlLeaderboardRepository;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Redis sorted-set leaderboard: member = username, score = MMR. */
@Service
public class RedisLeaderboardService {

    static final String KEY = "lb:global";

    private final StringRedisTemplate redis;
    private final SqlLeaderboardRepository sql;

    public RedisLeaderboardService(StringRedisTemplate redis, SqlLeaderboardRepository sql) {
        this.redis = redis;
        this.sql = sql;
    }

    public List<RankedPlayer> top(int n) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(KEY, 0, n - 1);
        List<RankedPlayer> result = new ArrayList<>(n);
        if (tuples != null) {
            for (TypedTuple<String> t : tuples) {
                result.add(new RankedPlayer(result.size() + 1L, t.getValue(), t.getScore().intValue()));
            }
        }
        return result;
    }

    /** Rank and score in a single round trip; returns an empty list if the player is unknown. */
    private static final RedisScript<List> RANK_SCRIPT = new DefaultRedisScript<>("""
            local rank = redis.call('ZREVRANK', KEYS[1], ARGV[1])
            if not rank then return {} end
            return {rank, redis.call('ZSCORE', KEYS[1], ARGV[1])}
            """, List.class);

    public Optional<RankedPlayer> rank(String username) {
        List<?> reply = redis.execute(RANK_SCRIPT, List.of(KEY), username);
        if (reply == null || reply.size() < 2) {
            return Optional.empty();
        }
        long rank = Long.parseLong(String.valueOf(reply.get(0)));
        double score = Double.parseDouble(String.valueOf(reply.get(1)));
        return Optional.of(new RankedPlayer(rank + 1, username, (int) score));
    }

    public void set(String username, int mmr) {
        redis.opsForZSet().add(KEY, username, mmr);
    }

    /** Rebuilds the sorted set from Postgres. Returns the number of players loaded. */
    public long reloadFromDatabase() {
        redis.delete(KEY);
        return sql.forEachBatch(10_000, batch -> {
            Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
            for (RankedPlayer p : batch) {
                tuples.add(new DefaultTypedTuple<>(p.username(), (double) p.mmr()));
            }
            redis.opsForZSet().add(KEY, tuples);
        });
    }
}
