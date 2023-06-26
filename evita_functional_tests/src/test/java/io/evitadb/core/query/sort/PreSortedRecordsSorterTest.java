/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.translator.AttributeComparator;
import io.evitadb.core.query.sort.attribute.translator.EntityAttributeExtractor;
import io.evitadb.core.query.sort.utils.MockSortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link PreSortedRecordsSorter} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PreSortedRecordsSorterTest {
	private static final String ENTITY_TYPE = "product";
	private static final String ATTRIBUTE_NAME_FIRST = "first";
	private static final String ATTRIBUTE_NAME_SECOND = "second";

	@SuppressWarnings({"unchecked", "rawtypes"})
	private final EntityComparator TEST_COMPARATOR_FIRST = new AttributeComparator(
		ATTRIBUTE_NAME_FIRST, null, EntityAttributeExtractor.INSTANCE, (o1, o2) -> ((Comparable) o1).compareTo(o2)
	);
	@SuppressWarnings({"unchecked", "rawtypes"})
	private final EntityComparator TEST_COMPARATOR_SECOND = new AttributeComparator(
		ATTRIBUTE_NAME_SECOND, null, EntityAttributeExtractor.INSTANCE, (o1, o2) -> ((Comparable) o1).compareTo(o2)
	);
	private PreSortedRecordsSorterWithContext bitmapSorter;
	private PreSortedRecordsSorterWithContext entitySorter;

	@BeforeEach
	void setUp() {
		final QueryContext bitmapQueryContext = Mockito.mock(QueryContext.class);
		Mockito.when(bitmapQueryContext.getPrefetchedEntities()).thenReturn(null);
		bitmapSorter = new PreSortedRecordsSorterWithContext(
			new PreSortedRecordsSorter(
				ENTITY_TYPE,
				() -> new SortedRecordsProvider[] {new MockSortedRecordsSupplier(7, 2, 4, 1, 3, 8, 5, 9, 6)},
				NullThrowingEntityComparator.INSTANCE
			),
			bitmapQueryContext
		);

		final List<EntityDecorator> mockEntities = createMockEntities(7, 2, 4, 1, 3, 8, 5, 9, 6);
		final Map<Integer, SealedEntity> mockEntitiesIndex = mockEntities.stream().collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));
		final QueryContext entityQueryContext = Mockito.mock(QueryContext.class);
		Mockito.when(entityQueryContext.getPrefetchedEntities()).thenReturn(mockEntities);
		Mockito.when(entityQueryContext.translateEntity(Mockito.any()))
			.thenAnswer(invocation -> ((EntityContract) invocation.getArgument(0)).getPrimaryKey());
		Mockito.when(entityQueryContext.translateToEntity(Mockito.anyInt()))
			.thenAnswer(invocation -> mockEntitiesIndex.get(((Integer) invocation.getArgument(0))));
		entitySorter = new PreSortedRecordsSorterWithContext(
			new PreSortedRecordsSorter(
				ENTITY_TYPE,
				() -> new SortedRecordsProvider[] {SortedRecordsProvider.EMPTY},
				TEST_COMPARATOR_FIRST
			),
			entityQueryContext
		);
	}

	@Test
	void shouldReturnFullResultInExpectedOrderOnSmallData() {
		Stream.of(bitmapSorter, entitySorter).forEach(tested -> {
			assertArrayEquals(
				new int[]{2, 4, 1, 3},
				tested.sorter().sortAndSlice(tested.context(), makeFormula(1, 2, 3, 4), 0, 100)
			);
			assertArrayEquals(
				new int[]{1, 3},
				tested.sorter().sortAndSlice(tested.context(), makeFormula(1, 2, 3, 4, 5, 6, 7, 8, 9), 3, 5)
			);
			assertArrayEquals(
				new int[]{7, 8, 9},
				tested.sorter().sortAndSlice(tested.context(), makeFormula(7, 8, 9), 0, 3)
			);
		});
	}

	@Test
	void shouldReturnSortedResultEvenForMissingData() {
		Stream.of(bitmapSorter, entitySorter).forEach(tested -> {
			final int[] actual = tested.sorter().sortAndSlice(tested.context(), makeFormula(0, 1, 2, 3, 4, 12, 13), 0, 100);
			assertArrayEquals(
				new int[]{2, 4, 1, 3, 0, 12, 13},
				actual
			);
		});
	}

	@Test
	void shouldReturnSortedResultEvenForMissingDataWithAdditionalSorter() {
		Stream.of(bitmapSorter, entitySorter).forEach(tested -> {
			final Sorter updatedSorter = tested.sorter().andThen(
				new PreSortedRecordsSorter(
					ENTITY_TYPE,
					() -> new SortedRecordsProvider[] {new MockSortedRecordsSupplier(13, 0, 12)},
					TEST_COMPARATOR_SECOND
				)
			);

			final int[] actual = updatedSorter.sortAndSlice(tested.context(), makeFormula(0, 1, 2, 3, 4, 12, 13), 0, 100);
			assertArrayEquals(
				new int[]{2, 4, 1, 3, 13, 0, 12},
				actual
			);
		});
	}

	@Test
	void shouldReturnFullResultInExpectedOrderOnLargeData() {
		final int[] sortedRecordIds = generateRandomSortedRecords(2500);
		final MockSortedRecordsSupplier sortedRecordsSupplier = new MockSortedRecordsSupplier(sortedRecordIds);

		assertArrayEquals(
			sortedRecordIds,
			sortedRecordsSupplier.getSortedRecordIds()
		);

		final PreSortedRecordsSorter sorter = new PreSortedRecordsSorter(
			ENTITY_TYPE,
			() -> new SortedRecordsProvider[] {sortedRecordsSupplier},
			NullThrowingEntityComparator.INSTANCE
		);
		final QueryContext queryContext = Mockito.mock(QueryContext.class);
		Mockito.when(queryContext.getPrefetchedEntities()).thenReturn(null);

		for (int i = 0; i < 5; i++) {
			int[] recIds = pickRandomResults(sortedRecordIds, 500);
			assertPageIsConsistent(sortedRecordIds, sorter, queryContext, recIds, 0, 50);
			assertPageIsConsistent(sortedRecordIds, sorter, queryContext, recIds, 75, 125);
			assertPageIsConsistent(sortedRecordIds, sorter, queryContext, recIds, 100, 500);
		}
	}

	private List<EntityDecorator> createMockEntities(int... expectedOrder) {
		final List<EntityDecorator> result = new ArrayList<>(expectedOrder.length);
		for (int i = 0; i < expectedOrder.length; i++) {
			final EntityDecorator mock = Mockito.mock(EntityDecorator.class);
			Mockito.when(mock.getPrimaryKey()).thenReturn(expectedOrder[i]);
			Mockito.when(mock.getAttribute(ATTRIBUTE_NAME_FIRST)).thenReturn(String.valueOf(Character.valueOf((char) (64 + i))));
			Mockito.when(mock.getAttribute(ATTRIBUTE_NAME_SECOND)).thenReturn(null);
			result.add(mock);
		}

		final AtomicInteger index = new AtomicInteger();
		Stream.of(13, 0, 12).forEach(pk -> {
			final EntityDecorator mock = Mockito.mock(EntityDecorator.class);
			Mockito.when(mock.getPrimaryKey()).thenReturn(pk);
			Mockito.when(mock.getAttribute(ATTRIBUTE_NAME_FIRST)).thenReturn(null);
			Mockito.when(mock.getAttribute(ATTRIBUTE_NAME_SECOND)).thenReturn(String.valueOf(Character.valueOf((char) (64 + index.getAndIncrement()))));
			result.add(mock);
		});
		result.sort(Comparator.comparing(EntityContract::getPrimaryKey));
		return result;
	}

	private void assertPageIsConsistent(int[] sortedRecordIds, PreSortedRecordsSorter sorter, QueryContext queryContext, int[] recIds, int startIndex, int endIndex) {
		final int[] sortedSlice = sorter.sortAndSlice(queryContext, makeFormula(recIds), startIndex, endIndex);
		assertEquals(endIndex - startIndex, sortedSlice.length);
		int lastPosition = -1;
		for (int recId : sortedSlice) {
			assertTrue(Arrays.binarySearch(recIds, recId) >= 0, "Record must be part of filter result!");
			int positionInSortedSet = findPosition(sortedRecordIds, recId);
			assertTrue(positionInSortedSet >= lastPosition, "Order must be monotonic!");
		}
	}

	private int findPosition(int[] sortedRecordIds, int recId) {
		for (int i = 0; i < sortedRecordIds.length; i++) {
			int sortedRecordId = sortedRecordIds[i];
			if (sortedRecordId == recId) {
				return i;
			}
		}
		return -1;
	}

	private int[] pickRandomResults(int[] sortedRecordIds, int count) {
		final Random random = new Random();
		final int[] recs = new int[count];
		final Set<Integer> picked = new HashSet<>(count);
		int peak = 0;
		do {
			final int randomRecordId = sortedRecordIds[random.nextInt(sortedRecordIds.length)];
			if (picked.add(randomRecordId)) {
				recs[peak++] = randomRecordId;
			}
		} while (peak < count);
		Arrays.sort(recs);
		return recs;
	}

	private int[] generateRandomSortedRecords(int recCount) {
		final Random random = new Random();
		final Set<Integer> randomRecordIds = new TreeSet<>();
		final int[] sortedRecordIds = new int[recCount];
		int peak = 0;
		do {
			final int rndRecId = random.nextInt(recCount * 2);
			if (randomRecordIds.add(rndRecId)) {
				sortedRecordIds[peak++] = rndRecId;
			}
		} while (peak < recCount);
		return sortedRecordIds;
	}

	@Nonnull
	private ConstantFormula makeFormula(int... recordIds) {
		return new ConstantFormula(new BaseBitmap(recordIds));
	}

	private record PreSortedRecordsSorterWithContext(
		@Nonnull PreSortedRecordsSorter sorter,
		@Nonnull QueryContext context
	) {
	}

	@SuppressWarnings("ComparatorNotSerializable")
	private static class NullThrowingEntityComparator implements EntityComparator {
		public static final EntityComparator INSTANCE = new NullThrowingEntityComparator();

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			throw new UnsupportedOperationException("Non-implemented");
		}

		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			throw new UnsupportedOperationException("Non-implemented");
		}

	}

}