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

package io.evitadb.api.requestResponse.mutation.conflict;

/**
 * Closed, bounded vocabulary naming the granularity a {@link ConflictKey} sits at, independent of the concrete
 * implementation class. Every {@link ConflictKey} maps to exactly one scope via {@link ConflictKey#conflictScope()}.
 *
 * Unlike {@link IncomingConflictScope} — which is a per-transaction containment matcher — this enum is a stable,
 * low-cardinality label set. Its primary consumer is observability: it is the exported label distinguishing
 * *where* contention occurred (a single attribute vs. a whole entity vs. a price vs. a reference) without leaking
 * the unbounded coordinates (primary key, attribute name) the concrete key also carries. Because the exported
 * metric label is derived from this enum rather than from class names, renaming a {@link ConflictKey} class does
 * not silently change the observability contract.
 *
 * The two range-constrained delta keys collapse onto the same scope as their absolute counterparts
 * ({@link AttributeDeltaConflictKey} → {@link #ATTRIBUTE}, {@link ReferenceAttributeDeltaConflictKey} →
 * {@link #REFERENCE_ATTRIBUTE}): the delta encoding is a detection-path optimisation, not a distinct contention
 * granularity an operator would act on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum ConflictScope {

	/**
	 * Catalog-wide scope — {@link CatalogConflictKey}.
	 */
	CATALOG,

	/**
	 * Whole entity-collection scope — {@link CollectionConflictKey}.
	 */
	COLLECTION,

	/**
	 * Whole-entity scope — {@link EntityConflictKey}.
	 */
	ENTITY,

	/**
	 * Single entity attribute — {@link AttributeConflictKey} / {@link AttributeDeltaConflictKey}.
	 */
	ATTRIBUTE,

	/**
	 * Single entity associated data — {@link AssociatedDataConflictKey}.
	 */
	ASSOCIATED_DATA,

	/**
	 * Single entity price — {@link PriceConflictKey}.
	 */
	PRICE,

	/**
	 * Entity price inner-record-handling strategy — {@link PriceInnerRecordHandlingStrategyConflictKey}.
	 */
	PRICE_INNER_RECORD_HANDLING,

	/**
	 * Entity hierarchy placement — {@link HierarchyConflictKey}.
	 */
	HIERARCHY,

	/**
	 * Single entity reference — {@link ReferenceConflictKey}.
	 */
	REFERENCE,

	/**
	 * Single entity reference attribute — {@link ReferenceAttributeConflictKey} /
	 * {@link ReferenceAttributeDeltaConflictKey}.
	 */
	REFERENCE_ATTRIBUTE

}
