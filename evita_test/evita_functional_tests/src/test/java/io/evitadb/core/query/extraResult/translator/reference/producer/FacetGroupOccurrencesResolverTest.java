/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.facet.FacetReferenceIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the resolver of the groups each facet of the reference summary is referenced under, built by
 * {@link ReferenceSummaryProducer#createFacetGroupOccurrencesResolver(java.util.Set, Map, List)} over real facet
 * indexes. The reference summary lists a facet referenced under several groups once in each of them, and every entry
 * asks the resolver for the occurrences of its facet. The maps holding the facet indexes count the look-ups of the
 * reference, which tells how many times the resolver resolved a facet - one look-up of the global index per resolution
 * in a query of one scope - and how many searched indexes it probed doing so. The entries share the impact of the
 * selection through the shared occurrences as well.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Facet group occurrences resolver")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FACET)
@Tag(REFERENCE)
class FacetGroupOccurrencesResolverTest {
	private static final String REFERENCE_NAME = "tag";
	private static final ReferenceSchema REFERENCE_SCHEMA = ReferenceSchema._internalBuild(
		REFERENCE_NAME, "tagEntity", true, Cardinality.ZERO_OR_MORE,
		"tagGroup", true,
		new ScopedReferenceIndexType[]{new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)},
		new Scope[]{Scope.LIVE}
	);
	/**
	 * The facet referenced under each of {@link #GROUPS}.
	 */
	private static final int FACET_IN_SEVERAL_GROUPS = 10;
	/**
	 * The facet referenced under the first of {@link #GROUPS} only.
	 */
	private static final int FACET_IN_ONE_GROUP = 11;
	/**
	 * The groups {@link #FACET_IN_SEVERAL_GROUPS} is referenced under.
	 */
	private static final int[] GROUPS = {1, 2, 3, 4};
	/**
	 * The number of indexes the reference summary is computed from.
	 */
	private static final int SEARCHED_INDEX_COUNT = 3;

	/**
	 * Returns the entity referencing the passed facet under the passed group in the searched index of the passed order.
	 *
	 * @param facetId    the facet
	 * @param groupId    the group
	 * @param indexOrder the order of the searched index
	 * @return the primary key of the entity
	 */
	private static int entityOf(int facetId, int groupId, int indexOrder) {
		return facetId * 1000 + groupId * 10 + indexOrder;
	}

	/**
	 * Creates the facet index of {@link #REFERENCE_NAME} the searched index of the passed order holds - every facet in
	 * every group it is referenced under, by an entity of its own.
	 *
	 * @param indexOrder the order of the searched index
	 * @return the facet index
	 */
	@Nonnull
	private static FacetReferenceIndex createFacetIndex(int indexOrder) {
		final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
		for (final int groupId : GROUPS) {
			index.addFacet(FACET_IN_SEVERAL_GROUPS, groupId, entityOf(FACET_IN_SEVERAL_GROUPS, groupId, indexOrder));
		}
		index.addFacet(FACET_IN_ONE_GROUP, GROUPS[0], entityOf(FACET_IN_ONE_GROUP, GROUPS[0], indexOrder));
		return index;
	}

	/**
	 * Returns the entities referencing the passed facet under the passed group in all the searched indexes - what
	 * the statistics of the facet in the group collect.
	 *
	 * @param facetId the facet
	 * @param groupId the group
	 * @return the entities
	 */
	@Nonnull
	private static Bitmap entitiesOf(int facetId, int groupId) {
		return new BaseBitmap(
			IntStream.range(0, SEARCHED_INDEX_COUNT).map(it -> entityOf(facetId, groupId, it)).toArray()
		);
	}

	@Test
	@DisplayName("should resolve a facet referenced under several groups once for all of its entries")
	void shouldResolveFacetOnceForAllItsEntries() {
		final CountingFacetIndexMap globalFacetIndex = new CountingFacetIndexMap(
			createFacetIndex(SEARCHED_INDEX_COUNT)
		);
		final CountingFacetIndexMap[] searchedFacetIndexes = IntStream.range(0, SEARCHED_INDEX_COUNT)
			.mapToObj(it -> new CountingFacetIndexMap(createFacetIndex(it)))
			.toArray(CountingFacetIndexMap[]::new);
		final FacetGroupOccurrences.Resolver resolver = ReferenceSummaryProducer.createFacetGroupOccurrencesResolver(
			EnumSet.of(Scope.LIVE),
			Map.of(Scope.LIVE, globalFacetIndex),
			List.of(searchedFacetIndexes)
		);

		// the reference summary asks once for each of its entries - one for each group of a facet
		final FacetGroupOccurrences[] occurrencesOfEntries = new FacetGroupOccurrences[GROUPS.length];
		for (int i = 0; i < GROUPS.length; i++) {
			occurrencesOfEntries[i] = resolver.resolve(
				REFERENCE_SCHEMA, FACET_IN_SEVERAL_GROUPS, GROUPS[i], entitiesOf(FACET_IN_SEVERAL_GROUPS, GROUPS[i])
			);
		}
		final FacetGroupOccurrences occurrencesInOneGroup = resolver.resolve(
			REFERENCE_SCHEMA, FACET_IN_ONE_GROUP, GROUPS[0], entitiesOf(FACET_IN_ONE_GROUP, GROUPS[0])
		);

		int searchedIndexLookUps = 0;
		for (final CountingFacetIndexMap searchedFacetIndex : searchedFacetIndexes) {
			searchedIndexLookUps += searchedFacetIndex.getLookUps();
		}
		// five entries of two facets: the global index is looked up once for each facet, and the searched indexes once
		// for each group of the facet other than the group of the entry resolving it
		assertEquals(
			"resolutions 2, searched index look-ups " + (GROUPS.length - 1) * SEARCHED_INDEX_COUNT,
			"resolutions " + globalFacetIndex.getLookUps() + ", searched index look-ups " + searchedIndexLookUps,
			"the resolutions must equal the facets, not the entries"
		);
		for (final FacetGroupOccurrences occurrences : occurrencesOfEntries) {
			assertSame(occurrencesOfEntries[0], occurrences, "every entry of the facet must share its occurrences");
		}

		final FacetGroupOccurrences occurrences = occurrencesOfEntries[0];
		assertFalse(occurrences.isSingleGroup());
		assertEquals(List.of(1, 2, 3, 4), occurrences.getGroups(null));
		for (final int groupId : GROUPS) {
			assertArrayEquals(
				entitiesOf(FACET_IN_SEVERAL_GROUPS, groupId).getArray(), occurrences.getEntityIds(groupId).getArray()
			);
		}
		assertTrue(occurrencesInOneGroup.isSingleGroup());
		assertArrayEquals(
			entitiesOf(FACET_IN_ONE_GROUP, GROUPS[0]).getArray(),
			occurrencesInOneGroup.getEntityIds(GROUPS[0]).getArray()
		);
	}

	@Test
	@DisplayName("should resolve the same occurrences whichever entry of the facet asks first")
	void shouldResolveSameOccurrencesWhicheverEntryAsksFirst() {
		final List<Map<String, FacetReferenceIndex>> searchedFacetIndexes = IntStream.range(0, SEARCHED_INDEX_COUNT)
			.mapToObj(it -> (Map<String, FacetReferenceIndex>) new CountingFacetIndexMap(createFacetIndex(it)))
			.toList();
		final Map<Scope, Map<String, FacetReferenceIndex>> globalFacetIndexes = Map.of(
			Scope.LIVE, new CountingFacetIndexMap(createFacetIndex(SEARCHED_INDEX_COUNT))
		);
		for (final int firstGroupId : GROUPS) {
			final FacetGroupOccurrences occurrences = ReferenceSummaryProducer.createFacetGroupOccurrencesResolver(
				EnumSet.of(Scope.LIVE), globalFacetIndexes, searchedFacetIndexes
			).resolve(
				REFERENCE_SCHEMA, FACET_IN_SEVERAL_GROUPS, firstGroupId,
				entitiesOf(FACET_IN_SEVERAL_GROUPS, firstGroupId)
			);
			assertEquals(List.of(1, 2, 3, 4), occurrences.getGroups(null));
			assertEquals(List.of(1, 2, 3, 4), occurrences.getGroups(Scope.LIVE));
			for (final int groupId : GROUPS) {
				assertArrayEquals(
					entitiesOf(FACET_IN_SEVERAL_GROUPS, groupId).getArray(),
					occurrences.getEntityIds(groupId).getArray(),
					"group " + groupId + " resolved by the entry of group " + firstGroupId
				);
			}
		}
	}

	@Test
	@DisplayName("should compute the impact once for all entries of the facet")
	void shouldComputeImpactOnceForAllEntriesOfFacet() {
		final FacetGroupOccurrences.Resolver resolver = ReferenceSummaryProducer.createFacetGroupOccurrencesResolver(
			EnumSet.of(Scope.LIVE),
			Map.of(Scope.LIVE, new CountingFacetIndexMap(createFacetIndex(SEARCHED_INDEX_COUNT))),
			List.of(new CountingFacetIndexMap(createFacetIndex(0)))
		);
		final AtomicInteger impactComputations = new AtomicInteger();
		for (final int groupId : GROUPS) {
			final FacetGroupOccurrences occurrences = resolver.resolve(
				REFERENCE_SCHEMA, FACET_IN_SEVERAL_GROUPS, groupId, entitiesOf(FACET_IN_SEVERAL_GROUPS, groupId)
			);
			// an impact that is not requested is NULL, and it is computed once as well
			assertNull(
				occurrences.computeImpactIfAbsent(
					() -> {
						impactComputations.incrementAndGet();
						return null;
					}
				)
			);
		}
		assertEquals(1, impactComputations.get(), "the impact must be computed for the first entry only");
	}

	/**
	 * A map of the facet indexes of one entity index holding a single facet index of {@link #REFERENCE_NAME}, counting
	 * how many times the facet index has been looked up.
	 */
	private static class CountingFacetIndexMap extends HashMap<String, FacetReferenceIndex> {
		private int lookUps;

		/**
		 * Creates the map holding the passed facet index.
		 *
		 * @param facetIndex the facet index of {@link #REFERENCE_NAME}
		 */
		CountingFacetIndexMap(@Nonnull FacetReferenceIndex facetIndex) {
			super(2);
			put(REFERENCE_NAME, facetIndex);
		}

		@Override
		public FacetReferenceIndex get(Object key) {
			if (REFERENCE_NAME.equals(key)) {
				this.lookUps++;
			}
			return super.get(key);
		}

		/**
		 * Returns the number of look-ups of the facet index of {@link #REFERENCE_NAME}.
		 *
		 * @return the number of look-ups
		 */
		int getLookUps() {
			return this.lookUps;
		}

	}

}
