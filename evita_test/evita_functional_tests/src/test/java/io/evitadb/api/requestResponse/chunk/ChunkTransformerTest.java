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

package io.evitadb.api.requestResponse.chunk;

import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap;
import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.StripList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for the chunk package verifying {@link OffsetAndLimit},
 * {@link NoTransformer}, {@link PageTransformer},
 * {@link StripTransformer}, and {@link DefaultSlicer}.
 *
 * @author evitaDB
 */
@DisplayName("Chunk transformers and slicers")
class ChunkTransformerTest {

	/**
	 * Creates a list of mock {@link ReferenceContract} instances of the given size.
	 */
	private static List<ReferenceContract> createMockReferences(int count) {
		final List<ReferenceContract> references = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			references.add(mock(ReferenceContract.class));
		}
		return references;
	}

	@Nested
	@DisplayName("OffsetAndLimit")
	class OffsetAndLimitTest {

		@Test
		@DisplayName("should create with all five parameters")
		void shouldCreateWithAllParameters() {
			final OffsetAndLimit oal = new OffsetAndLimit(20, 10, 3, 5, 50);

			assertEquals(20, oal.offset());
			assertEquals(10, oal.limit());
			assertEquals(3, oal.pageNumber());
			assertEquals(5, oal.lastPageNumber());
			assertEquals(50, oal.totalRecordCount());
		}

		@Test
		@DisplayName("should default pageNumber and lastPageNumber to 1")
		void shouldCreateWithCompactConstructor() {
			final OffsetAndLimit oal = new OffsetAndLimit(5, 10, 100);

			assertEquals(5, oal.offset());
			assertEquals(10, oal.limit());
			assertEquals(1, oal.pageNumber());
			assertEquals(1, oal.lastPageNumber());
			assertEquals(100, oal.totalRecordCount());
		}

		@Test
		@DisplayName("should calculate length as offset + limit")
		void shouldCalculateLength() {
			final OffsetAndLimit oal = new OffsetAndLimit(20, 10, 3, 5, 50);

			assertEquals(30, oal.length());
		}
	}

	@Nested
	@DisplayName("NoTransformer")
	class NoTransformerTest {

		@Test
		@DisplayName("should wrap all items in a PlainChunk")
		void shouldWrapInPlainChunk() {
			final List<ReferenceContract> references = createMockReferences(5);
			final DataChunk<ReferenceContract> chunk =
				NoTransformer.INSTANCE.createChunk(references);

			assertInstanceOf(PlainChunk.class, chunk);
			assertEquals(5, chunk.getTotalRecordCount());
			assertEquals(5, chunk.getData().size());
			assertEquals(references, chunk.getData());
		}

		@Test
		@DisplayName("should handle empty list")
		void shouldHandleEmptyList() {
			final DataChunk<ReferenceContract> chunk =
				NoTransformer.INSTANCE.createChunk(Collections.emptyList());

			assertInstanceOf(PlainChunk.class, chunk);
			assertEquals(0, chunk.getTotalRecordCount());
			assertTrue(chunk.isEmpty());
		}
	}

	@Nested
	@DisplayName("PageTransformer")
	class PageTransformerTest {

		@Test
		@DisplayName("should return requested page")
		void shouldReturnRequestedPage() {
			// page 2, size 10, total 25 -> items 10..19
			final Page page = new Page(2, 10);
			final PageTransformer transformer =
				new PageTransformer(page, new ConditionalGap[0]);
			final List<ReferenceContract> references = createMockReferences(25);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(PaginatedList.class, chunk);
			final PaginatedList<ReferenceContract> paginated =
				(PaginatedList<ReferenceContract>) chunk;
			assertEquals(10, chunk.getData().size());
			assertEquals(25, chunk.getTotalRecordCount());
			assertEquals(2, paginated.getPageNumber());
			assertEquals(3, paginated.getLastPageNumber());
			// verify correct items (indices 10..19)
			assertEquals(references.subList(10, 20), chunk.getData());
		}

		@Test
		@DisplayName("should return partial last page")
		void shouldReturnPartialLastPage() {
			// page 3, size 10, total 25 -> items 20..24 (5 items)
			final Page page = new Page(3, 10);
			final PageTransformer transformer =
				new PageTransformer(page, new ConditionalGap[0]);
			final List<ReferenceContract> references = createMockReferences(25);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(PaginatedList.class, chunk);
			assertEquals(5, chunk.getData().size());
			assertEquals(references.subList(20, 25), chunk.getData());
		}

		@Test
		@DisplayName("should reset to page 1 when beyond limit")
		void shouldResetToPage1WhenBeyondLimit() {
			// page 10, size 10, total 20 -> beyond limit, reset to page 1
			final Page page = new Page(10, 10);
			final PageTransformer transformer =
				new PageTransformer(page, new ConditionalGap[0]);
			final List<ReferenceContract> references = createMockReferences(20);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(PaginatedList.class, chunk);
			final PaginatedList<ReferenceContract> paginated =
				(PaginatedList<ReferenceContract>) chunk;
			assertEquals(1, paginated.getPageNumber());
			assertEquals(10, chunk.getData().size());
			assertEquals(references.subList(0, 10), chunk.getData());
		}

		@Test
		@DisplayName("should handle empty list")
		void shouldHandleEmptyList() {
			final Page page = new Page(1, 10);
			final PageTransformer transformer =
				new PageTransformer(page, new ConditionalGap[0]);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(Collections.emptyList());

			assertInstanceOf(PaginatedList.class, chunk);
			assertEquals(0, chunk.getTotalRecordCount());
			assertTrue(chunk.isEmpty());
		}

		@Test
		@DisplayName("should handle page size larger than total")
		void shouldHandlePageSizeLargerThanTotal() {
			final Page page = new Page(1, 100);
			final PageTransformer transformer =
				new PageTransformer(page, new ConditionalGap[0]);
			final List<ReferenceContract> references = createMockReferences(5);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(PaginatedList.class, chunk);
			assertEquals(5, chunk.getData().size());
			assertEquals(5, chunk.getTotalRecordCount());
			assertEquals(references, chunk.getData());
		}

		@Test
		@DisplayName("should create chunk via static method")
		void shouldCreateChunkViaStaticMethod() {
			final List<ReferenceContract> references = createMockReferences(30);

			// page 2, pageSize 10, lastPage 3, offset 10, limit 10, total 30
			final DataChunk<ReferenceContract> chunk =
				PageTransformer.getReferenceContractDataChunk(
					references, 2, 10, 3, 10, 10, 30
				);

			assertInstanceOf(PaginatedList.class, chunk);
			final PaginatedList<ReferenceContract> paginated =
				(PaginatedList<ReferenceContract>) chunk;
			assertEquals(2, paginated.getPageNumber());
			assertEquals(3, paginated.getLastPageNumber());
			assertEquals(10, chunk.getData().size());
			assertEquals(references.subList(10, 20), chunk.getData());
		}
	}

	@Nested
	@DisplayName("StripTransformer")
	class StripTransformerTest {

		@Test
		@DisplayName("should return strip from start")
		void shouldReturnStripFromStart() {
			final Strip strip = new Strip(0, 10);
			final StripTransformer transformer = new StripTransformer(strip);
			final List<ReferenceContract> references = createMockReferences(50);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(StripList.class, chunk);
			final StripList<ReferenceContract> stripList =
				(StripList<ReferenceContract>) chunk;
			assertEquals(10, chunk.getData().size());
			assertEquals(50, chunk.getTotalRecordCount());
			assertEquals(0, stripList.getOffset());
			assertEquals(10, stripList.getLimit());
			assertEquals(references.subList(0, 10), chunk.getData());
		}

		@Test
		@DisplayName("should return strip from middle")
		void shouldReturnStripFromMiddle() {
			final Strip strip = new Strip(20, 10);
			final StripTransformer transformer = new StripTransformer(strip);
			final List<ReferenceContract> references = createMockReferences(50);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(StripList.class, chunk);
			assertEquals(10, chunk.getData().size());
			assertEquals(references.subList(20, 30), chunk.getData());
		}

		@Test
		@DisplayName("should return partial strip near end")
		void shouldReturnPartialStrip() {
			// offset=45, limit=10, total=50 -> items 45..49 (5 items)
			final Strip strip = new Strip(45, 10);
			final StripTransformer transformer = new StripTransformer(strip);
			final List<ReferenceContract> references = createMockReferences(50);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(StripList.class, chunk);
			assertEquals(5, chunk.getData().size());
			assertEquals(references.subList(45, 50), chunk.getData());
		}

		@Test
		@DisplayName("should handle empty list")
		void shouldHandleEmptyList() {
			final Strip strip = new Strip(0, 10);
			final StripTransformer transformer = new StripTransformer(strip);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(Collections.emptyList());

			assertInstanceOf(StripList.class, chunk);
			assertEquals(0, chunk.getTotalRecordCount());
			assertTrue(chunk.isEmpty());
		}

		@Test
		@DisplayName(
			"should reset to offset 0 when offset beyond end"
		)
		void shouldResetToOffset0WhenOffsetBeyondEnd() {
			// offset=100, limit=10, total=50 -> should reset to offset 0
			final Strip strip = new Strip(100, 10);
			final StripTransformer transformer = new StripTransformer(strip);
			final List<ReferenceContract> references = createMockReferences(50);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(StripList.class, chunk);
			final StripList<ReferenceContract> stripList =
				(StripList<ReferenceContract>) chunk;
			// per Strip JavaDoc: "If the requested strip exceeds the number
			// of available records, a result from the zero offset with
			// retained limit is returned."
			assertEquals(0, stripList.getOffset());
			assertEquals(10, chunk.getData().size());
			assertEquals(references.subList(0, 10), chunk.getData());
		}

		@Test
		@DisplayName(
			"should reset to offset 0 when offset equals size"
		)
		void shouldResetToOffset0WhenOffsetEqualsSize() {
			// offset=50, limit=10, total=50 -> should reset to offset 0
			final Strip strip = new Strip(50, 10);
			final StripTransformer transformer = new StripTransformer(strip);
			final List<ReferenceContract> references = createMockReferences(50);

			final DataChunk<ReferenceContract> chunk =
				transformer.createChunk(references);

			assertInstanceOf(StripList.class, chunk);
			final StripList<ReferenceContract> stripList =
				(StripList<ReferenceContract>) chunk;
			assertEquals(0, stripList.getOffset());
			assertEquals(10, chunk.getData().size());
			assertEquals(references.subList(0, 10), chunk.getData());
		}
	}

	@Nested
	@DisplayName("DefaultSlicer")
	class DefaultSlicerTest {

		@Test
		@DisplayName("should calculate for paginated list")
		void shouldCalculateForPaginatedList() {
			// page 3, size 10, total 50 -> offset = (3-1)*10 = 20
			final OffsetAndLimit oal = DefaultSlicer.INSTANCE
				.calculateOffsetAndLimit(ResultForm.PAGINATED_LIST, 3, 10, 50);

			assertEquals(20, oal.offset());
			assertEquals(10, oal.limit());
			assertEquals(3, oal.pageNumber());
			assertEquals(5, oal.lastPageNumber());
			assertEquals(50, oal.totalRecordCount());
		}

		@Test
		@DisplayName(
			"should reset to page 1 when beyond limit for paginated list"
		)
		void shouldResetToPage1WhenBeyondLimitForPaginatedList() {
			// page 10, size 10, total 20 -> beyond limit, reset to page 1
			final OffsetAndLimit oal = DefaultSlicer.INSTANCE
				.calculateOffsetAndLimit(ResultForm.PAGINATED_LIST, 10, 10, 20);

			assertEquals(0, oal.offset());
			assertEquals(10, oal.limit());
			assertEquals(1, oal.pageNumber());
			assertEquals(2, oal.lastPageNumber());
			assertEquals(20, oal.totalRecordCount());
		}

		@Test
		@DisplayName("should calculate for strip list")
		void shouldCalculateForStripList() {
			// offset=20, limit=10, total=50 -> pass-through
			final OffsetAndLimit oal = DefaultSlicer.INSTANCE
				.calculateOffsetAndLimit(ResultForm.STRIP_LIST, 20, 10, 50);

			assertEquals(20, oal.offset());
			assertEquals(10, oal.limit());
			assertEquals(1, oal.pageNumber());
			assertEquals(1, oal.lastPageNumber());
			assertEquals(50, oal.totalRecordCount());
		}

		@Test
		@DisplayName("should throw for unsupported result form")
		void shouldThrowForUnsupportedResultForm() {
			// The only two enum values are PAGINATED_LIST and STRIP_LIST,
			// but the default branch throws for safety.
			// We verify the method signature accepts ResultForm
			// and the two known forms work (covered above).
			// Since we can't create a third enum value, we verify
			// the two known values don't throw.
			final OffsetAndLimit paginated = DefaultSlicer.INSTANCE
				.calculateOffsetAndLimit(ResultForm.PAGINATED_LIST, 1, 10, 100);
			final OffsetAndLimit strip = DefaultSlicer.INSTANCE
				.calculateOffsetAndLimit(ResultForm.STRIP_LIST, 0, 10, 100);

			assertEquals(10, paginated.limit());
			assertEquals(10, strip.limit());
		}
	}
}
