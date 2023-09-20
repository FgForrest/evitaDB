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
import io.evitadb.core.query.sort.attribute.PrefetchedRecordsSorter;
import io.evitadb.core.query.sort.attribute.translator.AttributeComparator;
import io.evitadb.core.query.sort.attribute.translator.EntityAttributeExtractor;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.core.query.sort.utils.SortUtilsTest.asResult;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies {@link PrefetchedRecordsSorter} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PrefetchedRecordsSorterTest {
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
	private PrefetchedRecordsSorterWithContext entitySorter;

	@BeforeEach
	void setUp() {
		final List<EntityDecorator> mockEntities = createMockEntities(7, 2, 4, 1, 3, 8, 5, 9, 6);
		final Map<Integer, SealedEntity> mockEntitiesIndex = mockEntities.stream().collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));
		final QueryContext entityQueryContext = Mockito.mock(QueryContext.class);
		Mockito.when(entityQueryContext.getPrefetchedEntities()).thenReturn(mockEntities);
		Mockito.when(entityQueryContext.translateEntity(Mockito.any()))
			.thenAnswer(invocation -> ((EntityContract) invocation.getArgument(0)).getPrimaryKey());
		Mockito.when(entityQueryContext.translateToEntity(Mockito.anyInt()))
			.thenAnswer(invocation -> mockEntitiesIndex.get(((Integer) invocation.getArgument(0))));
		entitySorter = new PrefetchedRecordsSorterWithContext(
			new PrefetchedRecordsSorter(
				TEST_COMPARATOR_FIRST
			),
			entityQueryContext
		);
	}

	@Test
	void shouldReturnFullResultInExpectedOrderOnSmallData() {
		assertArrayEquals(
			new int[]{2, 4, 1, 3},
			asResult(theArray -> entitySorter.sorter().sortAndSlice(entitySorter.context(), makeFormula(1, 2, 3, 4), 0, 100, theArray, 0))
		);
		assertArrayEquals(
			new int[]{1, 3},
			asResult(theArray -> entitySorter.sorter().sortAndSlice(entitySorter.context(), makeFormula(1, 2, 3, 4, 5, 6, 7, 8, 9), 3, 5, theArray, 0))
		);
		assertArrayEquals(
			new int[]{7, 8, 9},
			asResult(theArray -> entitySorter.sorter().sortAndSlice(entitySorter.context(), makeFormula(7, 8, 9), 0, 3, theArray, 0))
		);
	}

	@Test
	void shouldReturnSortedResultEvenForMissingData() {
		final int[] actual = asResult(theArray -> entitySorter.sorter().sortAndSlice(entitySorter.context(), makeFormula(0, 1, 2, 3, 4, 12, 13), 0, 100, theArray, 0));
		assertArrayEquals(
			new int[]{2, 4, 1, 3, 0, 12, 13},
			actual
		);
	}

	@Test
	void shouldReturnSortedResultEvenForMissingDataWithAdditionalSorter() {
		final Sorter updatedSorter = entitySorter.sorter().andThen(
			new PrefetchedRecordsSorter(
				TEST_COMPARATOR_SECOND
			)
		);

		final int[] actual = asResult(theArray -> updatedSorter.sortAndSlice(entitySorter.context(), makeFormula(0, 1, 2, 3, 4, 12, 13), 0, 100, theArray, 0));
		assertArrayEquals(
			new int[]{2, 4, 1, 3, 13, 0, 12},
			actual
		);
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

	@Nonnull
	private ConstantFormula makeFormula(int... recordIds) {
		return new ConstantFormula(new BaseBitmap(recordIds));
	}

	private record PrefetchedRecordsSorterWithContext(
		@Nonnull PrefetchedRecordsSorter sorter,
		@Nonnull QueryContext context
	) {
	}

}