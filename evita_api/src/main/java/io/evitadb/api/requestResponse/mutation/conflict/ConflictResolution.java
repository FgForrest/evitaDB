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


import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
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

	/**
	 * Transitional bridge that flattens this two-axis resolution into the historical single
	 * {@link EnumSet} of {@link ConflictPolicy} constants understood by the not-yet-migrated conflict
	 * key generation path. The coarse policy maps to itself (with {@link ConflictPolicy#NONE} yielding an
	 * empty set, i.e. last-writer-wins), and every {@link GranularConflictPolicy} maps to the identically
	 * named transitional {@link ConflictPolicy} constant.
	 *
	 * This method exists only until all call sites consume {@link ConflictResolution} directly; it — and
	 * the transitional granular {@link ConflictPolicy} constants it relies on — will be removed then.
	 *
	 * @return the equivalent legacy flat policy set
	 */
	@Deprecated(forRemoval = true, since = "2026.2")
	@Nonnull
	public EnumSet<ConflictPolicy> toLegacyPolicySet() {
		final EnumSet<ConflictPolicy> result = EnumSet.noneOf(ConflictPolicy.class);
		switch (this.policy) {
			case NONE:
				// empty set is the historical encoding of last-writer-wins
				break;
			case CATALOG:
				result.add(ConflictPolicy.CATALOG);
				break;
			case COLLECTION:
				result.add(ConflictPolicy.COLLECTION);
				break;
			case ENTITY:
				result.add(ConflictPolicy.ENTITY);
				for (GranularConflictPolicy granularPolicy : this.granularity) {
					result.add(ConflictPolicy.valueOf(granularPolicy.name()));
				}
				break;
			default:
				throw new GenericEvitaInternalError(
					"Unexpected coarse conflict policy: " + this.policy + "!"
				);
		}
		return result;
	}

	/**
	 * Transitional bridge that interprets a historical flat {@link ConflictPolicy} set (as produced by the
	 * pre-split configuration form) as a {@link ConflictResolution}. The coarsest non-granular member wins
	 * — a catalog- or collection-wide lock already subsumes any finer refinement present in the same set —
	 * while an empty set is read as {@link ConflictPolicy#NONE} (last-writer-wins). When only granular
	 * members are present, the coarse policy defaults to {@link ConflictPolicy#ENTITY}.
	 *
	 * This method exists only to keep parsing the deprecated flat-list configuration form and will be
	 * removed together with the transitional granular {@link ConflictPolicy} constants.
	 *
	 * @param policies the legacy flat policy set, never null
	 * @return the equivalent {@link ConflictResolution}
	 */
	@Deprecated(forRemoval = true, since = "2026.2")
	@Nonnull
	public static ConflictResolution fromLegacyPolicySet(@Nonnull Collection<ConflictPolicy> policies) {
		if (policies.isEmpty()) {
			return new ConflictResolution(ConflictPolicy.NONE);
		}
		// a catalog- or collection-wide lock subsumes any finer scope declared alongside it
		if (policies.contains(ConflictPolicy.CATALOG)) {
			return new ConflictResolution(ConflictPolicy.CATALOG);
		}
		if (policies.contains(ConflictPolicy.COLLECTION)) {
			return new ConflictResolution(ConflictPolicy.COLLECTION);
		}
		// entity scope (explicit or implied by the presence of granular members)
		final EnumSet<GranularConflictPolicy> granularPolicies = EnumSet.noneOf(GranularConflictPolicy.class);
		for (ConflictPolicy policy : policies) {
			if (policy.isGranular()) {
				granularPolicies.add(GranularConflictPolicy.valueOf(policy.name()));
			}
		}
		return new ConflictResolution(ConflictPolicy.ENTITY, granularPolicies);
	}

}
