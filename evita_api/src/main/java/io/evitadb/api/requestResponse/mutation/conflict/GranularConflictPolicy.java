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
 * Describes the sub-entity granularity at which write conflicts are detected and serialized. These
 * refinements only make sense once the coarse {@link ConflictPolicy} has already narrowed conflict
 * detection down to {@link ConflictPolicy#ENTITY} level — they split a single entity-wide conflict
 * key into several finer keys so that concurrent writes touching *different* parts of the same entity
 * no longer serialize against each other.
 *
 * Choosing a finer granularity trades a strong, easy-to-reason-about guarantee (any two writes to the
 * same entity conflict) for higher throughput, and in doing so admits the classic *write-skew* family
 * of anomalies: two concurrent transactions can each read some shared state of an entity, each write a
 * *different* part of that same entity, and both commit — even though the combined result violates an
 * invariant that spanned the two parts. Each constant below documents the specific anomaly it admits;
 * enable a constant only when the data it isolates carries no cross-part invariant that concurrent
 * writers could break, or when a coarser policy / a safe delta mutation guards that invariant instead.
 *
 * Unlike {@link ConflictPolicy} (a single, mutually exclusive coarse scope), granular policies form a
 * set: any subset of these refinements may be active simultaneously under an effective
 * {@link ConflictPolicy#ENTITY} policy. The set is carried by {@link ConflictResolution}.
 *
 * Thread-safety: the enum is immutable and safe to share.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum GranularConflictPolicy {

	/**
	 * Splits the entity-wide conflict key per entity attribute: concurrent mutations touching the *same*
	 * attribute of the same entity still conflict, but mutations touching *different* attributes of the
	 * same entity proceed in parallel.
	 *
	 * Admitted anomaly (write skew across attributes): two transactions may each read several attributes
	 * of an entity, then each update a *different* attribute, and both commit — even if an invariant
	 * linking those attributes (e.g. `discountedPrice <= basePrice`, or `sum(quantities) <= limit`)
	 * is thereby broken. Enable only when attributes of the entity carry no such cross-attribute
	 * invariant, or protect the invariant with a coarser policy or a range-checked delta mutation.
	 *
	 * This policy does not cover attributes of references — see {@link #REFERENCE_ATTRIBUTE} for those.
	 */
	ENTITY_ATTRIBUTE,

	/**
	 * Splits the entity-wide conflict key per reference: concurrent mutations touching the *same* reference
	 * (same reference name and referenced primary key) of the same entity still conflict, but mutations
	 * touching *different* references of the same entity proceed in parallel.
	 *
	 * Admitted anomaly (write skew across references): two transactions may each add, remove or re-point a
	 * *different* reference of the same entity and both commit, even if an invariant over the entity's
	 * reference set (e.g. "exactly one reference of this type may be marked primary", or a cardinality
	 * bound on the group) is thereby violated. Enable only when references of the entity are mutually
	 * independent with respect to such structural invariants.
	 */
	REFERENCE,

	/**
	 * Splits the conflict key per attribute of a reference: concurrent mutations touching the *same*
	 * attribute of the *same* reference of the same entity still conflict, but mutations touching different
	 * reference attributes (or different references) proceed in parallel.
	 *
	 * Admitted anomaly (write skew across reference attributes): two transactions may each update a
	 * *different* attribute of the same reference — or attributes of two different references — and both
	 * commit, even if an invariant linking those reference attributes (e.g. an ordering or a per-reference
	 * budget) is broken. Enable only when reference attributes carry no such cross-attribute invariant.
	 */
	REFERENCE_ATTRIBUTE,

	/**
	 * Splits the entity-wide conflict key per associated data container: concurrent mutations touching the
	 * *same* associated data (same name and locale) of the same entity still conflict, but mutations
	 * touching *different* associated data of the same entity proceed in parallel.
	 *
	 * Admitted anomaly (write skew across associated data): two transactions may each write a *different*
	 * associated data container of the same entity — or an associated data container and an attribute —
	 * and both commit, even if a consistency expectation spanning those containers is broken. Because
	 * associated data is opaque to the engine (arbitrary client payload), such invariants cannot be
	 * checked; enable only when associated data containers of the entity are independent.
	 */
	ASSOCIATED_DATA,

	/**
	 * Splits the entity-wide conflict key per price: concurrent mutations touching the *same* price (same
	 * price list, currency and inner record id) of the same entity still conflict, but mutations touching
	 * *different* prices of the same entity proceed in parallel.
	 *
	 * Admitted anomaly (write skew across prices): two transactions may each write a *different* price of
	 * the same entity and both commit, even if a cross-price invariant (e.g. "sale price must not exceed
	 * the base price", or a single-selling-price rule under a price inner record handling strategy) is
	 * thereby violated. Enable only when the prices of the entity are mutually independent; note that the
	 * price inner record handling strategy itself is not price-scoped and always serializes at entity level.
	 */
	PRICE,

	/**
	 * Splits the entity-wide conflict key for hierarchy placement: concurrent mutations repositioning the
	 * *same* entity within the hierarchy still conflict, but placements of *different* entities proceed in
	 * parallel.
	 *
	 * Admitted anomaly (write skew across hierarchy placements): two transactions may each reposition a
	 * *different* node and both commit, even though the combined result violates a global structural
	 * invariant of the tree — most notably introducing a cycle (each move is individually acyclic, but
	 * together they form a loop) or breaking a single-root / ordering expectation. Enable only when
	 * concurrent hierarchy edits cannot jointly violate such tree-wide invariants.
	 */
	HIERARCHY

}
