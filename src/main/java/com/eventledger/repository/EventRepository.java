package com.eventledger.repository;

import com.eventledger.model.TransactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<TransactionEvent, Long> {

    /**
     * Looks up a persisted event by its business key.
     * Used during ingestion to retrieve an already-stored event when an idempotent
     * re-submission arrives, so the service can return the original response without
     * creating a duplicate record.
     */
    Optional<TransactionEvent> findByEventId(String eventId);

    /**
     * Fast existence check on the business key before attempting an insert.
     * Preferred over {@link #findByEventId} for the idempotency guard because it
     * avoids loading the full entity when only a yes/no answer is needed.
     */
    boolean existsByEventId(String eventId);

    /**
     * Returns all events for an account sorted by the business-supplied event
     * timestamp rather than by insertion time ({@code received_at}).
     * Events may arrive out of order (delayed producers, retries), so sorting by
     * {@code eventTimestamp} gives a chronologically correct ledger view regardless
     * of when each record was physically written to the database.
     */
    List<TransactionEvent> findByAccountIdOrderByEventTimestampAsc(String accountId);

    /**
     * Computes the net balance for an account in a single database round-trip.
     * CREDITs add to the balance, DEBITs subtract. {@code COALESCE(..., 0)} ensures
     * a zero is returned for accounts that exist in another context but have no
     * recorded events, rather than a {@code null} that would require null-handling
     * at the call site.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN e.type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
            FROM TransactionEvent e
            WHERE e.accountId = :accountId
            """)
    BigDecimal computeBalanceByAccountId(@Param("accountId") String accountId);

    /**
     * Returns the ordered sequence of currencies seen on an account's events.
     * Ordered by {@code eventTimestamp ASC} so callers can detect currency changes
     * over time (e.g. to assert single-currency invariants or build a timeline).
     * The service layer is responsible for deduplication if only distinct values
     * are needed.
     */
    @Query("""
            SELECT e.currency
            FROM TransactionEvent e
            WHERE e.accountId = :accountId
            ORDER BY e.eventTimestamp ASC
            """)
    List<String> findCurrencyByAccountId(@Param("accountId") String accountId);
}
