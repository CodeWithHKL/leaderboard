package com.example.leaderboard.web;

import com.example.leaderboard.model.RankedPlayer;
import com.example.leaderboard.redis.RedisLeaderboardService;
import com.example.leaderboard.sql.SqlLeaderboardRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class LeaderboardController {

    private static final int MAX_TOP = 1000;

    private final RedisLeaderboardService redis;
    private final SqlLeaderboardRepository sql;

    public LeaderboardController(RedisLeaderboardService redis, SqlLeaderboardRepository sql) {
        this.redis = redis;
        this.sql = sql;
    }

    @GetMapping("/redis/top")
    public List<RankedPlayer> redisTop(@RequestParam(defaultValue = "100") int n) {
        return redis.top(clamp(n));
    }

    @GetMapping("/redis/rank/{username}")
    public ResponseEntity<RankedPlayer> redisRank(@PathVariable String username) {
        return respond(redis.rank(username));
    }

    @GetMapping("/sql/top")
    public List<RankedPlayer> sqlTop(@RequestParam(defaultValue = "100") int n) {
        return sql.top(clamp(n));
    }

    @GetMapping("/sql/rank/{username}")
    public ResponseEntity<RankedPlayer> sqlRank(@PathVariable String username) {
        return respond(sql.rank(username));
    }

    /** Write-through: Postgres first (source of truth), then Redis. */
    @PutMapping("/players/{username}")
    public ResponseEntity<Void> setMmr(@PathVariable String username, @RequestBody Map<String, Integer> body) {
        Integer mmr = body.get("mmr");
        if (mmr == null) {
            return ResponseEntity.badRequest().build();
        }
        sql.upsert(username, mmr);
        redis.set(username, mmr);
        return ResponseEntity.noContent().build();
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(n, MAX_TOP));
    }

    private static ResponseEntity<RankedPlayer> respond(Optional<RankedPlayer> p) {
        return p.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
