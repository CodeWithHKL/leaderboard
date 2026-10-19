import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Closed-loop load test: N virtual users each send one request at a time, back to back.
 *
 * Usage: java LoadTest.java <redis|sql> <top|rank> <users> <seconds> [players=1000000]
 */
public class LoadTest {

    static final int WARMUP_SECONDS = 3;
    static final int BUCKET_US = 100;          // histogram resolution: 0.1 ms
    static final int BUCKETS = 100_000;        // up to 10 s, anything slower lands in the last bucket

    public static void main(String[] args) throws Exception {
        String backend = args[0];
        String scenario = args[1];
        int users = Integer.parseInt(args[2]);
        int seconds = Integer.parseInt(args[3]);
        int players = args.length > 4 ? Integer.parseInt(args[4]) : 1_000_000;

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        AtomicLongArray histogram = new AtomicLongArray(BUCKETS);
        AtomicLong ok = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        long start = System.nanoTime();
        long measureFrom = start + WARMUP_SECONDS * 1_000_000_000L;
        long stopAt = measureFrom + seconds * 1_000_000_000L;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int u = 0; u < users; u++) {
                pool.submit(() -> {
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    while (System.nanoTime() < stopAt) {
                        String path = scenario.equals("top")
                                ? "/api/" + backend + "/top?n=100"
                                : "/api/" + backend + "/rank/player_" + (1 + rnd.nextInt(players));
                        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:8080" + path))
                                .timeout(Duration.ofSeconds(10)).GET().build();
                        long t0 = System.nanoTime();
                        boolean success;
                        try {
                            success = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                        } catch (Exception e) {
                            success = false;
                        }
                        long t1 = System.nanoTime();
                        if (t0 >= measureFrom) {
                            if (success) {
                                ok.incrementAndGet();
                                int b = (int) Math.min((t1 - t0) / 1000 / BUCKET_US, BUCKETS - 1);
                                histogram.incrementAndGet(b);
                            } else {
                                errors.incrementAndGet();
                            }
                        }
                    }
                });
            }
        }

        long total = ok.get();
        System.out.printf("%-5s %-4s users=%-5d  req=%-8d err=%-6d rps=%-8.0f p50=%-7.1f p95=%-7.1f p99=%-7.1f ms%n",
                backend, scenario, users, total, errors.get(), total / (double) seconds,
                percentile(histogram, total, 0.50), percentile(histogram, total, 0.95),
                percentile(histogram, total, 0.99));
    }

    static double percentile(AtomicLongArray h, long total, double p) {
        if (total == 0) {
            return Double.NaN;
        }
        long target = (long) Math.ceil(total * p);
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += h.get(i);
            if (seen >= target) {
                return (i + 1) * BUCKET_US / 1000.0;
            }
        }
        return BUCKETS * BUCKET_US / 1000.0;
    }
}
