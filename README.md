# Redis Leaderboard

A learning project: a gaming leaderboard (player ranking by MMR) served two ways, from **Redis sorted sets**
and from **PostgreSQL only**, plus a load tester to compare them under concurrent requests.

- Java 25, Spring Boot 4.1, Maven
- PostgreSQL (source of truth), Redis Stack (live ranking)
- 1,000,000 synthetic players

## How it works

| Piece | Role |
|---|---|
| PostgreSQL `players` table | Source of truth (`username`, `mmr`), index on `(mmr DESC, username DESC)` |
| Redis sorted set `lb:global` | Live ranking: member = username, score = MMR |
| `SqlLeaderboardRepository` | Top-N and rank straight from Postgres (the baseline) |
| `RedisLeaderboardService` | Top-N (`ZREVRANGE ... WITHSCORES`) and rank (one Lua call: `ZREVRANK` + `ZSCORE`) |
| Writes (`PUT /api/players/{user}`) | Write-through: Postgres first, then Redis |
| `POST /admin/seed`, `/admin/reload-redis` | Generate fake players / rebuild Redis from Postgres |

Ties are broken by username (descending) in both backends (`COLLATE "C"` in Postgres), so both return identical rankings.

| Endpoint | Description |
|---|---|
| `GET /api/redis/top?n=100`, `GET /api/redis/rank/{user}` | Served from Redis |
| `GET /api/sql/top?n=100`, `GET /api/sql/rank/{user}` | Served from Postgres only |
| `PUT /api/players/{user}` body `{"mmr":1800}` | Set a player's MMR in both stores |
| `POST /admin/seed?count=1000000` | Wipe and regenerate players, rebuild Redis |

## Run it

```powershell
docker compose up -d                       # Redis Stack on :6379, RedisInsight on :8001
# create the database once (needs your Postgres password):
& "C:\Program Files\PostgreSQL\18\bin\createdb.exe" -h localhost -U postgres leaderboard

$env:DB_PASSWORD = "your-password"         # never committed; DB_USER (default postgres) and DB_NAME also supported
.\mvnw spring-boot:run

curl -X POST "localhost:8080/admin/seed?count=1000000"
curl "localhost:8080/api/redis/top?n=5"
```

## Load test

`loadtest/LoadTest.java` is a single-file Java load generator (JDK `HttpClient` + virtual threads, no dependencies).
Each virtual user sends one request at a time, back to back (closed loop).

```
java -Xmx256m loadtest/LoadTest.java <redis|sql> <top|rank> <users> <seconds>
```

Rank tests look up a random player out of 1,000,000. A 3 s warm-up is excluded from the measurements.
Requests time out after 10 s and count as errors.

## Results

Each cell is the **median of 3 runs** (15 s per run, 3 s warm-up). Ranges are in the raw data below.
Latency is in milliseconds. `req/s` is throughput; p50 / p99 are the median and 99th-percentile latency.

### Rank lookup (player's rank among 1M)

| Users | Redis req/s | Redis p50 | Redis p99 | SQL req/s | SQL p50 | SQL p99 |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 7,838 | 11.8 ms | 25.9 ms | 106 | 930 ms | 1,138 ms |
| 250 | 5,901 | 40.6 ms | 74.0 ms | 109 | 2,263 ms | 2,519 ms |
| 500 | 5,632 | 85.9 ms | 145.3 ms | 106 | 4,643 ms | 4,978 ms |
| 1000 | 5,557 | 171.5 ms | 295.7 ms | unstable* | unstable* | unstable* |

\* At 1000 users SQL rank latency sits at the 10 s client timeout. The three runs gave 7, 1,471 and 664 timeouts
(throughput 106, 0 and 56 req/s), so no single number is meaningful. In every run it was effectively unusable.

### Top 100

| Users | Redis req/s | Redis p50 | Redis p99 | SQL req/s | SQL p50 | SQL p99 |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 2,957 | 32.6 ms | 56.8 ms | 8,497 | 10.4 ms | 40.3 ms |
| 250 | 2,862 | 84.3 ms | 134.6 ms | 8,020 | 25.8 ms | 125.8 ms |
| 500 | 2,802 | 176.7 ms | 276.3 ms | 7,608 | 51.6 ms | 300.6 ms |
| 1000 | 2,820 | 352.1 ms | 468.1 ms | 7,002 | 66.4 ms | 954.9 ms |

### What the results show

- **Rank lookup is where Redis wins, by roughly 50-75x in throughput** at 100-500 users. Postgres must count the
  players ranked above the target (about 700k rows for a mid-ranked player), so it handles only ~106 req/s and latency
  grows with the queue. Redis answers from the sorted set in O(log N) and stays at 5,500+ req/s.
- **Top 100 is faster in Postgres in this setup.** The index already stores rows in rank order, so Postgres reads
  100 index entries and stops. Redis served ~2,800 req/s against Postgres's ~7,000-8,500. Caching the top 100 in the
  app for a second would likely change this, but it was not tested.
- Redis's advantage is for **"what is my rank?"**-style queries, not for reading a short, index-ordered top list.

## Caveats (read before quoting these numbers)

1. **Single machine.** The load generator, Spring Boot app, Postgres, and Docker all ran on one 12-core, 7.3 GB Windows
   laptop. Absolute numbers will not transfer; the comparison between backends is the useful part.
2. **Redis runs in Docker Desktop (WSL2), Postgres runs natively.** A Redis round trip from Windows measured 0.65 ms,
   versus 0.11 ms inside the container, so Redis pays about 0.5 ms extra per call. This favors Postgres. It is the
   likely (but not proven) reason Redis was slower on top-100.
3. **Postgres rank throughput may be partly configuration-limited.** The app uses the default Hikari pool of 10
   connections. A larger pool was not tested, so ~106 req/s should not be read as Postgres's absolute ceiling.
4. **Only 3 short runs per configuration.** Run-to-run variation was a few percent for Redis (for example Redis
   rank at 100 users: 7,005-8,069 req/s). Small differences are noise.
5. **Closed-loop test.** At high user counts the latency is mostly queueing time, not the cost of one request.
6. **Synthetic data.** 1M generated players with MMR roughly bell-shaped around 1500, not real player data.
7. **No test above 1000 users** (the machine has little free RAM) and **no write-heavy mix**.

## Raw data (3 runs)

`rps` / `p50 ms` / `p99 ms` per run.

| Test | Users | Run 1 | Run 2 | Run 3 |
|---|---:|---|---|---|
| redis top | 100 | 2996 / 32.0 ms / 56.8 ms | 2957 / 32.6 ms / 51.1 ms | 2453 / 38.0 ms / 74.1 ms |
| redis top | 250 | 2923 / 82.5 ms / 136.4 ms | 2847 / 85.2 ms / 125.3 ms | 2862 / 84.3 ms / 134.6 ms |
| redis top | 500 | 2654 / 178.7 ms / 315.9 ms | 2876 / 171.1 ms / 276.3 ms | 2802 / 176.7 ms / 253.2 ms |
| redis top | 1000 | 2821 / 348.3 ms / 457.6 ms | 2556 / 383.8 ms / 528.2 ms | 2820 / 352.1 ms / 468.1 ms |
| redis rank | 100 | 8069 / 11.6 ms / 24.8 ms | 7838 / 11.8 ms / 25.9 ms | 7005 / 13.1 ms / 30.3 ms |
| redis rank | 250 | 6430 / 37.9 ms / 71.5 ms | 5901 / 40.6 ms / 74.0 ms | 5511 / 43.0 ms / 84.0 ms |
| redis rank | 500 | 6025 / 79.2 ms / 143.2 ms | 5632 / 85.9 ms / 145.3 ms | 5480 / 86.9 ms / 152.9 ms |
| redis rank | 1000 | 5746 / 166.6 ms / 289.1 ms | 5377 / 182.7 ms / 297.7 ms | 5557 / 171.5 ms / 295.7 ms |
| sql top | 100 | 8497 / 10.2 ms / 40.3 ms | 8510 / 10.4 ms / 38.3 ms | 8302 / 10.6 ms / 40.4 ms |
| sql top | 250 | 8342 / 25.2 ms / 115.6 ms | 8020 / 25.8 ms / 125.8 ms | 7653 / 26.9 ms / 129.6 ms |
| sql top | 500 | 7734 / 54.0 ms / 272.2 ms | 7608 / 49.8 ms / 300.6 ms | 7172 / 51.6 ms / 357.0 ms |
| sql top | 1000 | 7163 / 66.4 ms / 883.6 ms | 6603 / 70.2 ms / 982.1 ms | 7002 / 65.8 ms / 954.9 ms |
| sql rank | 100 | 114 / 873 ms / 1074 ms | 106 / 930 ms / 1138 ms | 104 / 959 ms / 1162 ms |
| sql rank | 250 | 109 / 2263 ms / 2518 ms | 111 / 2248 ms / 2503 ms | 104 / 2403 ms / 2674 ms |
| sql rank | 500 | 106 / 4632 ms / 4978 ms | 104 / 5059 ms / 5400 ms (2 errors) | 106 / 4643 ms / 4955 ms |
| sql rank | 1000 | 106 / 9107 ms / 9736 ms (7 errors) | 0 / n/a (1471 errors, 5 successes) | 56 / 9663 ms / 9997 ms (664 errors) |
