package com.jrobertgardzinski.memes.infrastructure;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;

/**
 * How long this service keeps re-trying ONE record of the account-deletion saga before it gives up
 * on it — a wall-clock <strong>deadline</strong>, not a number of attempts.
 *
 * <p><strong>What it replaces.</strong> Registering no error handler left Spring Kafka's
 * {@code DefaultErrorHandler} with its {@code FixedBackOff(0L, 9)} in charge: ten attempts with NO
 * pause between them (the whole burst inside a millisecond), then the record is "recovered" — logged
 * and skipped, its offset committed. A one-second hiccup of Postgres, a Hikari pool momentarily
 * exhausted, a restarting MinIO — anything that is over in a second rather than in a microsecond —
 * therefore made the purge command DISAPPEAR, silently, while the contract this service signed says
 * the saga must not lose a purge.
 *
 * <p><strong>Why it is not the eternal retry the sibling participant uses.</strong>
 * microservice-user-collections retries forever with backoff and never commits, with a javadoc
 * paragraph explaining that the saga must not lose a purge — true, and yet it cannot simply be
 * copied here, because the orchestrator gives up in finite time:
 *
 * <ul>
 *   <li>{@code OFFBOARDING_PURGE_TIMEOUT_SEC} = 120s — a saga is overdue when 120s have passed
 *       since its LAST DELIVERED attempt, not since it started: the sweeper measures the age of
 *       {@code updated_at}, and {@code retryDelivered} stamps that column when a re-commanded
 *       PURGE_USER_CONTENT demonstrably reached the broker;</li>
 *   <li>the sweeper wakes every 15s, so each re-command lands within one interval of its own
 *       deadline: the first at ≈120s, and because it resets the clock, the next at ≈240s and the
 *       last at ≈360s ({@code SweepOverdue.DEFAULT_MAX_RETRIES} = 3);</li>
 *   <li>at ≈480s the retries are spent: the saga compensates, microservice-security hands the
 *       account back and mails the leaver that the deletion FAILED.</li>
 * </ul>
 *
 * <p><strong>Why the cadence used to be different, and why that mattered here.</strong> The sweeper
 * measured overdue-ness from {@code created_at}, so past the threshold EVERY pass re-commanded — the
 * re-commands landed at ≈120s, ≈135s, ≈150s (15s apart, the sweeper's own interval) and the saga
 * capitulated at ≈165s, while the record the last one delivered still had ~90s of live budget below.
 * Two consequences, both fixed by measuring from the last delivered attempt: three re-commands were
 * spent inside a single participant's first retry window (they were re-asking a participant that was
 * demonstrably still working on the previous ask), and compensation arrived BEFORE the participant's
 * budget was even spent.
 *
 * <p>A participant that retried without end would purge whenever its store came back — an hour
 * later, a day later — deleting the content of an account the saga has already restored to its
 * owner. So the retrying is bounded, and the bound is this class.
 *
 * <p><strong>The arithmetic of the 90 seconds.</strong>
 *
 * <ul>
 *   <li><em>Floor ≈ 60s.</em> The failures this budget exists for BLOCK rather than fail fast: with
 *       Postgres unreachable, one attempt can spend a whole Hikari {@code connection-timeout} (the
 *       30s default, not overridden in this service) before it even throws. A budget under a minute
 *       would buy a single real attempt, which is the status quo with extra steps.</li>
 *   <li><em>Ceiling = 120s.</em> That is how long the orchestrator waits for THIS attempt before
 *       re-commanding — 120s from the delivery of the command in hand, now that overdue-ness is
 *       measured from the last delivered attempt. Retrying past it means competing with the command
 *       the sweeper has already sent for the same account: the purge is idempotent so nothing breaks,
 *       but it stops being THIS record's job. Ending first means the record in hand heals the hiccup,
 *       or hands the problem back to the sweeper. (The budget opens at the FIRST failure, so a
 *       record's whole life here is the budget plus that first attempt: ≈90s when failures are
 *       instant, ≈120s when the first one sits out a connection timeout — either way at most the
 *       orchestrator's patience for that same attempt, which is the number it should resemble.)</li>
 *   <li><em>90s is between them</em> and admits, concretely: ten attempts when the failure is fast
 *       (pauses 1+2+4+8+15+15+15+15+15 = 90s), and still four spread over two minutes when every
 *       attempt burns a full 30s connection timeout. Four attempts across two minutes is what rides
 *       out a database restart; ten immediate ones ride out nothing.</li>
 *   <li><em>The pause cap is 15s</em> — the sweeper's own interval, the coarsest clock in the saga —
 *       and it must stay far below {@code max.poll.interval.ms} (300s), because these pauses sleep
 *       ON the consumer thread: a pause longer than that interval would turn a database hiccup into
 *       a consumer-group rebalance.</li>
 * </ul>
 *
 * <p><strong>What "bounded" buys at the estate level.</strong> The orchestrator sends at most 1+3
 * commands per saga, and the commands of one leaver share a partition (they are keyed by the
 * address), so a re-command cannot overtake the record being retried. A permanent outage therefore
 * costs at most 4 × 90s ≈ 6 minutes of retrying per saga and then silence — bounded, logged and
 * counted (see {@link SagaParticipantConfig}), instead of a purge that lands whenever the store
 * happens to return. Each of those four rounds now gets its own 120s window rather than sharing one:
 * this record's retries end at ≈90s and the next command arrives at ≈120s, so the partition is idle
 * when it lands instead of the command queueing behind a record still in its budget.
 *
 * <p><strong>Where the overshoot went.</strong> This class used to name a residual window it could
 * not close: with re-commands 15s apart the last one landed at ≈150s and could still be purging at
 * ≈240s, up to ≈75s after the saga had already compensated and handed the account back — content
 * cleaned after the deletion was declared failed. Measuring overdue-ness from the last delivered
 * attempt closes it arithmetically: the final re-command lands at ≈360s, its budget is spent by
 * ≈450s, and the saga capitulates at ≈480s. The participant's 90s is strictly inside the
 * orchestrator's 120s, once per round, which is the invariant this budget was always trying to
 * approximate — 90 &lt; 120 is now a fact about every attempt rather than about the first one only.
 * (The outcome it protected against remains an announced one: PORTAL_PURGE_FAILED names the
 * participants that DID purge.)
 */
final class SagaRetryBudget implements BackOff {

    /** The whole retrying of one record, measured on the wall clock. See the class javadoc. */
    static final Duration BUDGET = Duration.ofSeconds(90);

    /** The first pause: short, because most store hiccups are over in a second. */
    static final Duration FIRST_PAUSE = Duration.ofSeconds(1);

    /** The pause cap — the sweeper's interval, and far below {@code max.poll.interval.ms}. */
    static final Duration LONGEST_PAUSE = Duration.ofSeconds(15);

    private final Duration budget;
    private final Duration firstPause;
    private final Duration longestPause;

    /** The elapsed-time source: monotonic in production, steerable in the tests. */
    private final java.util.function.LongSupplier nanoTime;

    static SagaRetryBudget forSagaRecords() {
        return new SagaRetryBudget(BUDGET, FIRST_PAUSE, LONGEST_PAUSE, System::nanoTime);
    }

    SagaRetryBudget(Duration budget, Duration firstPause, Duration longestPause,
                    java.util.function.LongSupplier nanoTime) {
        this.budget = budget;
        this.firstPause = firstPause;
        this.longestPause = longestPause;
        this.nanoTime = nanoTime;
    }

    /**
     * A fresh deadline per record. Spring Kafka's {@code FailedRecordTracker} calls this on a
     * record's FIRST failure (and once more, throwaway, while it works out whether retries are
     * configured at all), so the budget starts when the trouble starts — not when the service booted.
     *
     * <p>{@code System.nanoTime}, not the wall clock: the deadline measures elapsed time, and a wall
     * clock can step (NTP). Backwards it would hand out a budget that never expires; forwards it
     * would cut a purge short mid-outage.
     */
    @Override
    public BackOffExecution start() {
        long deadline = nanoTime.getAsLong() + budget.toNanos();
        return new BackOffExecution() {

            private long nextPauseMillis = firstPause.toMillis();

            @Override
            public long nextBackOff() {
                long remainingNanos = deadline - nanoTime.getAsLong();
                if (remainingNanos <= 0) {
                    return STOP;   // the budget is spent: the record is dropped, loudly and counted
                }
                long pause = Math.min(nextPauseMillis, longestPause.toMillis());
                nextPauseMillis = pause * 2;
                // never sleep PAST the deadline: a 15s pause with 3s of budget left would push the
                // record's last attempt beyond the budget the class name promises. Trimming it
                // spends the remainder on one final attempt instead.
                return Math.min(pause, remainingNanos / 1_000_000);
            }

            @Override
            public String toString() {
                return "SagaRetryBudget[" + budget.toSeconds() + "s, next pause "
                        + nextPauseMillis + "ms]";
            }
        };
    }

    @Override
    public String toString() {
        return "SagaRetryBudget[" + budget.toSeconds() + "s budget, pauses " + firstPause.toMillis()
                + "ms..." + longestPause.toMillis() + "ms]";
    }
}
