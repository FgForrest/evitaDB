/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.query.require;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * Standard implementation of {@link FetchRequirementCollector} used by the query planner to accumulate all
 * {@link EntityContentRequire} constraints that must be satisfied when entities are prefetched.
 *
 * **Initialisation:** The collector can be seeded with an explicit {@link EntityFetch} from the query's `require`
 * clause (via the single-argument constructor), or created empty and populated solely through implicit requirements
 * contributed by ordering and filtering translators.
 *
 * **"At least this", not "exactly this".** This collector answers a different question from the fold that shapes
 * the response, and the two rules are deliberately not the same one:
 *
 * - this collector says **load at least this**. It may widen freely — a superset is always a correct answer to it,
 *   because loading a reference nobody projects costs work and nothing else.
 * - {@link EntityFetchRequire#combineDuplicateRequirements(EntityContentRequire[])} says **return exactly this**,
 *   and therefore refuses a pair of client requirements that disagree instead of widening it.
 *
 * The widening is what makes the two rules coexist. Every requirement entering this collector is first admitted
 * through {@link EntityContentRequire#forPrefetch()}, which strips the output projections — for a
 * {@link ReferenceContent} its `filterBy`, `orderBy` and chunking constraints. That matters because this collector
 * is fed from two unrelated sources: the client's own `entityFetch`, and the requirements the query planner invents
 * on his behalf (a bare `referenceContent` for a reference named by `referenceHaving`, a
 * `referenceContentWithAttributes` carrying the sort attribute for a reference ordered by). Without the strip, an
 * ordinary query filtering its `referenceContent` while ordering by the same reference would meet the client-facing
 * refusal here, during planning, for a conflict the client never wrote.
 *
 * The client never observes the widened requirement. The prefetched entity is narrowed back down from his own
 * `EvitaRequest`: `EntityCollection#limitEntityInternal` builds fresh predicates from it, and the reference filter,
 * order and page come from `EvitaRequest#getReferenceEntityFetch()` and `ServerChunkTransformerAccessor`, both
 * reading the query he actually sent. The prefetch path and the index path therefore return the identical body.
 *
 * **Merging logic:** Requirements are indexed internally by their runtime class. When a new requirement arrives:
 * 1. If no requirement of that class exists yet, it is stored directly.
 * 2. If the new requirement is *fully contained within* an existing requirement of the same class, it is silently
 *    discarded (the existing one already covers it). This is the branch that dropping a redundant requirement lives
 *    in, and it is wider than combinability: a `referenceContent("brand")` contributed by one translator is
 *    contained within a `referenceContentAll()` contributed by another, so only the broader one is prefetched even
 *    though the two are not combinable. The containment is tested in both directions - when the *existing* one is
 *    contained within the newcomer, the newcomer replaces it - so that the outcome does not depend on the order in
 *    which two nested requirements happened to be contributed, and so that a nested pair never reaches step 3.
 * 3. If the new requirement is *combinable with* an existing one of the same class (e.g., two `AttributeContent`
 *    instances that together cover a superset of attribute names), they are merged in place. The merge may still be
 *    **refused** with an {@link EvitaInvalidUsageException} for a disagreement the strip does not cover — two
 *    `accompanyingPriceContent` requirements computing one price from different price lists, say.
 * 4. Otherwise the new requirement is appended as an additional entry for that class (rare, occurs for semantically
 *    incompatible instances of the same concrete type).
 *
 * This ensures that `getRequirementsToPrefetch()` returns the minimal non-redundant set of requirements — unless
 * a pair was refused, in which case the exception surfaces during query planning — which is then used to build the
 * actual {@link EntityFetch} passed to the entity-fetching layer.
 *
 * **Lifecycle:** Not thread-safe; a single instance is used within the context of one query planning pass and
 * is not shared across threads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class DefaultPrefetchRequirementCollector implements FetchRequirementCollector {
	public static final EntityContentRequire[] EMPTY_REQUIREMENTS = EntityContentRequire.EMPTY_ARRAY;
	private LinkedHashMap<Class<? extends EntityContentRequire>, EntityContentRequire[]> requirements;

	public DefaultPrefetchRequirementCollector() {
		this(null);
	}

	public DefaultPrefetchRequirementCollector(@Nullable EntityFetch entityFetch) {
		if (entityFetch != null) {
			this.requirements = CollectionUtils.createLinkedHashMap(8);
			addRequirementToPrefetchInternal(entityFetch.getRequirements());
		}
	}

	@Override
	public void addRequirementsToPrefetch(@Nonnull EntityContentRequire... require) {
		if (this.requirements == null) {
			this.requirements = CollectionUtils.createLinkedHashMap(8);
		}
		addRequirementToPrefetchInternal(require);
	}

	@Nonnull
	@Override
	public EntityContentRequire[] getRequirementsToPrefetch() {
		return this.requirements == null ?
			EMPTY_REQUIREMENTS :
			this.requirements.values()
				.stream()
				.flatMap(Arrays::stream)
				.toArray(EntityContentRequire[]::new);
	}

	/**
	 * Retrieves the entity fetch requirements that have been refined and collected.
	 *
	 * @return an EntityFetch instance containing the refined requirements if present, otherwise null
	 */
	@Nullable
	public EntityFetch getEntityFetch() {
		return isEmpty() ?
			null :
			new EntityFetch(
				getRequirementsToPrefetch()
			);
	}

	/**
	 * Checks if there is any fetch requirement defined.
	 *
	 * @return true if the requirements is null
	 */
	public boolean isEmpty() {
		return this.requirements == null;
	}

	/**
	 * Adds the given array of {@link EntityContentRequire} requirements to the internal requirements map. This is the
	 * single door into the map — both the seeding constructor and
	 * {@link #addRequirementsToPrefetch(EntityContentRequire...)} come through here.
	 *
	 * Every requirement is first admitted through {@link EntityContentRequire#forPrefetch()}, which drops the output
	 * projections that say nothing about what has to be loaded, so that no restriction the client wrote ever reaches
	 * the union and collides with a requirement the query planner contributed. It is then matched against the ones
	 * already registered for its class, in registration order. When it is fully contained within one of them it is
	 * dropped as redundant; otherwise, when one of them is combinable with it, that entry is replaced by the merged
	 * requirement; otherwise the requirement is appended as a further entry for its class.
	 *
	 * @param require an array of {@link EntityContentRequire} requirements to be added
	 * @throws EvitaInvalidUsageException when a requirement addresses the same thing as an already registered one
	 *                                    but contradicts it, so that the two cannot be merged
	 */
	private void addRequirementToPrefetchInternal(@Nonnull EntityContentRequire[] require) {
		for (final EntityContentRequire originalRequirement : require) {
			// the prefetch asks what must be loaded, never how the loaded data is projected into the response
			final EntityContentRequire theRequirement = originalRequirement.forPrefetch();
			this.requirements.compute(
				theRequirement.getClass(),
				(aClass, existing) -> {
					if (existing == null) {
						return new EntityContentRequire[]{theRequirement};
					}
					for (int i = 0; i < existing.length; i++) {
						final EntityContentRequire existingRequire = existing[i];
						if (theRequirement.isFullyContainedWithin(existingRequire)) {
							return existing;
						} else if (existingRequire.isFullyContainedWithin(theRequirement)) {
							// the union keeps the wider of two nested requirements, and which of them arrived first says
							// nothing about what has to be loaded - without this, a pair that nests one way round would be
							// dropped as redundant while the same pair in the other order would have to be reconciled by
							// `combineWith`, which answers the stricter client-facing question and may refuse it
							existing[i] = theRequirement;
							return existing;
						} else if (existingRequire.isCombinableWith(theRequirement)) {
							existing[i] = existingRequire.combineWith(theRequirement);
							return existing;
						}
					}
					return ArrayUtils.insertRecordIntoArrayOnIndex(theRequirement, existing, existing.length);
				}
			);
		}
	}

}
