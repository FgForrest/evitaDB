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
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.sorter.MergedSortedRecordsSupplierSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.ReferenceSortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how the index route of a traversal ordering sorts one block whose referenced entity has reduced indexes in
 * both scopes: {@link SequentialSorter} drives a real {@link PreSortedRecordsSorter} in
 * {@link MergeMode#APPEND_ALL} over the providers of the whole family, and the providers of one block are merged by
 * value - the owners of both scopes follow their values, then their primary keys in the direction of the ordering -
 * unless the sorter has no value comparator (a chain), in which case they are appended one after another.
 *
 * Fixture - rows `(owner, value)`:
 *
 * - target 1, live index: (1, 30) (3, 10); archived index: (2, 20) (4, 30)
 * - target 2, live index: (5, 5) (6, 40)
 *
 * The providers lie in the order the traversal lists the indexes: live and archived index of target 1, then target 2.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sequential sorter merging the scopes of one block")
@Tag(ENGINE)
@Tag(ORDER)
class SequentialSorterBlockMergeTest {
	/**
	 * The fixture providing the execution context of the query.
	 */
	private PickFirstReducedIndexFixture fixture;
	/**
	 * Live reduced index of target 1.
	 */
	private ReducedEntityIndex liveTarget1;
	/**
	 * Archived reduced index of target 1.
	 */
	private ReducedEntityIndex archivedTarget1;
	/**
	 * Live reduced index of target 2.
	 */
	private ReducedEntityIndex liveTarget2;
	/**
	 * How many times any provider's value seeker was asked for a value - the work a merge by value pays per record.
	 */
	private int valueReads;

	@BeforeEach
	void setUp() {
		this.fixture = new PickFirstReducedIndexFixture();
		this.liveTarget1 = createIndex(1, 1, Scope.LIVE, 1, 3);
		this.archivedTarget1 = createIndex(2, 1, Scope.ARCHIVED, 2, 4);
		this.liveTarget2 = createIndex(3, 2, Scope.LIVE, 5, 6);
		this.valueReads = 0;
	}

	@Test
	@DisplayName("should merge the owners of both scopes of one block by value, then primary key, ascending")
	void shouldMergeOwnersOfBothScopesAscending() {
		// block 1: 3(10) 2(20) 1(30) 4(30) - the tie of 1 and 4 goes to the lower primary key; block 2: 5(5) 6(40)
		// appending the scopes would give 3 1 | 2 4
		assertArrayEquals(
			new int[]{3, 2, 1, 4, 5, 6},
			sort(createAscendingProviders(), Comparator.naturalOrder(), OrderDirection.ASC, 0, 10, 1, 2, 3, 4, 5, 6)
		);
	}

	@Test
	@DisplayName("should merge the owners of both scopes of one block by value, then primary key, descending")
	void shouldMergeOwnersOfBothScopesDescending() {
		// block 1: 4(30) 1(30) 2(20) 3(10) - the tie of 1 and 4 goes to the higher primary key; block 2: 6(40) 5(5)
		assertArrayEquals(
			new int[]{4, 1, 2, 3, 6, 5},
			sort(
				createDescendingProviders(), Comparator.reverseOrder(), OrderDirection.DESC, 0, 10, 1, 2, 3, 4, 5, 6
			)
		);
	}

	@Test
	@DisplayName("should append the providers of one block one after another when there is no value comparator")
	void shouldAppendProvidersOfBlockWithoutComparator() {
		// a chain: the live provider of target 1 first, then the archived one - positions of two providers are not
		// comparable
		assertArrayEquals(
			new int[]{3, 1, 2, 4, 5, 6},
			sort(createAscendingProviders(), null, OrderDirection.ASC, 0, 10, 1, 2, 3, 4, 5, 6)
		);
	}

	@Test
	@DisplayName("should slice a page out of the merged block and report the skipped owners")
	void shouldSlicePageOutOfMergedBlock() {
		final List<Integer> skipped = new ArrayList<>(2);
		final int[] result = new int[3];
		final SortingContext output = createSequentialSorter(
			createAscendingProviders(), Comparator.naturalOrder(), OrderDirection.ASC
		).sortAndSlice(
			new SortingContext(
				this.fixture.createExecutionContext(), new BaseBitmap(1, 2, 3, 4, 5, 6), 1, 4, 0, 0
			),
			result,
			skipped::add
		);

		assertAll(
			() -> assertArrayEquals(new int[]{2, 1, 4}, Arrays.copyOf(result, output.peak())),
			() -> assertArrayEquals(new int[]{3}, skipped.stream().mapToInt(Integer::intValue).toArray())
		);
	}

	@Test
	@DisplayName("should emit the owners of a block without reading any value when one scope holds all of them")
	void shouldEmitWithoutReadingValuesWhenOneProviderContributes() {
		// the selection holds live owners of target 1 only: the archived provider of the block contributes nothing,
		// so the live provider's mask is already the answer - a merge would read a value per owner for nothing
		final int[] sorted = sort(
			createAscendingProviders(), Comparator.naturalOrder(), OrderDirection.ASC, 0, 10, 1, 3
		);

		assertAll(
			() -> assertArrayEquals(new int[]{3, 1}, sorted),
			() -> assertEquals(0, this.valueReads, "no value may be read when a single provider holds the selection")
		);
	}

	@Test
	@DisplayName("should read the values when both scopes of a block hold selected owners")
	void shouldReadValuesWhenBothProvidersContribute() {
		// the positive control of the test above: owners of both scopes have to be merged by their values
		final int[] sorted = sort(
			createAscendingProviders(), Comparator.naturalOrder(), OrderDirection.ASC, 0, 10, 1, 2
		);

		assertAll(
			() -> assertArrayEquals(new int[]{2, 1}, sorted),
			() -> assertTrue(this.valueReads > 0, "the merge of two contributing providers reads their values")
		);
	}

	@Test
	@DisplayName("should never let a provider of another block claim an owner of the selection")
	void shouldNeverLetProviderOfAnotherBlockClaimOwner() {
		// owner 5 sits in the provider of target 2 with the lowest value of all; sorting the block of target 1 must
		// leave it to a later block - a merge over the providers of the whole family would place it first
		final MergedSortedRecordsSupplierSorter sorter = new MergedSortedRecordsSupplierSorter(
			createAscendingProviders(), Comparator.naturalOrder(), OrderDirection.ASC
		);
		final int[] result = new int[3];
		final SortingContext output = sorter.sortAndSlice(
			new SortingContext(
				this.fixture.createExecutionContext(), new BaseBitmap(1, 2, 5), 0, 3, 0, 0,
				this.liveTarget1.getRepresentativeReferenceKey()
			),
			result,
			null
		);

		assertAll(
			() -> assertArrayEquals(new int[]{2, 1}, Arrays.copyOf(result, output.peak())),
			() -> assertArrayEquals(new int[]{5}, output.nonSortedKeys().getArray())
		);
	}

	/**
	 * Runs the sequential sorter over the two blocks of the fixture.
	 *
	 * @param providers       the providers of the family in traversal order
	 * @param comparator      the value comparator in the direction of the ordering, `null` for a chain
	 * @param primaryKeyOrder the direction of the ordering
	 * @param startIndex      the first requested position
	 * @param endIndex        the position after the last requested one
	 * @param selection       the owners to sort
	 * @return the owners written, in order
	 */
	@Nonnull
	private int[] sort(
		@Nonnull SortedRecordsProvider[] providers,
		@Nullable Comparator<?> comparator,
		@Nonnull OrderDirection primaryKeyOrder,
		int startIndex,
		int endIndex,
		@Nonnull int... selection
	) {
		final int[] result = new int[endIndex - startIndex];
		final SortingContext output = createSequentialSorter(providers, comparator, primaryKeyOrder).sortAndSlice(
			new SortingContext(
				this.fixture.createExecutionContext(), new BaseBitmap(selection), startIndex, endIndex, 0, 0
			),
			result,
			null
		);
		return Arrays.copyOf(result, output.peak());
	}

	/**
	 * Creates the sequential sorter of the two blocks, sorting each by a pre-sorted records sorter over the providers.
	 *
	 * @param providers       the providers of the family in traversal order
	 * @param comparator      the value comparator in the direction of the ordering, `null` for a chain
	 * @param primaryKeyOrder the direction of the ordering
	 * @return the sorter
	 */
	@Nonnull
	private SequentialSorter createSequentialSorter(
		@Nonnull SortedRecordsProvider[] providers,
		@Nullable Comparator<?> comparator,
		@Nonnull OrderDirection primaryKeyOrder
	) {
		return new SequentialSorter(
			new ReducedEntityIndex[][]{{this.liveTarget1, this.archivedTarget1}, {this.liveTarget2}},
			new ArrayList<>(
				List.of(new PreSortedRecordsSorter(MergeMode.APPEND_ALL, comparator, primaryKeyOrder, () -> providers))
			)
		);
	}

	/**
	 * Creates the providers of an ascending ordering - values ascending, ties by ascending primary key.
	 *
	 * @return the providers in traversal order
	 */
	@Nonnull
	private SortedRecordsProvider[] createAscendingProviders() {
		return new SortedRecordsProvider[]{
			createProvider(this.liveTarget1, new int[]{3, 1}, 10, 30),
			createProvider(this.archivedTarget1, new int[]{2, 4}, 20, 30),
			createProvider(this.liveTarget2, new int[]{5, 6}, 5, 40)
		};
	}

	/**
	 * Creates the providers of a descending ordering - values descending, ties by descending primary key.
	 *
	 * @return the providers in traversal order
	 */
	@Nonnull
	private SortedRecordsProvider[] createDescendingProviders() {
		return new SortedRecordsProvider[]{
			createProvider(this.liveTarget1, new int[]{1, 3}, 30, 10),
			createProvider(this.archivedTarget1, new int[]{4, 2}, 30, 20),
			createProvider(this.liveTarget2, new int[]{6, 5}, 40, 5)
		};
	}

	/**
	 * Creates a real reduced index of the `items` reference holding the passed owners.
	 *
	 * @param primaryKey primary key of the index
	 * @param target     primary key of the referenced entity
	 * @param scope      scope of the owners the index holds
	 * @param owners     the owners
	 * @return the index
	 */
	@Nonnull
	private static ReducedEntityIndex createIndex(
		int primaryKey,
		int target,
		@Nonnull Scope scope,
		@Nonnull int... owners
	) {
		final ReducedEntityIndex index = new ReducedEntityIndex(
			primaryKey, PickFirstReducedIndexFixture.OWNER_TYPE,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, scope,
				new RepresentativeReferenceKey(new ReferenceKey(PickFirstReducedIndexFixture.REFERENCE_NAME, target))
			)
		);
		for (int owner : owners) {
			index.insertPrimaryKeyIfMissing(owner);
		}
		return index;
	}

	/**
	 * Creates the array-backed provider of one reduced index, whose value seeker counts its reads in
	 * {@link #valueReads}.
	 *
	 * @param index        the reduced index
	 * @param sortedOwners the owners of the index in the sort order
	 * @param values       the value of each owner, aligned with `sortedOwners`
	 * @return the provider
	 */
	@Nonnull
	private ReferenceSortedRecordsProvider createProvider(
		@Nonnull ReducedEntityIndex index,
		@Nonnull int[] sortedOwners,
		@Nonnull int... values
	) {
		final int[] ownersAscending = index.getAllPrimaryKeys().getArray();
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
		return new ReferenceSortedRecordsProvider(
			index.getPrimaryKey(), sortedOwners, positions, index.getAllPrimaryKeys(),
			position -> {
				this.valueReads++;
				return boxedValues[position];
			},
			index.getRepresentativeReferenceKey()
		);
	}

}
