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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionLayer;
import io.evitadb.exception.NotMonitored;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * **Monitoring:**
 * The type is marked {@link NotMonitored}: a conflict is a benign, expected outcome of concurrent
 * writers, not an engine fault. Counting it would move `io_evitadb_errors_total` and, through the
 * same counter, raise the `EVITA_DB_INTERNAL_ERRORS` health signal. Conflicts are tracked instead
 * through `TransactionConflictEvent` (`io_evitadb_transaction_transaction_conflict_total`), which
 * also breaks them down by conflict policy, resolution layer and conflict scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotMonitored
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
	 * The effective conflict resolution that was in force for the conflicting scope, or {@code null} when the
	 * exception was raised without diagnostic resolution context.
	 */
	@Getter @Nullable private final ConflictResolution resolvedConflictResolution;

	/**
	 * The schema layer the {@link #resolvedConflictResolution} was taken from (entity / catalog / engine), or
	 * {@code null} when the exception was raised without diagnostic resolution context.
	 */
	@Getter @Nullable private final ConflictResolutionLayer resolutionLayer;

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
		super(composeMessage(catalogName, conflictKey, catalogVersion, null));
		this.catalogName = catalogName;
		this.conflictKey = conflictKey;
		this.catalogVersion = catalogVersion;
		this.resolvedConflictResolution = null;
		this.resolutionLayer = null;
	}

	/**
	 * Creates a new exception describing a mutation conflict enriched with the resolved conflict-resolution
	 * diagnostics: the policy that was in force for the conflicting scope and the schema layer it was
	 * resolved from. The diagnostic detail is folded into the exception message so it survives serialization
	 * across the client boundary in addition to being available through the getters.
	 *
	 * @param catalogName               name of the catalog where the conflict occurred
	 * @param conflictKey               key identifying the conflicting mutation scope
	 * @param catalogVersion            the exact catalog version where the conflicting change was committed
	 * @param resolvedConflictResolution the effective resolution that was in force for the conflicting scope
	 * @param resolutionLayer           the schema layer the resolution was taken from
	 */
	public ConflictingCatalogMutationException(
		@Nonnull String catalogName,
		@Nonnull ConflictKey conflictKey,
		long catalogVersion,
		@Nonnull ConflictResolution resolvedConflictResolution,
		@Nonnull ConflictResolutionLayer resolutionLayer
	) {
		super(composeMessage(catalogName, conflictKey, catalogVersion, buildDiagnostics(resolvedConflictResolution, resolutionLayer)));
		this.catalogName = catalogName;
		this.conflictKey = conflictKey;
		this.catalogVersion = catalogVersion;
		this.resolvedConflictResolution = resolvedConflictResolution;
		this.resolutionLayer = resolutionLayer;
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
        super(composeMessage(catalogName, conflictKey, catalogVersion, additionalMessage));
        this.catalogName = catalogName;
        this.conflictKey = conflictKey;
        this.catalogVersion = catalogVersion;
        this.resolvedConflictResolution = null;
        this.resolutionLayer = null;
    }

	/**
	 * Composes the exception message: the common conflict preamble (catalog, conflict key, committed version)
	 * optionally followed by an additional diagnostic sentence.
	 *
	 * @param catalogName       name of the catalog where the conflict occurred, must not be null
	 * @param conflictKey       key identifying the conflicting mutation scope, must not be null
	 * @param catalogVersion    the exact catalog version where the conflicting change was committed
	 * @param additionalMessage extra diagnostic detail to append, or {@code null} for none
	 * @return the fully composed exception message
	 */
	@Nonnull
	private static String composeMessage(
		@Nonnull String catalogName,
		@Nonnull ConflictKey conflictKey,
		long catalogVersion,
		@Nullable String additionalMessage
	) {
		final StringBuilder sb = new StringBuilder(256);
		sb.append("Conflicting mutations detected in catalog `").append(catalogName)
			.append("` for conflict key: ").append(conflictKey).append(' ')
			.append("between your transaction and transactions that committed before you. ")
			.append("Conflicting change occurred exactly at catalog version: ").append(catalogVersion).append(". ");
		if (additionalMessage != null) {
			sb.append(additionalMessage);
		}
		return sb.toString();
	}

	/**
	 * Renders the resolved conflict-resolution diagnostics into a human-readable sentence appended to the
	 * base conflict message: the coarse policy that was in force, any granular refinement, and the schema
	 * layer the policy was resolved from.
	 *
	 * @param resolution the effective resolution that was in force, must not be null
	 * @param layer      the schema layer the resolution was taken from, must not be null
	 * @return a diagnostic sentence describing the resolved policy and its source layer
	 */
	@Nonnull
	private static String buildDiagnostics(
		@Nonnull ConflictResolution resolution,
		@Nonnull ConflictResolutionLayer layer
	) {
		final StringBuilder sb = new StringBuilder(128);
		sb.append("The effective conflict resolution in force was `").append(resolution.policy()).append('`');
		if (!resolution.granularity().isEmpty()) {
			sb.append(" with granular refinement ").append(resolution.granularity());
		}
		sb.append(", resolved from the ").append(layer).append(" layer.");
		return sb.toString();
	}

}
