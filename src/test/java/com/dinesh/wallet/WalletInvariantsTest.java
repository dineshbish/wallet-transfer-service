package com.dinesh.wallet;

import com.dinesh.wallet.error.ConflictException;
import com.dinesh.wallet.transfer.Transfer;
import com.dinesh.wallet.transfer.TransferService;
import com.dinesh.wallet.transfer.TransferStatus;
import com.dinesh.wallet.wallet.Wallet;
import com.dinesh.wallet.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the four graded invariants against a real Postgres, under real thread
 * contention.
 */
class WalletInvariantsTest extends AbstractIntegrationTest {

    @Autowired
    WalletService walletService;

    @Autowired
    TransferService transferService;

    @Test
    void concurrentGetOrCreateYieldsExactlyOneWallet() throws Exception {
        String userId = "race-" + UUID.randomUUID();
        int threads = 50;

        List<Wallet> results = runConcurrently(threads, () -> walletService.getOrCreate(userId));

        long distinctIds = results.stream().map(Wallet::id).distinct().count();
        assertThat(distinctIds).isEqualTo(1);
    }

    @Test
    void idempotentStormAppliesTransferExactlyOnce() throws Exception {
        Wallet a = walletService.getOrCreate("storm-a-" + UUID.randomUUID());
        Wallet b = walletService.getOrCreate("storm-b-" + UUID.randomUUID());
        walletService.deposit(a.id(), 100_000, "seed-" + UUID.randomUUID());

        String key = "idem-" + UUID.randomUUID();
        int threads = 30;

        List<Transfer> results = runConcurrently(threads,
                () -> transferService.transfer(a.id(), b.id(), 5_000, key));

        long distinctTransferIds = results.stream().map(Transfer::id).distinct().count();
        assertThat(distinctTransferIds).isEqualTo(1);
        assertThat(results).allMatch(t -> t.status() == TransferStatus.COMPLETED);
        assertThat(walletService.getById(a.id()).balancePaise()).isEqualTo(95_000);
        assertThat(walletService.getById(b.id()).balancePaise()).isEqualTo(5_000);
    }

    @Test
    void overdraftIsDeclinedCleanly() {
        Wallet a = walletService.getOrCreate("od-a-" + UUID.randomUUID());
        Wallet b = walletService.getOrCreate("od-b-" + UUID.randomUUID());
        walletService.deposit(a.id(), 1_000, "seed-" + UUID.randomUUID());

        Transfer t = transferService.transfer(a.id(), b.id(), 5_000, "od-" + UUID.randomUUID());

        assertThat(t.status()).isEqualTo(TransferStatus.DECLINED);
        assertThat(walletService.getById(a.id()).balancePaise()).isEqualTo(1_000);
        assertThat(walletService.getById(b.id()).balancePaise()).isZero();
    }

    @Test
    void sameKeyDifferentBodyIsConflict() {
        Wallet a = walletService.getOrCreate("cf-a-" + UUID.randomUUID());
        Wallet b = walletService.getOrCreate("cf-b-" + UUID.randomUUID());
        walletService.deposit(a.id(), 100_000, "seed-" + UUID.randomUUID());
        String key = "cf-" + UUID.randomUUID();

        transferService.transfer(a.id(), b.id(), 5_000, key);

        assertThatThrownBy(() -> transferService.transfer(a.id(), b.id(), 9_999, key))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void conservationHoldsUnderCrossingContention() throws Exception {
        int walletCount = 5;
        long seed = 100_000;
        List<Wallet> wallets = new java.util.ArrayList<>();
        for (int i = 0; i < walletCount; i++) {
            Wallet w = walletService.getOrCreate("cont-" + i + "-" + UUID.randomUUID());
            walletService.deposit(w.id(), seed, "seed-" + UUID.randomUUID());
            wallets.add(w);
        }
        long totalBefore = wallets.stream()
                .mapToLong(w -> walletService.getById(w.id()).balancePaise()).sum();

        int transfers = 200;
        var random = new java.util.Random(42);
        List<Callable<Transfer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < transfers; i++) {
            int from = random.nextInt(walletCount);
            int to = random.nextInt(walletCount);
            while (to == from) {
                to = random.nextInt(walletCount);
            }
            UUID fromId = wallets.get(from).id();
            UUID toId = wallets.get(to).id();
            long amount = (random.nextInt(3) + 1) * 20_000L;
            String key = "cont-" + i + "-" + UUID.randomUUID();
            tasks.add(() -> transferService.transfer(fromId, toId, amount, key));
        }

        runAll(tasks);

        long totalAfter = wallets.stream()
                .mapToLong(w -> walletService.getById(w.id()).balancePaise()).sum();
        assertThat(totalAfter).isEqualTo(totalBefore);
        assertThat(wallets).allMatch(w -> walletService.getById(w.id()).balancePaise() >= 0);
    }

    // --- helpers -----------------------------------------------------------

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        List<Callable<T>> copies = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            copies.add(task);
        }
        return runAll(copies);
    }

    /** Runs all tasks with a shared start latch so they collide as hard as possible. */
    private <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(64, tasks.size()));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Future<T>> futures = new java.util.ArrayList<>();
        try {
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return t.call();
                }));
            }
            start.countDown();
            List<T> out = new java.util.ArrayList<>();
            for (Future<T> f : futures) {
                try {
                    out.add(f.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }
            // None of the concurrent scenarios here should ever throw: declines
            // return DECLINED, replays return the original. A failure means a
            // deadlock or other server error — the exact regression we guard against.
            assertThat(failures.get())
                    .as("unexpected failures (e.g. deadlocks) under contention")
                    .isZero();
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
