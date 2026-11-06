package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code @Scheduled} job in this service has a thread to run on.
 *
 * <p>Spring's default scheduler is a pool of ONE, and this service hangs three jobs off the single
 * {@code @EnableScheduling} in {@link MemeOutboxConfig}. Two of them are alarms about work that was
 * owed and not done — the blob sweep and the erasure backlog watch — and the third, the shared
 * outbox republisher, is the one that can occupy a thread for seconds at a time when the broker is
 * away (up to {@code max.block.ms} per overdue row). Sharing one thread means the two alarms go
 * quiet during exactly the incident they report on.
 *
 * <p>The count is taken from the container rather than written down here, so a fourth job cannot
 * be added into a pool that has no room for it.
 */
@Epic("Infrastructure")
@Feature("Scheduled jobs")
@SpringBootTest(classes = MemesApplication.class, properties = {
        "memes.kafka-enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1"})
class ScheduledJobsDoNotQueueTest {

    @Autowired
    ScheduledTaskHolder scheduledJobs;

    @Autowired
    ThreadPoolTaskScheduler scheduler;

    @Test
    @DisplayName("the scheduler has a thread for each scheduled job, not one for all of them")
    void no_scheduled_job_waits_for_another() {
        int jobs = scheduledJobs.getScheduledTasks().size();
        assertTrue(jobs >= 3, "the three jobs this service schedules are all registered — found " + jobs);

        assertTrue(scheduler.getPoolSize() >= jobs,
                "a scheduler pool of " + scheduler.getPoolSize() + " for " + jobs + " scheduled jobs:"
                        + " a republisher pass against an absent broker takes seconds, and the"
                        + " erasure backlog gauge does not get fed while it does");
    }
}
