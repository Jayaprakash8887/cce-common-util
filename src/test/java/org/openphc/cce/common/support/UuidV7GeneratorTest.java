package org.openphc.cce.common.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    private final UuidV7Generator generator = new UuidV7Generator();

    /** Unsigned 128-bit comparison, matching how PostgreSQL orders the {@code uuid} type. */
    private static int compareUnsigned(UUID a, UUID b) {
        int hi = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return hi != 0 ? hi : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private static long embeddedTimestampMs(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }

    @Test
    void setsVersion7AndRfcVariant() {
        UUID uuid = generator.generateUuid(null);
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2); // RFC 4122 / 9562 variant (0b10)
    }

    @Test
    void embedsCurrentTimestamp() {
        long before = System.currentTimeMillis();
        UUID uuid = generator.generateUuid(null);
        long after = System.currentTimeMillis();

        assertThat(embeddedTimestampMs(uuid)).isBetween(before, after);
    }

    @Test
    void isStrictlyMonotonicWithinABurst() {
        int count = 100_000; // far exceeds 4096/ms, exercising the counter-overflow carry
        UUID previous = generator.generateUuid(null);
        for (int i = 1; i < count; i++) {
            UUID current = generator.generateUuid(null);
            assertThat(compareUnsigned(previous, current))
                    .as("uuid %d must sort strictly before uuid %d", i - 1, i)
                    .isNegative();
            previous = current;
        }
    }

    @Test
    void producesUniqueValues() {
        int count = 100_000;
        Set<UUID> seen = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            assertThat(seen.add(generator.generateUuid(null))).isTrue();
        }
    }

    @Test
    void isThreadSafe() throws InterruptedException {
        int threads = 8;
        int perThread = 20_000;
        Set<UUID> all = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        all.add(generator.generateUuid(null));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(all).hasSize(threads * perThread); // no collisions across threads
    }

    @Test
    void counterOverflowBorrowsIntoTheTimestampRatherThanWrapping() throws Exception {
        // 4096 ids in a single millisecond is not reproducible from a test loop, so the counter is
        // driven to its ceiling directly. The invariant matters: on overflow the id must keep rising,
        // never repeat the previous sequence value within the same millisecond.
        UuidV7Generator generator = new UuidV7Generator();
        java.lang.reflect.Field lastMillis = UuidV7Generator.class.getDeclaredField("lastMillis");
        java.lang.reflect.Field counter = UuidV7Generator.class.getDeclaredField("counter");
        lastMillis.setAccessible(true);
        counter.setAccessible(true);
        // a timestamp in the future keeps System.currentTimeMillis() from resetting the counter
        long future = System.currentTimeMillis() + 60_000L;
        lastMillis.setLong(generator, future);
        counter.setLong(generator, 0x0FFFL);

        UUID overflowed = generator.generateUuid(null);

        assertThat(overflowed.getMostSignificantBits() >>> 16)
                .as("the timestamp is borrowed into, so the id still increases")
                .isEqualTo(future + 1);
        assertThat(overflowed.getMostSignificantBits() & 0x0FFFL)
                .as("the sequence restarts in the borrowed millisecond")
                .isZero();
        assertThat(counter.getLong(generator)).isZero();

        assertThat(generator.generateUuid(null).getMostSignificantBits())
                .as("generation continues to rise past the overflow")
                .isGreaterThan(overflowed.getMostSignificantBits());
    }

    @Test
    void aBackwardsClockDoesNotProduceADecreasingId() {
        // NTP correction or a VM pause can move the wall clock back. The generator holds its last
        // timestamp and advances the sequence instead, so ids stay usable as a sort key.
        UuidV7Generator generator = new UuidV7Generator();
        UUID first = generator.generateUuid(null);

        for (int i = 0; i < 200; i++) {
            UUID next = generator.generateUuid(null);
            assertThat(next.getMostSignificantBits())
                    .isGreaterThan(first.getMostSignificantBits());
            first = next;
        }
    }
}
