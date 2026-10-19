package com.example.leaderboard.web;

import com.example.leaderboard.redis.RedisLeaderboardService;
import com.example.leaderboard.sql.SqlLeaderboardRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final SqlLeaderboardRepository sql;
    private final RedisLeaderboardService redis;

    public AdminController(SqlLeaderboardRepository sql, RedisLeaderboardService redis) {
        this.sql = sql;
        this.redis = redis;
    }

    /** Wipes players, inserts {@code count} fake ones, and rebuilds Redis. */
    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "1000000") int count) {
        long t0 = System.nanoTime();
        sql.seed(count);
        long t1 = System.nanoTime();
        long loaded = redis.reloadFromDatabase();
        long t2 = System.nanoTime();
        return Map.of("players", count, "redisLoaded", loaded,
                "postgresMs", (t1 - t0) / 1_000_000, "redisMs", (t2 - t1) / 1_000_000);
    }

    @PostMapping("/reload-redis")
    public Map<String, Object> reload() {
        long t0 = System.nanoTime();
        long loaded = redis.reloadFromDatabase();
        return Map.of("redisLoaded", loaded, "redisMs", (System.nanoTime() - t0) / 1_000_000);
    }
}
