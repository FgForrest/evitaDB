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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.Fixture;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.HistogramRequest;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.RegisteredHistogram;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.Invocation;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Counts real index owner lookups through package-local spies, without production counters or clock assertions.
 *
 * The owner spies observe owner-map lookups, not the total work of selection. The many-index fixture also observes
 * producing-set membership probes through real mapped-bitmap spies, and a spy over the owner filter counts how often
 * the counted owners are read. No spy stubs index or bitmap behavior.
 */
@DisplayName("Owner lookups of the reference attribute boundary pick without a sorter")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
class ReferenceAttributeBoundaryCallCountTest {
	/**
	 * Ranks the LIVE candidate first and fetches it once when the lowest PK lies outside the searched scope.
	 */
	@Test
	@DisplayName("Fetches the LIVE boundary once when the lowest candidate lies outside the searched scope")
	void shouldFetchInScopeBoundaryOnceWhenLowestCandidateLiesOutsideScope() {
		assertUngroupedBoundaryLookups(true, true);
	}

	/**
	 * An in-scope candidate that lacks the requested locale leaves the boundary unresolved with a single fetch.
	 */
	@Test
	@DisplayName("Fetches only once when the chosen boundary cannot load")
	void shouldFetchOnlyOnceWhenInScopeCandidateCannotLoad() {
		assertUngroupedBoundaryLookups(false, false);
	}

	/**
	 * Counts owner and partition lookups per ungrouped bound and the fetch and scope-index reads they share.
	 *
	 * @param lowestOutsideScope whether the lowest candidate is absent from the searched scope
	 * @param boundaryLoads whether the chosen candidate's body is available in the requested form
	 */
	private static void assertUngroupedBoundaryLookups(boolean lowestOutsideScope, boolean boundaryLoads) {
		final ReferenceSchemaContract reference = mock(ReferenceSchemaContract.class);
		final EntitySchema ownerSchema = mock(EntitySchema.class);
		final SealedEntitySchema sealedOwnerSchema = mock(SealedEntitySchema.class);
		final AttributeSchemaContract attribute = mock(AttributeSchemaContract.class);
		when(reference.getName()).thenReturn("targets");
		when(reference.getReferencedEntityType()).thenReturn("target");
		when(reference.isReferencedEntityTypeManaged()).thenReturn(true);
		when(reference.getAttribute("score")).thenReturn(Optional.of(attribute));
		when(reference.isIndexedInScope(Scope.LIVE)).thenReturn(true);
		when(reference.getIndexedComponents(Scope.LIVE))
			.thenReturn(Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY));
		when(attribute.getName()).thenReturn("score");
		doReturn(Integer.class).when(attribute).getPlainType();
		final HistogramValueDescriptor descriptor = new HistogramValueDescriptor(
			HistogramValueSource.REFERENCE_ATTRIBUTE, null, "score", Integer.class, false, false, null, null, 0
		);
		final HistogramRequest request = new HistogramRequest(
			reference, "scores", 10, HistogramBehavior.STANDARD, null, Set.of(Scope.LIVE), descriptor, null, Map.of()
		);

		final QueryExecutionContext context = mock(QueryExecutionContext.class);
		final QueryPlanningContext planning = mock(QueryPlanningContext.class);
		final EntityCollection collection = mock(EntityCollection.class);
		final EvitaRequest evitaRequest = mock(EvitaRequest.class);
		final GlobalEntityIndex liveTargets = mock(GlobalEntityIndex.class);
		final Bitmap candidates = new BaseBitmap(11, 12);
		final int expectedPk = lowestOutsideScope ? 12 : 11;
		when(context.getEvitaRequest()).thenReturn(evitaRequest);
		when(evitaRequest.getScopesAsArray()).thenReturn(new Scope[]{Scope.LIVE});
		when(planning.getGlobalEntityIndexIfExists("target", Scope.LIVE)).thenReturn(Optional.of(liveTargets));
		when(liveTargets.getAllPrimaryKeys()).thenReturn(lowestOutsideScope ? new BaseBitmap(12) : candidates);
		final SealedEntity boundary = mock(SealedEntity.class);
		when(boundary.getPrimaryKeyOrThrowException()).thenReturn(expectedPk);
		when(context.fetchEntities(eq("target"), eq(new int[]{expectedPk}), any()))
			.thenReturn(boundaryLoads ? List.of(boundary) : List.of());
		when(context.getQueryContext()).thenReturn(planning);
		when(planning.getEntityType()).thenReturn("owner");
		when(planning.getSchema()).thenReturn(ownerSchema);
		when(context.getEntityCollectionOrThrowException(eq("owner"), anyString())).thenReturn(collection);
		when(collection.getSchema()).thenReturn(sealedOwnerSchema);

		final ReferencedTypeEntityIndex typeIndex = mock(ReferencedTypeEntityIndex.class);
		final OwnerFilterIndex histogramIndex = spy(filter(Integer.class));
		histogramIndex.addRecord(7, 10);
		histogramIndex.addRecord(7, 20);
		final OwnerFilterIndex valueIndex = spy(filter(Integer.class));
		valueIndex.addRecord(71, 10);
		valueIndex.addRecord(72, 20);
		when(planning.getEntityIndex(
			"owner", new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "targets"),
			ReferencedTypeEntityIndex.class
		)).thenReturn(Optional.of(typeIndex));
		when(typeIndex.getHistogramFilterIndex("scores", null)).thenReturn(histogramIndex);
		when(typeIndex.getIndexPrimaryKeys(any())).thenReturn(EmptyBitmap.INSTANCE);
		when(typeIndex.getFilterIndex(reference, attribute, null)).thenReturn(valueIndex);
		when(typeIndex.getReferencedPrimaryKeysForIndexPks(any())).thenReturn(candidates);
		when(typeIndex.getAllReferencedPrimaryKeys()).thenReturn(candidates);

		final CacheableHistogramContract cacheable = mock(CacheableHistogramContract.class);
		when(cacheable.getRawMin()).thenReturn(10);
		when(cacheable.getRawMax()).thenReturn(20);
		final HistogramContract histogram = mock(HistogramContract.class);
		when(cacheable.convertToHistogram(any())).thenReturn(histogram);
		when(cacheable.convertToHistogram(any(), same(boundary), same(boundary))).thenReturn(histogram);
		when(context.analyse(any(AttributeHistogramComputer.class))).thenReturn(() -> cacheable);

		final RegisteredHistogram registration = new RegisteredHistogram(request, null, null, null, null);
		ReferenceHistogramAccumulator.injectHistograms(
			Map.of(), Map.of("targets", List.of(registration)), null, context, ReferenceSummaryAdapter.INSTANCE
		);

		final ArgumentCaptor<int[]> fetchedPks = ArgumentCaptor.forClass(int[].class);
		verify(context).fetchEntities(eq("target"), fetchedPks.capture(), any());
		assertArrayEquals(new int[]{expectedPk}, fetchedPks.getValue());
		if (boundaryLoads) {
			verify(cacheable).convertToHistogram(any(), same(boundary), same(boundary));
		} else {
			verify(cacheable).convertToHistogram(any());
		}
		verify(valueIndex).getRecordsEqualTo(10);
		verify(valueIndex).getRecordsEqualTo(20);
		verify(typeIndex, times(2)).getReferencedPrimaryKeysForIndexPks(any());
		verify(typeIndex).getAllReferencedPrimaryKeys();
		verify(planning).getGlobalEntityIndexIfExists("target", Scope.LIVE);
		verify(planning, never()).getGlobalEntityIndexIfExists("target", Scope.ARCHIVED);
		verify(liveTargets).getAllPrimaryKeys();
		verify(histogramIndex).getRecordsEqualTo(10);
		verify(histogramIndex).getRecordsEqualTo(20);
		verify(histogramIndex, times(2)).getRecordsEqualTo(any());
		verify(typeIndex, times(2)).getIndexPrimaryKeys(any());
		verify(planning, never()).resolvePartition(anyInt());
	}

	/** The first successful witness stops both the producing-index checks and the global candidate merge. */
	@Test
	@DisplayName("should stop at the first candidate whose owner check passes")
	void shouldStopAfterTheFirstSuccessfulWitness() {
		final Fixture fixture = fixture(
			new int[][]{{1, 2, 3}, {1, 2, 3}, {1, 2, 3}},
			new int[][]{{1, 2, 3}, {1, 2, 3}, {1, 2, 3}}, 1, 2, 3
		);
		final ReducedGroupEntityIndex[] indexes = spyIndexes(fixture);

		final ReferenceAttributeBoundaryOracle.Pair pick = fixture.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(1, null), pick);
		verify(indexes[0], times(1)).getOwnerPKsForReferencedEntity(1);
		verify(indexes[0], times(1)).getOwnerPKsForReferencedEntity(anyInt());
		verify(indexes[1], never()).getOwnerPKsForReferencedEntity(anyInt());
		verify(indexes[2], never()).getOwnerPKsForReferencedEntity(anyInt());
	}

	/** A first-index failure still reaches the later producing-index witness before stopping. */
	@Test
	@DisplayName("should check the later producing index after an earlier one fails, then stop")
	void shouldCheckTheLateWitnessAfterEarlierFailures() {
		final Fixture fixture = fixture(new int[][]{{1, 2}, {2}}, new int[][]{{}, {2}}, 1, 2);
		final ReducedGroupEntityIndex[] indexes = spyIndexes(fixture);

		final ReferenceAttributeBoundaryOracle.Pair pick = fixture.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(2, 1), pick);
		verify(indexes[0], times(1)).getOwnerPKsForReferencedEntity(1);
		verify(indexes[0], times(1)).getOwnerPKsForReferencedEntity(2);
		verify(indexes[0], times(2)).getOwnerPKsForReferencedEntity(anyInt());
		verify(indexes[1], times(1)).getOwnerPKsForReferencedEntity(2);
		verify(indexes[1], times(1)).getOwnerPKsForReferencedEntity(anyInt());
		final InOrder order = inOrder((Object[]) indexes);
		order.verify(indexes[0]).getOwnerPKsForReferencedEntity(1);
		order.verify(indexes[0]).getOwnerPKsForReferencedEntity(2);
		order.verify(indexes[1]).getOwnerPKsForReferencedEntity(2);
	}

	/**
	 * Whole-context fallback must exhaust every producing-index witness when all candidates fail. Exhausting is the
	 * contract: with no passing owner the bound keeps every candidate, so each index is asked about each candidate it
	 * produced exactly once and about nothing else.
	 */
	@Test
	@DisplayName("should ask every producing index about each of its candidates once before falling back")
	void shouldExhaustEveryWitnessBeforeAllMissFallback() {
		final Fixture fixture = fixture(
			new int[][]{{1, 2, 3}, {1, 2, 3}, {1, 2, 3}}, new int[][]{{}, {}, {}}, 1, 2, 3
		);
		final ReducedGroupEntityIndex[] indexes = spyIndexes(fixture);

		final ReferenceAttributeBoundaryOracle.Pair pick = fixture.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(1, null), pick);
		for (final ReducedGroupEntityIndex index : indexes) {
			for (final int pk : new int[]{1, 2, 3}) {
				verify(index, times(1)).getOwnerPKsForReferencedEntity(pk);
			}
			verify(index, times(3)).getOwnerPKsForReferencedEntity(anyInt());
		}
	}

	/** The owners are read from the group store once per bound, however many indexes produce a candidate. */
	@Test
	@DisplayName("should read the counted owners once across several producing indexes")
	void shouldReadTheOwnersOnceAcrossProducingIndexes() {
		final Fixture fixture = withSpiedOwners(
			fixture(new int[][]{{1}, {2}, {3}}, new int[][]{{}, {}, {3}}, 1, 2, 3)
		);

		final ReferenceAttributeBoundaryOracle.Pair pick = fixture.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(3, 1), pick);
		verify(fixture.owners(), times(1)).getRecordsEqualTo(any());
	}

	/**
	 * The owners are read at the first nonempty raw lookup, as the exhaustive steps read them, even when the mapping or
	 * the scope then leaves no candidate.
	 */
	@Test
	@DisplayName("should read the counted owners once even when mapping or scope leaves no candidate")
	void shouldReadTheOwnersOnceWhenMappingOrScopeLeavesNoCandidate() {
		final Fixture unmapped = withSpiedOwners(fixture(new int[][]{{1}}, new int[][]{{}}, 1, 5));
		// the filter holds 5 at the value 20, but the reduced index does not track 5, so nothing maps
		unmapped.filters()[0].addRecord(5, 20);
		final Fixture outOfScope = withSpiedOwners(fixture(new int[][]{{1, 9}}, new int[][]{{9}}));

		final ReferenceAttributeBoundaryOracle.Pair unmappedPick = unmapped.actual(20, null);
		final ReferenceAttributeBoundaryOracle.Pair outOfScopePick = outOfScope.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(null, null), unmappedPick);
		verify(unmapped.owners(), times(1)).getRecordsEqualTo(any());
		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(null, null), outOfScopePick);
		verify(outOfScope.owners(), times(1)).getRecordsEqualTo(any());
		// the oracle reads the spied owners too, so it is consulted only after the reads were counted
		unmapped.compare(20, null, null);
		outOfScope.compare(10, null, null);
	}

	/** Disjoint all-miss candidates must not cause a candidate-by-index membership cross product. */
	@Test
	@DisplayName("should merge many disjoint producing indexes without probing their candidate membership")
	void shouldMergeManyDisjointIndexesWithoutMembershipProbes() {
		final MappingObservedIndex[] indexes = new MappingObservedIndex[200];
		final Fixture fixture = disjointIndexes(indexes);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(1, null), fixture.actual(10, null));
		long membershipProbes = 0;
		for (final MappingObservedIndex index : indexes) {
			verify(index, times(5)).getOwnerPKsForReferencedEntity(anyInt());
			for (final Invocation invocation : mockingDetails(index.mappedCandidates).getInvocations()) {
				if (invocation.getMethod().getName().equals("contains")) {
					membershipProbes++;
				}
			}
		}
		assertEquals(0L, membershipProbes);
	}

	/**
	 * Many producing heads are merged in global PK order: a late owned candidate is reached only after every lower
	 * candidate of the earlier indexes was checked, and no index holding only higher candidates is asked about its
	 * owners.
	 */
	@Test
	@DisplayName("should reach a late owned candidate of many producing indexes only after every lower candidate")
	void shouldReachALateOwnedCandidateOnlyAfterEveryLowerCandidate() {
		final MappingObservedIndex[] indexes = new MappingObservedIndex[200];
		final Fixture fixture = disjointIndexes(indexes);
		// index 150 holds 751 to 755 and tracks 751 for the counted owner
		indexes[150].insertPrimaryKeyIfMissing(100, 751);

		final ReferenceAttributeBoundaryOracle.Pair pick = fixture.actual(10, null);

		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(751, 1), pick);
		for (int i = 0; i < 150; i++) {
			verify(indexes[i], times(5)).getOwnerPKsForReferencedEntity(anyInt());
		}
		verify(indexes[150], times(1)).getOwnerPKsForReferencedEntity(751);
		verify(indexes[150], times(1)).getOwnerPKsForReferencedEntity(anyInt());
		for (int i = 151; i < indexes.length; i++) {
			verify(indexes[i], never()).getOwnerPKsForReferencedEntity(anyInt());
		}
	}

	/** A real reduced index that exposes its real mapped bitmap through an unstubbed spy. */
	private static class MappingObservedIndex extends ReducedGroupEntityIndex {
		/** The bitmap returned by the latest real candidate mapping. */
		private Bitmap mappedCandidates;

		/** Creates a representative-attribute partition with no fabricated mapping behavior. */
		private MappingObservedIndex(int rank) {
			super(rank + 1, "owner", new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey("brand", 1), new Serializable[]{rank})
			));
		}

		/** Delegates all mapping to the real index and observes calls on its result without stubbing them. */
		@Nonnull
		@Override
		public Bitmap getReferencedPrimaryKeysForIndexPks(@Nonnull Bitmap indexPrimaryKeys) {
			this.mappedCandidates = spy(super.getReferencedPrimaryKeysForIndexPks(indexPrimaryKeys));
			return this.mappedCandidates;
		}
	}

	/**
	 * Fills `indexes` with spied disjoint producing indexes: index `i` holds the candidates `5i + 1` to `5i + 5` at the
	 * value 10, tracks each for the uncounted owner 200, and every candidate is in scope; only owner 100 counts at the
	 * value.
	 *
	 * @param indexes the array to fill; its length is the number of producing indexes
	 * @return the fixture over the indexes, without a baseline
	 */
	@Nonnull
	private static Fixture disjointIndexes(@Nonnull MappingObservedIndex[] indexes) {
		final FilterIndex[] filters = new FilterIndex[indexes.length];
		final int[] scope = new int[indexes.length * 5];
		for (int i = 0; i < indexes.length; i++) {
			indexes[i] = spy(new MappingObservedIndex(i));
			filters[i] = filter(Integer.class);
			for (int j = 0; j < 5; j++) {
				final int pk = i * 5 + j + 1;
				scope[pk - 1] = pk;
				indexes[i].insertPrimaryKeyIfMissing(200, pk);
				filters[i].addRecord(pk, 10);
			}
		}
		final OwnerFilterIndex owners = filter(Integer.class);
		owners.addRecord(100, 10);
		return new Fixture(filters, indexes, owners, null, new BaseBitmap(scope));
	}

	/** Replaces the owner filter of `fixture` with an unstubbed spy over it, so its lookups can be counted. */
	@Nonnull
	private static Fixture withSpiedOwners(@Nonnull Fixture fixture) {
		return new Fixture(
			fixture.filters(), fixture.indexes(), spy(fixture.owners()), fixture.baseline(), fixture.scope()
		);
	}

	/** Wraps the existing real indexes without sharing an oracle invocation with the measured picker call. */
	@Nonnull
	private static ReducedGroupEntityIndex[] spyIndexes(@Nonnull Fixture fixture) {
		final ReducedGroupEntityIndex[] indexes = new ReducedGroupEntityIndex[fixture.indexes().length];
		for (int i = 0; i < indexes.length; i++) {
			indexes[i] = spy((ReducedGroupEntityIndex) fixture.indexes()[i]);
			fixture.indexes()[i] = indexes[i];
		}
		return indexes;
	}
}
