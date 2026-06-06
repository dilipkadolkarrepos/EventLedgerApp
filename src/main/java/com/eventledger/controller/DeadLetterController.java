package com.eventledger.controller;

import com.eventledger.kafka.DeadLetterConsumer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Operational monitoring endpoints for the Kafka dead-letter queue.
 *
 * <p>These endpoints are intended for DevOps and on-call engineers to quickly
 * inspect recent processing failures without needing direct Kafka CLI access.</p>
 *
 * <p>Data is sourced from {@link DeadLetterConsumer}'s in-memory ring buffer and
 * resets on application restart — it is not a substitute for durable alerting.</p>
 */
@RestController
@RequestMapping("/dead-letter")
public class DeadLetterController {

    private final DeadLetterConsumer deadLetterConsumer;

    public DeadLetterController(DeadLetterConsumer deadLetterConsumer) {
        this.deadLetterConsumer = deadLetterConsumer;
    }

    /**
     * Returns the most recent dead-letter entries (up to 100), newest last.
     *
     * <p>Example: {@code GET /ledger/dead-letter/recent}</p>
     */
    @GetMapping("/recent")
    public ResponseEntity<List<Map<String, Object>>> getRecentFailures() {
        return ResponseEntity.ok(deadLetterConsumer.getRecentFailures());
    }

    /**
     * Returns the count of dead-letter entries currently held in memory.
     *
     * <p>Example: {@code GET /ledger/dead-letter/count}</p>
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Integer>> getFailureCount() {
        return ResponseEntity.ok(Map.of("count", deadLetterConsumer.getFailureCount()));
    }
}
