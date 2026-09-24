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

package io.evitadb.core.query.sort.reference.sorter;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReducedIndexResolver.ResolvedReducedIndexes;
import io.evitadb.core.query.sort.utils.MockSortedRecordsSupplier;
import io.evitadb.dataType.Scope;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link PickFirstReducedIndexResolver} against real reduced indexes and a real
 * {@link io.evitadb.index.membership.ReducedIndexMembership}: which candidate indexes it probes for a selection, which
 * candidates it rejects, how it orders the kept indexes, and what it memoizes.
 *
 * The standard fixture of the live scope (reference `items`, index primary key = 100 + target):
 *
 * | index | target | owners  | membership |
 * |-------|--------|---------|------------|
 * | 101   | 1      | 1, 2    | covered    |
 * | 102   | 2      | 3       | covered    |
 * | 103   | 3      | 4       | covered    |
 * | 104   | 4      | 1, 5    | residual   |
 * | 105   | 5      | 6       | residual   |
 * | 106   | 6      | 7, 8, 9 | covered    |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Pick-first reduced index resolver")
@Tag(ENGINE)
@Tag(ORDER)
class PickFirstReducedIndexResolverTest {
	/**
	 * The indexes the standard fixture covers in its membership.
	 */
	private static final int[] COVERED = {101, 102, 103, 106};
	/**
	 * Six owners covering every index of the standard fixture but 105, as many covered owners as the family has
	 * indexes.
	 */
	private static final int[] WIDE_SELECTION = {1, 2, 3, 4, 7, 8};

	/**
	 * The ways the resolver can find the candidate indexes of one selection.
	 */
	private enum CandidatePath {
		/**
		 * The membership entries of the selected owners plus the residual set.
		 */
		GATHER,
		/**
		 * The whole family, because the scope has no membership.
		 */
		NO_MEMBERSHIP,
		/**
		 * The whole family, because the selection holds as many covered owners as the family has indexes.
		 */
		COVERED_NOT_SMALLER
	}

	/**
	 * Adds the indexes of the standard fixture to the live scope without registering any membership.
	 */
	private static void addStandardIndexes(@Nonnull PickFirstReducedIndexFixture fixture) {
		fixture.addIndex(Scope.LIVE, 101, 1, 1, 2);
		fixture.addIndex(Scope.LIVE, 102, 2, 3);
		fixture.addIndex(Scope.LIVE, 103, 3, 4);
		fixture.addIndex(Scope.LIVE, 104, 4, 1, 5);
		fixture.addIndex(Scope.LIVE, 105, 5, 6);
		fixture.addIndex(Scope.LIVE, 106, 6, 7, 8, 9);
	}

	/**
	 * Creates the standard fixture with its membership registered.
	 */
	@Nonnull
	private static PickFirstReducedIndexFixture createStandardFixture() {
		final PickFirstReducedIndexFixture fixture = new PickFirstReducedIndexFixture();
		addStandardIndexes(fixture);
		fixture.registerFamily(Scope.LIVE, COVERED);
		return fixture;
	}

	/**
	 * Returns the primary keys of the indexes in their order.
	 */
	@Nonnull
	private static int[] primaryKeys(@Nonnull ResolvedReducedIndexes resolved) {
		return Arrays.stream(resolved.indexes()).mapToInt(ReducedEntityIndex::getPrimaryKey).toArray();
	}

	/**
	 * Asserts the primary keys of the resolved indexes, reporting both arrays on a mismatch.
	 */
	private static void assertIndexes(@Nonnull int[] expected, @Nonnull ResolvedReducedIndexes resolved) {
		final int[] actual = primaryKeys(resolved);
		assertArrayEquals(
			expected, actual,
			() -> "expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual)
		);
	}

	/**
	 * Creates a sorter over the targets that places the listed targets first, in the listed order, and appends the
	 * other targets in ascending order.
	 */
	@Nonnull
	private static NestedContextSorter createTargetSorter(@Nonnull int... leadingTargets) {
		final QueryExecutionContext executionContext = mock(QueryExecutionContext.class);
		when(executionContext.getQueryContext()).thenReturn(mock(QueryPlanningContext.class));
		when(executionContext.getPrefetchedEntities()).thenReturn(null);
		doAnswer(invocation -> SharedBufferPool.INSTANCE.obtain()).when(executionContext).borrowBuffer();
		doNothing().when(executionContext).returnBuffer(any());
		return new NestedContextSorter(
			executionContext,
			() -> "target order",
			List.of(
				new PreSortedRecordsSorter(
					MergeMode.APPEND_FIRST,
					Comparator.naturalOrder(),
					OrderDirection.ASC,
					() -> new SortedRecordsProvider[]{new MockSortedRecordsSupplier(leadingTargets)}
				)
			)
		);
	}

	@Test
	@DisplayName("should gather the covered indexes of the selected owners plus the residual ones")
	void shouldGatherIndexesOfSelectedOwnersPlusResidual() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();

		final ResolvedReducedIndexes resolved = fixture.resolver(false, Scope.LIVE).resolve(new BaseBitmap(1, 3));

		// 101 and 102 through the owners' entries, 104 through the residual set; 105 is residual but holds no one
		assertIndexes(new int[]{101, 102, 104}, resolved);
		verify(fixture.queryContext, times(1)).getEntityIndexByPrimaryKeyIfExists(105);
		// the covered indexes of unselected owners are never looked up - the gather ran, not the family walk
		verify(fixture.queryContext, never()).getEntityIndexByPrimaryKeyIfExists(103);
		verify(fixture.queryContext, never()).getEntityIndexByPrimaryKeyIfExists(106);
	}

	@Test
	@DisplayName("should walk the whole family when the scope has no membership")
	void shouldWalkWholeFamilyWhenScopeHasNoMembership() {
		final PickFirstReducedIndexFixture fixture = new PickFirstReducedIndexFixture();
		addStandardIndexes(fixture);

		final ResolvedReducedIndexes resolved = fixture.resolver(false, Scope.LIVE).resolve(new BaseBitmap(1, 3));

		assertIndexes(new int[]{101, 102, 104}, resolved);
		for (int indexPrimaryKey = 101; indexPrimaryKey <= 106; indexPrimaryKey++) {
			verify(fixture.queryContext, times(1)).getEntityIndexByPrimaryKeyIfExists(indexPrimaryKey);
		}
	}

	@Test
	@DisplayName("should walk the whole family when the selection holds as many covered owners as there are indexes")
	void shouldWalkWholeFamilyWhenCoveredSelectionIsNotSmaller() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();

		final ResolvedReducedIndexes resolved = fixture.resolver(false, Scope.LIVE)
			.resolve(new BaseBitmap(WIDE_SELECTION));

		assertIndexes(new int[]{101, 102, 103, 104, 106}, resolved);
		for (int indexPrimaryKey = 101; indexPrimaryKey <= 106; indexPrimaryKey++) {
			verify(fixture.queryContext, times(1)).getEntityIndexByPrimaryKeyIfExists(indexPrimaryKey);
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(CandidatePath.class)
	@DisplayName("should resolve the same indexes whichever way the candidates are found")
	void shouldResolveSameIndexesWhateverPathFindsThem(CandidatePath path) {
		final PickFirstReducedIndexFixture fixture = new PickFirstReducedIndexFixture();
		addStandardIndexes(fixture);
		switch (path) {
			// only 3 of the 6 selected owners are covered, fewer than the 6 indexes of the family
			case GATHER -> fixture.registerFamily(Scope.LIVE, 101, 102);
			case NO_MEMBERSHIP -> {
				// no membership is registered at all
			}
			// all 6 selected owners are covered, as many as the family has indexes
			case COVERED_NOT_SMALLER -> fixture.registerFamily(Scope.LIVE, COVERED);
		}

		final ResolvedReducedIndexes resolved = fixture.resolver(false, Scope.LIVE)
			.resolve(new BaseBitmap(WIDE_SELECTION));

		assertIndexes(new int[]{101, 102, 103, 104, 106}, resolved);
	}

	@Test
	@DisplayName("should skip candidates that do not resolve to a reduced index of this reference in this scope")
	void shouldSkipCandidatesThatDoNotResolveToThisReference() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();
		final Serializable[] noRepresentativeValues = new Serializable[0];
		// every one of these holds owner 1, so only the checks of the resolver can keep them out
		fixture.addUnlistedIndex(
			Scope.LIVE, 201, PickFirstReducedIndexFixture.OTHER_REFERENCE_NAME, 1, noRepresentativeValues, 1
		);
		fixture.addGroupIndex(Scope.LIVE, 202, 1, 1);
		fixture.addUnlistedIndex(
			Scope.ARCHIVED, 203, PickFirstReducedIndexFixture.REFERENCE_NAME, 1, noRepresentativeValues, 1
		);
		// 200 names no index at all
		for (int stale : new int[]{200, 201, 202, 203}) {
			fixture.membership(Scope.LIVE).registerIndexAsResidual(stale);
		}

		final ResolvedReducedIndexes resolved = fixture.resolver(false, Scope.LIVE).resolve(new BaseBitmap(1));

		assertIndexes(new int[]{101, 104}, resolved);
		for (int stale : new int[]{200, 201, 202, 203}) {
			verify(fixture.queryContext, times(1)).getEntityIndexByPrimaryKeyIfExists(stale);
		}
	}

	@Test
	@DisplayName("should skip an index a stale membership entry names but that no longer holds the owner")
	void shouldSkipIndexWhoseMembershipEntryIsStale() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();
		// owner 4 leaves index 103 behind the membership's back, so its entry still names the index
		fixture.getIndex(103).removePrimaryKey(4);

		final ResolvedReducedIndexes resolved = fixture.resolver(false, Scope.LIVE).resolve(new BaseBitmap(4));

		assertIndexes(new int[0], resolved);
		// the stale entry did lead to the index - the probe against the selection is what rejected it
		verify(fixture.queryContext, times(1)).getEntityIndexByPrimaryKeyIfExists(103);
	}

	@Test
	@DisplayName("should order targets descending while keeping indexes of one target in representative value order")
	void shouldOrderTargetsDescendingButKeepDuplicatesByRepresentativeValues() {
		final PickFirstReducedIndexFixture fixture = new PickFirstReducedIndexFixture();
		// the `b` index of target 2 exists before the `a` one and has the lower primary key
		fixture.addIndex(Scope.LIVE, 301, 2, new Serializable[]{"b"}, 1);
		fixture.addIndex(Scope.LIVE, 302, 2, new Serializable[]{"a"}, 2);
		fixture.addIndex(Scope.LIVE, 303, 1, new Serializable[]{"a"}, 3);

		final ResolvedReducedIndexes resolved = fixture.resolver(true, Scope.LIVE).resolve(new BaseBitmap(1, 2, 3));

		// the targets are reversed, the representative values of one target are not
		assertIndexes(new int[]{302, 301, 303}, resolved);
	}

	@Test
	@DisplayName("should order targets by the target sorter and rank a target outside the ordered set last")
	void shouldOrderTargetsByTargetSorterAndRankUnknownTargetLast() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();
		// targets 4, 1 and 2 lead in this order, the sorter appends the remaining ones ascending
		final PickFirstReducedIndexResolver resolver = fixture.resolver(
			createTargetSorter(4, 1, 2), () -> new ReducedEntityIndex[0], Scope.LIVE
		);
		final Bitmap selection = new BaseBitmap(WIDE_SELECTION);

		final ResolvedReducedIndexes resolved = resolver.resolve(selection);
		final IntUnaryOperator rank = resolver.getTargetRank(selection);

		assertIndexes(new int[]{104, 101, 102, 103, 106}, resolved);
		assertArrayEquals(
			new int[]{0, 1, 2, 3, 4},
			Arrays.stream(new int[]{4, 1, 2, 3, 6}).map(rank).toArray()
		);
		// target 5 is referenced only by owner 6, which is not selected
		assertEquals(Integer.MAX_VALUE, rank.applyAsInt(5));
	}

	@Test
	@DisplayName("should rank the plain primary key order without touching any index")
	void shouldRankPrimaryKeyOrderWithoutTouchingIndexes() {
		final QueryPlanningContext queryContext = mock(QueryPlanningContext.class);
		final PickFirstReducedIndexFixture fixture = new PickFirstReducedIndexFixture();
		final Bitmap selection = new BaseBitmap(1, 2, 3);

		final IntUnaryOperator ascending = new PickFirstReducedIndexResolver(
			queryContext, fixture.referenceSchema, new Scope[]{Scope.LIVE}, null, false, () -> new ReducedEntityIndex[0]
		).getTargetRank(selection);
		final IntUnaryOperator descending = new PickFirstReducedIndexResolver(
			queryContext, fixture.referenceSchema, new Scope[]{Scope.LIVE}, null, true, () -> new ReducedEntityIndex[0]
		).getTargetRank(selection);

		assertEquals(5, ascending.applyAsInt(5));
		assertEquals(-5, descending.applyAsInt(5));
		verifyNoInteractions(queryContext);
	}

	@Test
	@DisplayName("should memoize the resolution by the identity of the selection")
	void shouldMemoizeBySelectionIdentity() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();
		final PickFirstReducedIndexResolver resolver = fixture.resolver(false, Scope.LIVE);
		final Bitmap selection = new BaseBitmap(1, 3);

		final ResolvedReducedIndexes first = resolver.resolve(selection);
		final ResolvedReducedIndexes second = resolver.resolve(selection);

		assertSame(first, second);
		verify(fixture.queryContext, times(1)).getEntityIndexByPrimaryKeyIfExists(101);

		// an equal but distinct selection is resolved again
		final ResolvedReducedIndexes third = resolver.resolve(new BaseBitmap(1, 3));

		assertArrayEquals(primaryKeys(first), primaryKeys(third));
		verify(fixture.queryContext, times(2)).getEntityIndexByPrimaryKeyIfExists(101);
	}

	@Test
	@DisplayName("should resolve nothing for an empty selection without looking at any index")
	void shouldResolveNothingForEmptySelection() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();

		final ResolvedReducedIndexes resolved = fixture.resolver(false, Scope.LIVE).resolve(EmptyBitmap.INSTANCE);

		assertIndexes(new int[0], resolved);
		verifyNoInteractions(fixture.queryContext);
	}

	@Test
	@DisplayName("should collect the indexes of every processed scope and skip a scope without a type index")
	void shouldCollectIndexesOfAllScopes() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();
		final Bitmap selection = new BaseBitmap(1, 20);

		// the archived scope has no reduced index of the reference yet
		assertIndexes(
			new int[]{101, 104},
			fixture.resolver(false, Scope.LIVE, Scope.ARCHIVED).resolve(selection)
		);

		fixture.addIndex(Scope.ARCHIVED, 401, 7, 20);
		fixture.registerFamily(Scope.ARCHIVED, 401);

		assertIndexes(
			new int[]{101, 104, 401},
			fixture.resolver(false, Scope.LIVE, Scope.ARCHIVED).resolve(selection)
		);
		// a scope the query does not process is not looked into
		assertIndexes(
			new int[]{401},
			fixture.resolver(false, Scope.ARCHIVED).resolve(selection)
		);
	}

	@Test
	@DisplayName("should resolve the planning-time indexes once and only when asked for")
	void shouldResolvePlanningIndexesOnceAndOnlyWhenAsked() {
		final PickFirstReducedIndexFixture fixture = createStandardFixture();
		final AtomicInteger supplied = new AtomicInteger();
		final ReducedEntityIndex[] planningIndexes = {fixture.getIndex(101)};
		final PickFirstReducedIndexResolver resolver = fixture.resolver(
			null,
			() -> {
				supplied.incrementAndGet();
				return planningIndexes;
			},
			Scope.LIVE
		);

		resolver.resolve(new BaseBitmap(1, 3));
		assertEquals(0, supplied.get());

		assertSame(planningIndexes, resolver.getPlanningIndexes());
		assertSame(planningIndexes, resolver.getPlanningIndexes());
		assertEquals(1, supplied.get());
	}

}
