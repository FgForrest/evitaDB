/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.exception;


import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when concurrent transactions modify the same data in conflicting ways,
 * detected during transaction commit.
 *
 * evitaDB uses optimistic concurrency control with conflict detection based on
 * {@link ConflictKey} instances. Each mutation declares which keys it affects. When
 * committing, evitaDB checks whether any committed transaction since this transaction's
 * start has modified the same keys. If so, this exception is thrown to prevent lost
 * updates and ensure serializability.
 *
 * **Typical Causes:**
 * - Two transactions concurrently modifying the same entity, attribute, or reference
 * - Long-running transaction whose data became stale while other transactions committed
 * - High contention on frequently modified entities (like counters or inventory levels)
 *
 * **Resolution:**
 * Retry the transaction from the beginning with fresh data. Read current entity state,
 * reapply your business logic, and commit again. Implementing exponential backoff for
 * retries is recommended under high contention.
 *
 * **Design Note:**
 * Conflict keys provide fine-grained conflict detection. For example, modifying attribute
 * "name" on entity 1 doesn't conflict with modifying attribute "price" on the same entity,
 * allowing higher concurrency than whole-entity locking would provide.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ConflictingCatalogMutationException extends TransactionException {
	@Serial private static final long serialVersionUID = 4792726509766583503L;
	/**
	 * Name of the catalog where the conflict occurred.
	 */
	@Getter private final String catalogName;

	/**
	 * Key identifying the conflicting mutation scope within the catalog.
	 */
	@Getter private final ConflictKey conflictKey;

	/**
	 * The catalog version at which the change with which current transaction conflicts has been accepted (committed).
	 */
	@Getter private final long catalogVersion;

	/**
	 * Creates a new exception describing a mutation conflict for the given catalog and
	 * conflict key.
	 *
	 * @param catalogName    name of the catalog where the conflict occurred
	 * @param conflictKey    key identifying the conflicting mutation scope (e.g., entity
	 *                       primary key, attribute name)
	 * @param catalogVersion the exact catalog version number where the conflicting change
	 *                       was committed
	 */
	public ConflictingCatalogMutationException(
		@Nonnull String catalogName,
		@Nonnull ConflictKey conflictKey,
		long catalogVersion
	) {
		super(
			"Conflicting mutations detected in catalog `" + catalogName + "` for conflict key: " + conflictKey + " " +
				"between your transaction and transactions that committed before you. " +
				"Conflicting change occurred exactly at catalog version: " + catalogVersion + ". "
		);
		this.catalogName = catalogName;
		this.conflictKey = conflictKey;
		this.catalogVersion = catalogVersion;
	}

    /**
     * Creates a new exception describing a mutation conflict with additional context.
     *
     * @param catalogName       name of the catalog where the conflict occurred
     * @param conflictKey       key identifying the conflicting mutation scope
     * @param catalogVersion    the exact catalog version where the conflict occurred
     * @param additionalMessage extra details about the conflict nature
     */
    protected ConflictingCatalogMutationException(
        @Nonnull String catalogName,
        @Nonnull ConflictKey conflictKey,
        long catalogVersion,
        @Nonnull String additionalMessage
    ) {
        super(
            "Conflicting mutations detected in catalog `" + catalogName + "` for conflict key: " + conflictKey + " " +
                "between your transaction and transactions that committed before you. " +
                "Conflicting change occurred exactly at catalog version: " + catalogVersion + ". " + additionalMessage
        );
        this.catalogName = catalogName;
        this.conflictKey = conflictKey;
        this.catalogVersion = catalogVersion;
    }

}
