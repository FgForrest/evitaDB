/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api;

import io.evitadb.api.SessionTraits.SessionFlags;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Represents a transactional context in evitaDB that isolates writes and controls commit/rollback behavior.
 *
 * **Purpose and Architecture**
 *
 * `TransactionContract` provides snapshot isolation for evitaDB operations. Transactions are implicitly opened when
 * performing write operations in a read-write session, or explicitly controlled via try-with-resources patterns.
 * Each transaction maintains its own isolated view of the catalog state and accumulates mutations that are either
 * committed or rolled back atomically.
 *
 * **Transaction Lifecycle**
 *
 * 1. **Creation**: Implicit on first write operation in a read-write session
 * 2. **Accumulation**: Mutations collected in isolation from other transactions
 * 3. **Close**: Either commits (default) or rolls back (if {@link #setRollbackOnly()} was called)
 *
 * **Concurrency Model**
 *
 * evitaDB uses optimistic concurrency control:
 * - Transactions operate on isolated snapshots
 * - No locks during transaction execution
 * - Conflict detection at commit time
 * - First transaction wins; later transactions with conflicts are rejected
 *
 * **Usage Patterns**
 *
 * Implicit transaction (single-operation):
 * ```
 * session.upsertEntity(entity);  // implicitly commits when session closes
 * ```
 *
 * Explicit transaction control (not directly exposed in public API):
 * ```
 * // Transactions are managed internally by the session
 * // Public API: changes commit on session close based on CommitBehavior
 * ```
 *
 * Rollback example:
 * ```
 * // If exception occurs, transaction is marked rollback-only automatically
 * // Or explicitly via internal transaction.setRollbackOnly()
 * ```
 *
 * **Isolation Guarantees**
 *
 * - Read Committed: Each query sees all changes committed before the query started
 * - Snapshot Isolation: Within a transaction, all reads see a consistent snapshot
 * - Serializable: Transactions that modify overlapping data are serialized via conflict detection
 *
 * **Thread-Safety**
 *
 * This interface is NOT thread-safe. Each transaction is bound to a single thread (the session's thread).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface TransactionContract extends AutoCloseable {

	/**
	 * Returns the unique identifier of this transaction.
	 *
	 * The transaction ID is a UUID generated when the transaction is created. It serves purely for identification and
	 * logging purposes; it is not used in the MVCC (Multi-Version Concurrency Control) mechanism. evitaDB's versioning
	 * is based on catalog version numbers, not transaction IDs.
	 *
	 * @return unique UUID identifying this transaction instance
	 */
	@Nonnull
	UUID getTransactionId();

	/**
	 * Marks this transaction for rollback, ensuring all changes are discarded on {@link #close()}.
	 *
	 * Once this method is called, the transaction cannot be committed. When {@link #close()} is invoked, all mutations
	 * accumulated in this transaction are discarded and the catalog state remains unchanged.
	 *
	 * **When to Use**
	 *
	 * - Exception handling: call this in a catch block to abandon changes after an error
	 * - Validation failures: mark rollback when business logic rejects a batch of changes
	 * - Dry-run mode: automatically set for sessions with {@link SessionFlags#DRY_RUN}
	 *
	 * **Note**: This method is idempotent; calling it multiple times has the same effect as calling it once.
	 */
	void setRollbackOnly();

	/**
	 * Returns `true` if this transaction has been marked for rollback.
	 *
	 * A transaction can be marked rollback-only by:
	 * - Explicit call to {@link #setRollbackOnly()}
	 * - Exception during mutation processing
	 * - Dry-run session mode ({@link SessionFlags#DRY_RUN})
	 *
	 * @return `true` if {@link #setRollbackOnly()} was called, `false` otherwise
	 */
	boolean isRollbackOnly();

	/**
	 * Closes the transaction, committing or rolling back changes based on the rollback-only flag.
	 *
	 * **Behavior**
	 *
	 * - If {@link #isRollbackOnly()} returns `false`: changes are committed according to the session's
	 * {@link CommitBehavior}
	 * - If {@link #isRollbackOnly()} returns `true`: all changes are discarded
	 *
	 * **Idempotency**
	 *
	 * This method is idempotent; multiple calls have no effect after the first invocation.
	 *
	 * **Exception Handling**
	 *
	 * This method may throw exceptions during commit if:
	 * - Conflicts with other transactions are detected
	 * - Validation errors occur during commit processing
	 * - I/O errors prevent writing to the Write-Ahead Log (WAL)
	 */
	@Override
	void close();

	/**
	 * Returns `true` if this transaction has been closed (either committed or rolled back).
	 *
	 * After {@link #close()} is called, this method returns `true` and the transaction can no longer accept mutations.
	 *
	 * @return `true` if {@link #close()} was called, `false` otherwise
	 */
	boolean isClosed();

	/**
	 * Defines when a transaction commit is considered complete in evitaDB.
	 *
	 * **Architecture Context**
	 *
	 * evitaDB's commit pipeline consists of multiple stages:
	 * 1. **Conflict resolution**: Optimistic lock check against concurrent transactions
	 * 2. **WAL persistence**: Write-Ahead Log fsync to disk (durability guarantee)
	 * 3. **Index propagation**: Apply changes to in-memory indexes (visibility guarantee)
	 *
	 * `CommitBehavior` controls which stage must complete before the commit call returns to the client. Later stages
	 * continue asynchronously after the client call returns (except for {@link #WAIT_FOR_CHANGES_VISIBLE}).
	 *
	 * **Trade-offs**
	 *
	 * - **Performance**: Earlier stages return faster but with fewer guarantees
	 * - **Durability**: WAL persistence ensures crash recovery
	 * - **Visibility**: Index propagation ensures immediate query consistency
	 *
	 * **Choosing a Behavior**
	 *
	 * - Use {@link #WAIT_FOR_CONFLICT_RESOLUTION} for maximum throughput when:
	 * - Durability is handled externally (e.g., replicated primary data store)
	 * - Acceptable to lose recent changes on crash
	 * - Eventual consistency is acceptable
	 *
	 * - Use {@link #WAIT_FOR_WAL_PERSISTENCE} for durability with fast commits:
	 * - Changes must survive crashes
	 * - Immediate visibility not required
	 * - Background indexing is acceptable
	 *
	 * - Use {@link #WAIT_FOR_CHANGES_VISIBLE} (default) for strong consistency:
	 * - Changes must be immediately queryable after commit
	 * - Durability is required
	 * - Standard ACID expectations
	 */
	enum CommitBehavior {

		/**
		 * Commit completes after conflict resolution, before WAL persistence.
		 *
		 * **Guarantees**
		 *
		 * - Changes passed conflict check (no concurrent transaction modified the same data)
		 * - Transaction is accepted and will be applied
		 *
		 * **No Guarantees**
		 *
		 * - Changes are NOT durable; server crash may lose this transaction
		 * - Changes are NOT visible to queries yet; indexes are still being updated asynchronously
		 *
		 * **Performance**
		 *
		 * Fastest commit behavior. Suitable for high-throughput scenarios where durability is handled externally
		 * (e.g., evitaDB acts as a secondary index rebuilt from a durable primary store).
		 *
		 * **Failure Scenarios**
		 *
		 * - Server crash before WAL fsync: changes lost
		 * - Client disconnect after commit returns: changes may or may not be visible (asynchronous indexing)
		 */
		WAIT_FOR_CONFLICT_RESOLUTION,

		/**
		 * Commit completes after Write-Ahead Log (WAL) is persisted to disk (fsynced).
		 *
		 * **Guarantees**
		 *
		 * - Changes are durable; survive server crash and restart
		 * - Changes passed conflict check
		 *
		 * **No Guarantees**
		 *
		 * - Changes are NOT visible to queries yet; indexes are still being updated asynchronously
		 *
		 * **Performance**
		 *
		 * Slower than {@link #WAIT_FOR_CONFLICT_RESOLUTION} due to fsync overhead, but faster than
		 * {@link #WAIT_FOR_CHANGES_VISIBLE}. evitaDB may batch fsync operations from multiple transactions to amortize
		 * cost, so latency may vary depending on concurrent commit activity.
		 *
		 * **Use Cases**
		 *
		 * - Systems requiring durability without strict immediate consistency
		 * - Batch import where visibility can lag behind writes
		 * - Event sourcing scenarios where replay from WAL is acceptable
		 *
		 * **Failure Scenarios**
		 *
		 * - Client disconnect after commit returns: changes are durable and will eventually become visible
		 */
		WAIT_FOR_WAL_PERSISTENCE,

		/**
		 * Commit completes after changes are visible in indexes and durable on disk.
		 *
		 * **Guarantees**
		 *
		 * - Changes are durable (WAL fsynced)
		 * - Changes are immediately visible to all queries (indexes updated)
		 * - Next query in any session will see these changes
		 *
		 * **Performance**
		 *
		 * Slowest commit behavior. Blocks until index updates complete, which may involve multiple data structures
		 * (entity storage, attribute indexes, reference indexes, hierarchy indexes).
		 *
		 * **Use Cases**
		 *
		 * - Interactive applications expecting read-your-writes consistency
		 * - Test scenarios requiring deterministic state after each operation
		 * - Admin tools where immediate feedback is critical
		 *
		 * **Default Behavior**
		 *
		 * This is the default commit behavior, providing the strongest consistency guarantees and matching typical
		 * ACID database expectations.
		 */
		WAIT_FOR_CHANGES_VISIBLE;

		/**
		 * Returns the default commit behavior for evitaDB transactions.
		 *
		 * The default is {@link #WAIT_FOR_CHANGES_VISIBLE}, which provides strong consistency (changes are immediately
		 * visible after commit) and durability (changes survive crashes).
		 *
		 * @return {@link #WAIT_FOR_CHANGES_VISIBLE}
		 */
		@Nonnull
		public static CommitBehavior defaultBehaviour() {
			return WAIT_FOR_CHANGES_VISIBLE;
		}

	}

}
