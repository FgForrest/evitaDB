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

package io.evitadb.core.metric.event.transaction;

import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionLayer;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Event fired when a transaction is rolled back because its changes conflicted with a concurrently committed
 * transaction under the effective conflict-resolution policy. It isolates conflict-induced rollbacks — which the
 * generic `TransactionFinished(resolution=ROLLBACK)` counter lumps together with user-initiated and
 * processing-error rollbacks — and breaks them down by the three bounded dimensions the conflict path already
 * computes: the coarse policy in force, the schema layer it was resolved from, and the conflict scope. The
 * conflict *rate* is then a query-time ratio against {@link TransactionFinishedEvent}'s invocation count.
 *
 * Both conflict families are counted: an absolute-write conflict (raised with full resolution diagnostics) and
 * a commutative delta-merge conflict (raised as {@code ConflictingCatalogCommutativeMutationException}, a
 * subclass that carries no resolution diagnostics). For the commutative case the {@link #conflictScope} label
 * is still populated from the conflicting delta key (attribute / reference attribute), while
 * {@link #conflictPolicy} and {@link #resolutionLayer} degrade to the {@code UNKNOWN} sentinel — the rollback is
 * counted rather than silently dropped, at the cost of the finer breakdown on that path.
 *
 * The event is fully determined at construction (a point-in-time count), so it calls {@link #begin()} and
 * {@link #end()} in the constructor and is committed by the caller; no separate finish step is needed. Only
 * bounded dimensions are exported as labels — the unbounded coordinates the conflict key also carries (primary
 * key, attribute name, catalog version) stay in the exception message for log-level forensics, never in a metric
 * label, to keep the metric store's series count bounded.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".Conflict")
@Label("Transaction conflict")
@ExportInvocationMetric(label = "Transaction conflicts detected")
@Description(
	"Event fired when a transaction is rolled back because its changes conflicted with a concurrently " +
		"committed transaction under the effective conflict-resolution policy. Broken down by the coarse " +
		"policy in force, the schema layer it was resolved from, and the conflict scope."
)
@Getter
public class TransactionConflictEvent extends AbstractTransactionEvent {

	/**
	 * Sentinel used for a label whose source diagnostic was absent (the older single-argument conflict
	 * exception constructor leaves the resolved policy / layer null). The live conflict path always carries
	 * both, so this only guards defensive / test construction.
	 */
	private static final String UNKNOWN = "UNKNOWN";

	@Label("Conflict policy")
	@Description("The coarse conflict policy (NONE/CATALOG/COLLECTION/ENTITY) in force for the conflicting scope.")
	@ExportMetricLabel
	private final String conflictPolicy;

	@Label("Resolution layer")
	@Description("The schema layer the policy was resolved from (ENTITY_SCHEMA/CATALOG_SCHEMA/ENGINE_DEFAULT).")
	@ExportMetricLabel
	private final String resolutionLayer;

	@Label("Conflict scope")
	@Description("The granularity of the conflicting key (e.g. entity, attribute, price, reference).")
	@ExportMetricLabel
	private final String conflictScope;

	/**
	 * Creates the event straight off a caught {@link ConflictingCatalogMutationException}, reading its
	 * conflict-resolution diagnostics into the three bounded labels. Null diagnostics (raised without
	 * resolution context) map to the {@link #UNKNOWN} sentinel rather than throwing.
	 *
	 * @param catalogName the catalog the conflicting transaction targeted, must not be null
	 * @param conflict    the conflict exception that aborted the transaction, must not be null
	 */
	public TransactionConflictEvent(
		@Nonnull String catalogName,
		@Nonnull ConflictingCatalogMutationException conflict
	) {
		super(catalogName);
		this.begin();
		final ConflictResolution resolution = conflict.getResolvedConflictResolution();
		this.conflictPolicy = resolution == null ? UNKNOWN : resolution.policy().name();
		final ConflictResolutionLayer layer = conflict.getResolutionLayer();
		this.resolutionLayer = layer == null ? UNKNOWN : layer.name();
		this.conflictScope = scopeOf(conflict.getConflictKey());
		this.end();
	}

	/**
	 * Maps a {@link ConflictKey} to its bounded, stable scope label. Delegates to
	 * {@link ConflictKey#conflictScope()} so the exported label is decoupled from the concrete key class name;
	 * a null key (not expected on the live path) maps to the {@link #UNKNOWN} sentinel.
	 *
	 * @param key the conflicting key, may be null on a defensively-constructed exception
	 * @return the bounded scope label, never null
	 */
	@Nonnull
	private static String scopeOf(@Nullable ConflictKey key) {
		return key == null ? UNKNOWN : key.conflictScope().name();
	}

}
