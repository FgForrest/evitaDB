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
 * Per-item override of the conflict resolution granularity, declared directly on an individual schema
 * element (an entity attribute, a reference, an attribute of a reference, or an associated data
 * definition). It refines — for that one element only — the {@link ConflictResolution} resolved from
 * the enclosing entity schema, catalog schema and engine configuration.
 *
 * This vocabulary is deliberately tiny and, unlike {@link GranularConflictPolicy}, it never names a
 * concrete granular constant: the *kind* of granularity is implied by *where* the override sits. On an
 * attribute schema {@link #GRANULAR} means {@link GranularConflictPolicy#ENTITY_ATTRIBUTE}; on a
 * reference schema it means {@link GranularConflictPolicy#REFERENCE}; on an attribute nested in a
 * reference it means {@link GranularConflictPolicy#REFERENCE_ATTRIBUTE}; on an associated data schema it
 * means {@link GranularConflictPolicy#ASSOCIATED_DATA}. This keeps the declaration site from being able
 * to name a granularity that does not belong to it.
 *
 * There is intentionally no per-item `NONE` (per-field last-writer-wins): dropping conflict detection is
 * only expressible at the coarse {@link ConflictPolicy} level. Allowing it here would let a single field
 * silently opt out of all consistency; the omission can be lifted later if a concrete use-case appears,
 * whereas the reverse (removing it once shipped) would be a breaking change.
 *
 * Thread-safety: the enum is immutable and safe to share.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum ConflictResolutionOverride {

	/**
	 * Default. The item carries no opinion of its own and follows whatever the {@link ConflictResolution}
	 * resolved from the enclosing entity schema, catalog schema or engine configuration dictates.
	 */
	INHERITED,

	/**
	 * The item is given its own sub-entity conflict key, isolating concurrent writes that touch only this
	 * item from writes touching other parts of the same entity. The concrete {@link GranularConflictPolicy}
	 * applied is implied by the declaration site (see the enum description). Choosing this admits the
	 * write-skew anomaly documented on the corresponding {@link GranularConflictPolicy} constant.
	 */
	GRANULAR,

	/**
	 * The item explicitly serializes on the whole entity: writes touching it conflict with any concurrent
	 * write to the same entity, regardless of any granular refinement resolved for its siblings. Use this
	 * to pin a consistency-critical item at entity level even when the surrounding schema opts into finer
	 * granularity.
	 */
	ENTITY

}
