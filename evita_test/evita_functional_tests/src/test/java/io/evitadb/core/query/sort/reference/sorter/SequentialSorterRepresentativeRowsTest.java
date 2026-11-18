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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.ForcedSortResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.core.query.sort.attribute.sorter.MergedSortedRecordsSupplierSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies how the index route of a traversal ordering sorts a block whose referenced entity is referenced by several
 * rows of one owner - a reference allowing duplicates, the rows told apart by a representative attribute. The block
 * is the whole referenced entity: its providers - one per representative key - claim the block's owners in the order
 * of the keys ({@link RepresentativeReferenceKey#GENERIC_COMPARATOR}), each the owners it holds a value for that no
 * earlier provider claimed, so an owner is represented by its first valued row; the claimed runs are then merged by
 * value and owner primary key. A {@link SequentialSorter} drives a real {@link PreSortedRecordsSorter} in
 * {@link MergeMode#APPEND_ALL} over the providers of the whole family.
 *
 * The providers are handed over in the order the planning lines them up; that the planning puts them in key order
 * is pinned by `ReferencePropertyTranslatorIndexGroupingTest`.
 *
 * Besides the order, the tests pin the work the route does: which providers are asked to resolve positions (one
 * claim each, in key order, stopping once the block's owners are all claimed) and how many values the merge reads
 * (none unless two providers claimed owners, then one per emitted owner, bounded by the requested page).
 *
 * Fixture - rows `(owner, value)` of each representative key, `-` for a row without the value:
 *
 * - target 1, key `m`: (74, 60)
 * - target 2, key `a`: (71, 100) (72, -) (78, -)
 * - target 2, key `b`: (72, 50) (73, 60) and, for the laziness test only, (75, 51) (76, 52) (77, 53)
 * - target 2, key `c`: (73, 5)
 * - target 2, key `z`: (70, 1)
 * - target 3, key `q`: (79, -) - no provider at all
 *
 * Each owner of target 2 is represented by its first valued row in key order: 70 by `z` (1), 71 by `a` (100), 72 by `b`
 * (50) - its `a` row has no value - and 73 by `b` (60) although its `c` row carries 5. Owner 78 has no valued row of
 * target 2, and target 3 has no provider to claim 79.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sequential sorter representing each owner by its first valued row of a target")
@Tag(ENGINE)
@Tag(ORDER)
class SequentialSorterRepresentativeRowsTest {
	/**
	 * The fixture providing the execution context of the query.
	 */
	private PickFirstReducedIndexFixture fixture;
	/**
	 * Index of target 1, key `m`.
	 */
	private ReducedEntityIndex target1M;
	/**
	 * Index of target 2, key `a`.
	 */
	private ReducedEntityIndex target2A;
	/**
	 * Index of target 2, key `b`.
	 */
	private ReducedEntityIndex target2B;
	/**
	 * Index of target 2, key `c`.
	 */
	private ReducedEntityIndex target2C;
	/**
	 * Index of target 2, key `z`.
	 */
	private ReducedEntityIndex target2Z;
	/**
	 * Index of target 3, key `q`, which no provider backs.
	 */
	private ReducedEntityIndex target3Q;
	/**
	 * The providers asked to resolve positions, in the order they were asked - `target/key` each.
	 */
	private List<String> claims;
	/**
	 * How many values the providers' value seekers were asked for - the work of the merge by value.
	 */
	private int valueReads;
	/**
	 * How many times the supplier built the providers of the family.
	 */
	private int providerBuilds;

	@BeforeEach
	void setUp() {
		this.fixture = new PickFirstReducedIndexFixture();
		this.target1M = createIndex(1, 1, "m", 74);
		this.target2A = createIndex(2, 2, "a", 71, 72, 78);
		this.target2B = createIndex(3, 2, "b", 72, 73, 75, 76, 77);
		this.target2C = createIndex(4, 2, "c", 73);
		this.target2Z = createIndex(5, 2, "z", 70);
		this.target3Q = createIndex(6, 3, "q", 79);
		this.claims = new ArrayList<>(8);
		this.valueReads = 0;
		this.providerBuilds = 0;
	}

	@Test
	@DisplayName("should represent each owner by its first valued row in key order and merge the owners by value, ascending")
	void shouldMergeFirstValuedRowsAscending() {
		// 74 | 70 (z 1) 72 (b 50) 73 (b 60) 71 (a 100); rejected: best value over the rows puts 73 (c 5) second,
		// one sub-block per key gives 71 72 73 70, the first row whether valued or not hands 72 to its value-less a row
		final SortOutcome outcome = sort(ascending(), 0, 10, 70, 71, 72, 73, 74);

		assertAll(
			() -> assertArrayEquals(new int[]{74, 70, 72, 73, 71}, outcome.written()),
			() -> assertArrayEquals(new int[0], outcome.remaining(), "every owner finds its block")
		);
	}

	@Test
	@DisplayName("should represent each owner by its first valued row in key order and merge the owners by value, descending")
	void shouldMergeFirstValuedRowsDescending() {
		// the keys are walked in the same order whatever the direction - only the merge follows it
		final SortOutcome outcome = sort(descending(), 0, 10, 70, 71, 72, 73, 74);

		assertAll(
			() -> assertArrayEquals(new int[]{74, 71, 73, 72, 70}, outcome.written()),
			() -> assertArrayEquals(new int[0], outcome.remaining(), "every owner finds its block")
		);
	}

	@Test
	@DisplayName("should cost a block of one key one claim and no value read")
	void shouldClaimOnceForTargetWithSingleKey() {
		final SortOutcome outcome = sort(ascending(), 0, 10, 74);

		assertAll(
			() -> assertArrayEquals(new int[]{74}, outcome.written()),
			() -> assertEquals(List.of("1/m"), this.claims, "only the provider of target 1 is asked"),
			() -> assertEquals(0, this.valueReads, "a single provider is walked without reading values"),
			() -> assertEquals(1, this.providerBuilds)
		);
	}

	@Test
	@DisplayName("should stop claiming once a few selected owners are claimed, without reading any value")
	void shouldStopClaimingOnceFewOwnersAreClaimed() {
		// owner 73 is claimed by key b: the providers of keys c and z are never asked
		final SortOutcome outcome = sort(ascending(), 0, 10, 73);

		assertAll(
			() -> assertArrayEquals(new int[]{73}, outcome.written()),
			() -> assertEquals(List.of("2/a", "2/b"), this.claims, "claims stop at the provider claiming the last owner"),
			() -> assertEquals(0, this.valueReads, "one contributing provider needs no merge")
		);
	}

	@Test
	@DisplayName("should ask every key of the target for an owner only the last key holds, still without reading values")
	void shouldWalkAllKeysForOwnerOfLastKey() {
		final SortOutcome outcome = sort(ascending(), 0, 10, 70);

		assertAll(
			() -> assertArrayEquals(new int[]{70}, outcome.written()),
			() -> assertEquals(List.of("2/a", "2/b", "2/c", "2/z"), this.claims),
			() -> assertEquals(0, this.valueReads)
		);
	}

	@Test
	@DisplayName("should claim once per key and read one value per merged owner for many owners")
	void shouldReadOneValuePerMergedOwner() {
		// 70 (z 1) 72 (b 50) 75 (b 51) 76 (b 52) 77 (b 53) 73 (b 60) 71 (a 100): three runs (a, b, z) merged
		final SortOutcome outcome = sort(ascending(), 0, 10, 70, 71, 72, 73, 75, 76, 77);

		assertAll(
			() -> assertArrayEquals(new int[]{70, 72, 75, 76, 77, 73, 71}, outcome.written()),
			() -> assertEquals(List.of("2/a", "2/b", "2/c", "2/z"), this.claims),
			() -> assertEquals(7, this.valueReads, "one value per owner the merge emits")
		);
	}

	@Test
	@DisplayName("should read values lazily, only as far as the requested page reaches")
	void shouldReadValuesOnlyUpToRequestedPage() {
		// page [0, 2): the merge reads the heads of its three runs (71, 72, 70), emits 70 and 72, and reads the next
		// value of run b (75) when it advances past 72 - four reads where the whole block takes seven
		final SortOutcome outcome = sort(ascending(), 0, 2, 70, 71, 72, 73, 75, 76, 77);

		assertAll(
			() -> assertArrayEquals(new int[]{70, 72}, outcome.written()),
			() -> assertEquals(4, this.valueReads)
		);
	}

	@Test
	@DisplayName("should pass on an owner no provider of its target holds and the owners of a target without providers")
	void shouldPassOnOwnerNoProviderOfTargetHolds() {
		// 78 sits in the index of key a without a value, 79 in the index of target 3, which has no provider at all
		final SortOutcome outcome = sort(ascending(), 0, 10, 70, 78, 79);

		assertAll(
			() -> assertArrayEquals(new int[]{70}, outcome.written()),
			() -> assertArrayEquals(new int[]{78, 79}, outcome.remaining(), "no block claims them"),
			() -> assertEquals(
				List.of("2/a", "2/b", "2/c", "2/z"), this.claims, "a target without providers asks no provider"
			),
			() -> assertEquals(0, this.valueReads)
		);
	}

	@Test
	@DisplayName("should build the providers of the family once and slice a later page across the blocks")
	void shouldBuildProvidersOnceAndSliceLaterPage() {
		// the full order is 74 | 70 72 73 71: page [2, 4) skips 74 (block 1) and 70 (block 2) and writes 72 73
		final SequentialSorter sorter = createSequentialSorter(Comparator.naturalOrder(), OrderDirection.ASC, false);
		sort(sorter, 0, 10, 70, 71, 72, 73, 74);
		final SortOutcome secondPage = sort(sorter, 2, 4, 70, 71, 72, 73, 74);

		assertAll(
			() -> assertArrayEquals(new int[]{72, 73}, secondPage.written(), "the page"),
			() -> assertArrayEquals(new int[]{74, 70}, secondPage.skipped(), "the owners before the page"),
			() -> assertEquals(1, this.providerBuilds, "the providers are built once")
		);
	}

	@Test
	@DisplayName("should append the runs of a chain in key order when there is no value comparator")
	void shouldAppendChainRunsInKeyOrder() {
		// no comparator: the owners follow the provider that claimed them - a: 71 | b: 72 73 | z: 70
		assertArrayEquals(
			new int[]{74, 71, 72, 73, 70},
			sort(createSequentialSorter(null, OrderDirection.ASC, false), 0, 10, 70, 71, 72, 73, 74).written()
		);
	}

	@Test
	@DisplayName("should refuse providers of one target that do not lie next to each other")
	void shouldRefuseNonContiguousProvidersOfTarget() {
		// key z of target 2 after target 1 again: its providers would be lost from the block of target 2
		final MergedSortedRecordsSupplierSorter sorter = new MergedSortedRecordsSupplierSorter(
			new SortedRecordsProvider[]{
				createProvider(this.target2A, new int[]{71}, 100),
				createProvider(this.target1M, new int[]{74}, 60),
				createProvider(this.target2Z, new int[]{70}, 1)
			},
			Comparator.naturalOrder(),
			OrderDirection.ASC
		);

		assertThrows(
			EvitaInternalError.class,
			() -> sorter.sortAndSlice(
				new SortingContext(
					this.fixture.createExecutionContext(), new BaseBitmap(70, 71), 0, 2, 0, 0,
					new RepresentativeReferenceKey(new ReferenceKey(PickFirstReducedIndexFixture.REFERENCE_NAME, 2))
				),
				new int[2],
				null
			)
		);
	}

	/**
	 * Creates the sorter of the ascending ordering.
	 */
	@Nonnull
	private SequentialSorter ascending() {
		return createSequentialSorter(Comparator.naturalOrder(), OrderDirection.ASC, false);
	}

	/**
	 * Creates the sorter of the descending ordering.
	 */
	@Nonnull
	private SequentialSorter descending() {
		return createSequentialSorter(Comparator.reverseOrder(), OrderDirection.DESC, true);
	}

	/**
	 * Runs the passed sequential sorter and returns what it wrote, skipped and left unsorted.
	 *
	 * @param sorter     the sorter to run
	 * @param startIndex the first requested position
	 * @param endIndex   the position after the last requested one
	 * @param selection  the owners to sort
	 * @return the outcome of the sort
	 */
	@Nonnull
	private SortOutcome sort(
		@Nonnull SequentialSorter sorter,
		int startIndex,
		int endIndex,
		@Nonnull int... selection
	) {
		final int[] result = new int[endIndex - startIndex];
		final List<Integer> skipped = new ArrayList<>(4);
		final SortingContext output = sorter.sortAndSlice(
			new SortingContext(
				this.fixture.createExecutionContext(), new BaseBitmap(selection), startIndex, endIndex, 0, 0
			),
			result,
			skipped::add
		);
		return new SortOutcome(
			Arrays.copyOf(result, output.peak()),
			skipped.stream().mapToInt(Integer::intValue).toArray(),
			output.nonSortedKeys().getArray()
		);
	}

	/**
	 * Creates the sequential sorter of the three blocks - target 1, then target 2 with its keys in
	 * {@link RepresentativeReferenceKey#GENERIC_COMPARATOR} order, then target 3 - sorting each by a pre-sorted records
	 * sorter over the providers of the family, which the supplier builds on the first sort and counts.
	 *
	 * @param comparator      the value comparator in the direction of the ordering, `null` for a chain
	 * @param primaryKeyOrder the direction of the ordering
	 * @param descending      whether the providers list their owners in the descending order
	 * @return the sorter
	 */
	@Nonnull
	private SequentialSorter createSequentialSorter(
		@Nullable Comparator<?> comparator,
		@Nonnull OrderDirection primaryKeyOrder,
		boolean descending
	) {
		return new SequentialSorter(
			new ReducedEntityIndex[][]{
				{this.target1M},
				{this.target2A, this.target2B, this.target2C, this.target2Z},
				{this.target3Q}
			},
			new ArrayList<>(
				List.of(
					new PreSortedRecordsSorter(
						MergeMode.APPEND_ALL, comparator, primaryKeyOrder,
						() -> {
							this.providerBuilds++;
							return descending ? createDescendingProviders() : createAscendingProviders();
						}
					)
				)
			)
		);
	}

	/**
	 * Creates the providers of an ascending ordering - values ascending, ties by ascending primary key; owners without
	 * the value are left out.
	 *
	 * @return the providers in traversal order, the keys of one target in key order
	 */
	@Nonnull
	private SortedRecordsProvider[] createAscendingProviders() {
		return new SortedRecordsProvider[]{
			createProvider(this.target1M, new int[]{74}, 60),
			createProvider(this.target2A, new int[]{71}, 100),
			createProvider(this.target2B, new int[]{72, 75, 76, 77, 73}, 50, 51, 52, 53, 60),
			createProvider(this.target2C, new int[]{73}, 5),
			createProvider(this.target2Z, new int[]{70}, 1)
		};
	}

	/**
	 * Creates the providers of a descending ordering - values descending, ties by descending primary key.
	 *
	 * @return the providers in traversal order, the keys of one target in key order - the same as for the ascending
	 * ordering
	 */
	@Nonnull
	private SortedRecordsProvider[] createDescendingProviders() {
		return new SortedRecordsProvider[]{
			createProvider(this.target1M, new int[]{74}, 60),
			createProvider(this.target2A, new int[]{71}, 100),
			createProvider(this.target2B, new int[]{73, 77, 76, 75, 72}, 60, 53, 52, 51, 50),
			createProvider(this.target2C, new int[]{73}, 5),
			createProvider(this.target2Z, new int[]{70}, 1)
		};
	}

	/**
	 * Creates a real live reduced index of the `items` reference with one representative attribute value.
	 *
	 * @param primaryKey primary key of the index
	 * @param target     primary key of the referenced entity
	 * @param key        the representative attribute value of the rows the index holds
	 * @param owners     the owners
	 * @return the index
	 */
	@Nonnull
	private static ReducedEntityIndex createIndex(
		int primaryKey,
		int target,
		@Nonnull String key,
		@Nonnull int... owners
	) {
		final ReducedEntityIndex index = new ReducedEntityIndex(
			primaryKey, PickFirstReducedIndexFixture.OWNER_TYPE,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(
					new ReferenceKey(PickFirstReducedIndexFixture.REFERENCE_NAME, target), new Serializable[]{key}
				)
			)
		);
		for (int owner : owners) {
			index.insertPrimaryKeyIfMissing(owner);
		}
		return index;
	}

	/**
	 * Creates the array-backed provider of one reduced index holding the passed owners. The provider records every
	 * position resolution in {@link #claims} and its value seeker counts its reads in {@link #valueReads}.
	 *
	 * @param index        the reduced index
	 * @param sortedOwners the owners holding the value, in the sort order
	 * @param values       the value of each owner, aligned with `sortedOwners`
	 * @return the provider
	 */
	@Nonnull
	private ReferenceSortedRecordsProvider createProvider(
		@Nonnull ReducedEntityIndex index,
		@Nonnull int[] sortedOwners,
		@Nonnull int... values
	) {
		final int[] ownersAscending = Arrays.stream(sortedOwners).sorted().toArray();
		// the position of each owner, in ascending owner order, within the sorted array
		final int[] positions = new int[ownersAscending.length];
		for (int i = 0; i < ownersAscending.length; i++) {
			for (int j = 0; j < sortedOwners.length; j++) {
				if (sortedOwners[j] == ownersAscending[i]) {
					positions[i] = j;
				}
			}
		}
		final Serializable[] boxedValues = Arrays.stream(values).boxed().toArray(Serializable[]::new);
		final RepresentativeReferenceKey key = index.getRepresentativeReferenceKey();
		final String label = key.primaryKey() + "/" + key.representativeAttributeValues()[0];
		return new CountingProvider(
			index.getPrimaryKey(), sortedOwners, positions, new BaseBitmap(ownersAscending),
			position -> {
				this.valueReads++;
				return boxedValues[position];
			},
			key, label
		);
	}

	/**
	 * A real array-backed reference provider that records each position resolution - one claim of the walk over the
	 * providers of a block - in {@link #claims}.
	 */
	private final class CountingProvider extends ReferenceSortedRecordsProvider {
		/**
		 * The label recorded for each claim: `target/key`.
		 */
		private final String label;

		CountingProvider(
			long transactionalId,
			@Nonnull int[] sortedRecordIds,
			@Nonnull int[] recordPositions,
			@Nonnull BaseBitmap allRecords,
			@Nonnull SortedComparableForwardSeeker seeker,
			@Nonnull RepresentativeReferenceKey referenceKey,
			@Nonnull String label
		) {
			super(transactionalId, sortedRecordIds, recordPositions, allRecords, seeker, referenceKey);
			this.label = label;
		}

		@Nonnull
		@Override
		public PositionResolution resolvePositions(
			@Nonnull PersistentRoaringBitmap selectedRecordIds,
			int selectedRecordCount,
			@Nonnull int[] bufferA,
			@Nonnull int[] bufferB,
			@Nullable ForcedSortResolution forcedResolution
		) {
			SequentialSorterRepresentativeRowsTest.this.claims.add(this.label);
			return super.resolvePositions(selectedRecordIds, selectedRecordCount, bufferA, bufferB, forcedResolution);
		}
	}

	/**
	 * What one run of the sorter produced.
	 *
	 * @param written   the owners the sorter wrote, in order
	 * @param skipped   the owners it skipped before the requested page, in order
	 * @param remaining the owners no block claimed
	 */
	private record SortOutcome(@Nonnull int[] written, @Nonnull int[] skipped, @Nonnull int[] remaining) {
	}

}
