package com.eventledger.repository;

import com.eventledger.model.TransactionEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface EventRepository extends JpaRepository<TransactionEvent, Long> {

    /**
     * Looks up a persisted event by its business key.
     * Used during ingestion to return an already-stored event on idempotent
     * re-submission, and to re-fetch the winner's record after a concurrent
     * duplicate triggers a UNIQUE constraint violation.
     */
    Optional<TransactionEvent> findByEventId(String eventId);

    /**
     * Returns a paginated slice of events for an account.
     * The caller must supply a {@link Pageable} that includes an explicit sort;
     * {@code Sort.by("eventTimestamp").ascending()} produces a chronologically
     * correct ledger regardless of physical insertion order.
     */
    Page<TransactionEvent> findByAccountId(String accountId, Pageable pageable);

    /**
     * Returns the account's earliest event (by business timestamp) in a single
     * database round-trip. Used by the balance endpoint to determine the currency
     * without loading all of the account's events.
     * Returns {@link Optional#empty()} when the account has no recorded events.
     */
    Optional<TransactionEvent> findFirstByAccountIdOrderByEventTimestampAsc(String accountId);

    /**
     * Computes the net balance for an account in a single database round-trip.
     * CREDITs add to the balance, DEBITs subtract. {@code COALESCE(..., 0)} ensures
     * a zero is returned rather than {@code null} when an account has no events.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN e.type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
            FROM TransactionEvent e
            WHERE e.accountId = :accountId
            """)
    BigDecimal computeBalanceByAccountId(@Param("accountId") String accountId);
}
