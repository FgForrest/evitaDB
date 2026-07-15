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


import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;

/**
 * Immutable value object describing the complete conflict resolution setting at a single declaration
 * level (engine configuration, catalog schema or entity schema). It combines the two orthogonal axes
 * of the conflict model:
 *
 * - {@link #policy()} — the coarse, mutually exclusive scope at which conflicts are detected
 *   ({@link ConflictPolicy#NONE}, {@link ConflictPolicy#CATALOG}, {@link ConflictPolicy#COLLECTION} or
 *   {@link ConflictPolicy#ENTITY}),
 * - {@link #granularity()} — the set of sub-entity refinements ({@link GranularConflictPolicy}) that
 *   further split the entity-wide conflict key.
 *
 * The two axes are not independent: sub-entity refinements are only meaningful once conflicts are
 * already scoped down to a single entity, therefore a non-empty {@link #granularity()} is legal only
 * when {@link #policy()} is {@link ConflictPolicy#ENTITY}. This invariant is enforced by the
 * constructor, making illegal combinations unrepresentable.
 *
 * The same record is used verbatim at every coarse declaration level; a `null` value at a schema level
 * means "inherit from the enclosing level" (entity schema inherits from catalog schema, which inherits
 * from the engine configuration). Resolution is a whole-record override — the most specific non-null
 * {@link ConflictResolution} wins entirely, with no per-field merging across levels.
 *
 * Thread-safety: the record is deeply immutable — the {@link #granularity()} set is defensively copied
 * at construction. For allocation reasons on the conflict-detection path the accessor returns the
 * internal set directly; callers must treat it as read-only and never mutate it.
 *
 * @param policy      the coarse conflict scope, never null
 * @param granularity the sub-entity refinements, never null (empty when no refinement applies);
 *                    non-empty only when {@code policy == }{@link ConflictPolicy#ENTITY}
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ConflictResolution(
	@Nonnull ConflictPolicy policy,
	@Nonnull EnumSet<GranularConflictPolicy> granularity
) implements Serializable {

	/**
	 * Canonical constructor enforcing the granularity-implies-entity invariant and defensively copying
	 * the (mutable) granularity set so the resulting value object is immutable.
	 *
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if a non-empty granularity is combined with
	 *         a coarse policy other than {@link ConflictPolicy#ENTITY}
	 */
	public ConflictResolution {
		Assert.notNull(policy, "Conflict policy must not be null!");
		// null granularity is tolerated and normalized to an empty set for caller convenience
		granularity = granularity == null
			? EnumSet.noneOf(GranularConflictPolicy.class)
			: EnumSet.copyOf(granularity);
		Assert.isTrue(
			granularity.isEmpty() || policy == ConflictPolicy.ENTITY,
			"Granular conflict policies (" + granularity + ") can only be declared under the " +
				ConflictPolicy.ENTITY + " conflict policy, but the coarse policy is " + policy + "!"
		);
	}

	/**
	 * Convenience constructor for an entity-or-coarser policy with no sub-entity refinements.
	 *
	 * @param policy the coarse conflict scope, never null
	 */
	public ConflictResolution(@Nonnull ConflictPolicy policy) {
		this(policy, EnumSet.noneOf(GranularConflictPolicy.class));
	}

	/**
	 * Returns true if any sub-entity refinement is active. This can only ever be true when
	 * {@link #policy()} is {@link ConflictPolicy#ENTITY}.
	 *
	 * @return true if at least one {@link GranularConflictPolicy} is set
	 */
	public boolean hasGranularity() {
		return !this.granularity.isEmpty();
	}

}
