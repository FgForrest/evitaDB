/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.sort;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.core.query.sort.attribute.translator.AttributeComparator;
import io.evitadb.core.query.sort.generic.PrefetchedRecordsSorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * This test verifies {@link PrefetchedRecordsSorter} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PrefetchedRecordsSorterTest {
	private static final String ATTRIBUTE_NAME_FIRST = "first";
	private static final String ATTRIBUTE_NAME_SECOND = "second";

	private final EntityComparator TEST_COMPARATOR_FIRST = new AttributeComparator(
		ATTRIBUTE_NAME_FIRST, String.class, null, OrderDirection.ASC
	);
	private final EntityComparator TEST_COMPARATOR_SECOND = new AttributeComparator(
		ATTRIBUTE_NAME_SECOND, String.class, null, OrderDirection.ASC
	);
	private PrefetchedRecordsSorterWithContext entitySorter;

	private static List<ServerEntityDecorator> createMockEntities(int... expectedOrder) {
		final List<ServerEntityDecorator> result = new ArrayList<>(expectedOrder.length);
		for (int i = 0; i < expectedOrder.length; i++) {
			final ServerEntityDecorator mock = mock(ServerEntityDecorator.class);
			when(mock.getPrimaryKeyOrThrowException()).thenReturn(expectedOrder[i]);
			when(mock.getPrimaryKey()).thenReturn(expectedOrder[i]);
			when(mock.getAttribute(ATTRIBUTE_NAME_FIRST)).thenReturn(String.valueOf(Character.valueOf((char) (64 + i))));
			when(mock.getAttribute(ATTRIBUTE_NAME_SECOND)).thenReturn(null);
			result.add(mock);
		}

		final AtomicInteger index = new AtomicInteger();
		Stream.of(13, 0, 12).forEach(pk -> {
			final ServerEntityDecorator mock = mock(ServerEntityDecorator.class);
			when(mock.getPrimaryKeyOrThrowException()).thenReturn(pk);
			when(mock.getPrimaryKey()).thenReturn(pk);
			when(mock.getAttribute(ATTRIBUTE_NAME_FIRST)).thenReturn(null);
			when(mock.getAttribute(ATTRIBUTE_NAME_SECOND)).thenReturn(String.valueOf(Character.valueOf((char) (64 + index.getAndIncrement()))));
			result.add(mock);
		});
		result.sort(Comparator.comparing(EntityContract::getPrimaryKeyOrThrowException));
		return result;
	}

	@Nonnull
	private static Bitmap makeBitmap(int... recordIds) {
		return new BaseBitmap(recordIds);
	}

	@BeforeEach
	void setUp() {
		final List<ServerEntityDecorator> mockEntities = createMockEntities(7, 2, 4, 1, 3, 8, 5, 9, 6);
		final Map<Integer, SealedEntity> mockEntitiesIndex = mockEntities.stream().collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));
		final QueryPlanningContext planningContext = mock(QueryPlanningContext.class);
		final QueryExecutionContext executionContext = mock(QueryExecutionContext.class);
		when(planningContext.createExecutionContext()).thenReturn(executionContext);
		when(executionContext.getPrefetchedEntities()).thenReturn(mockEntities);
		when(executionContext.translateEntity(any()))
			.thenAnswer(invocation -> ((EntityContract) invocation.getArgument(0)).getPrimaryKey());
		when(executionContext.translateToEntity(anyInt()))
			.thenAnswer(invocation -> mockEntitiesIndex.get(((Integer) invocation.getArgument(0))));
		doAnswer(invocation -> SharedBufferPool.INSTANCE.obtain()).when(executionContext).borrowBuffer();
		doNothing().when(executionContext).returnBuffer(any());

		this.entitySorter = new PrefetchedRecordsSorterWithContext(
			new PrefetchedRecordsSorter(
				this.TEST_COMPARATOR_FIRST
			),
			planningContext
		);
	}

	@Test
	void shouldReturnFullResultInExpectedOrderOnSmallData() {
		final QueryExecutionContext executionContext = this.entitySorter.context().createExecutionContext();
		assertArrayEquals(
			new int[]{2, 4, 1, 3},
			asResult(
				theArray -> this.entitySorter.sorter().sortAndSlice(
					new SortingContext(
						executionContext, makeBitmap(1, 2, 3, 4),
						0, 100, 0, 0
					),
					theArray,
					null
				)
			)
		);
		assertArrayEquals(
			new int[]{1, 3},
			asResult(
				theArray -> this.entitySorter.sorter().sortAndSlice(
					new SortingContext(
						executionContext,
						makeBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9),
						3, 5, 0, 0
					),
					theArray,
					null
				)
			)
		);
		assertArrayEquals(
			new int[]{7, 8, 9},
			asResult(
				theArray -> this.entitySorter.sorter().sortAndSlice(
					new SortingContext(
						executionContext,
						makeBitmap(7, 8, 9),
						0, 3, 0, 0
					),
					theArray,
					null
				)
			)
		);
	}

	@Test
	void shouldReturnSortedResultEvenForMissingData() {
		final int[] actual = new NestedContextSorter(
			this.entitySorter.context().createExecutionContext(),
			() -> "whatever",
			List.of(
				this.entitySorter.sorter()
			)
		).sortAndSlice(makeBitmap(0, 1, 2, 3, 4, 12, 13));

		assertArrayEquals(
			new int[]{2, 4, 1, 3, 0, 12, 13},
			actual
		);
	}

	@Test
	void shouldReturnSortedResultEvenForMissingDataWithAdditionalSorter() {
		final int[] actual = new NestedContextSorter(
			this.entitySorter.context().createExecutionContext(),
			() -> "whatever",
			List.of(
				this.entitySorter.sorter(),
				new PrefetchedRecordsSorter(
					this.TEST_COMPARATOR_SECOND
				)
			)
		).sortAndSlice(makeBitmap(0, 1, 2, 3, 4, 12, 13));

		assertArrayEquals(
			new int[]{2, 4, 1, 3, 13, 0, 12},
			actual
		);
	}

	private record PrefetchedRecordsSorterWithContext(
		@Nonnull PrefetchedRecordsSorter sorter,
		@Nonnull QueryPlanningContext context
	) {
	}

}
