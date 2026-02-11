/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.trafficRecording;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static io.evitadb.api.query.QueryConstraints.collection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for all traffic recording container types.
 *
 * @author Claude
 */
@DisplayName("Traffic recording containers")
class TrafficRecordingContainerTest {

	private static final UUID SESSION_ID = UUID.randomUUID();
	private static final OffsetDateTime CREATED = OffsetDateTime.now();
	private static final Query TEST_QUERY = Query.query(collection("product"));

	@Nested
	@DisplayName("QueryContainer")
	class QueryContainerTests {

		@Test
		@DisplayName("should return QUERY type")
		void shouldReturnQueryType() {
			final QueryContainer container = createQueryContainer();
			assertEquals(TrafficRecordingType.QUERY, container.type());
		}

		@Test
		@DisplayName("should implement TrafficRecording and TrafficRecordingWithLabels")
		void shouldImplementCorrectInterfaces() {
			final QueryContainer container = createQueryContainer();
			assertInstanceOf(TrafficRecording.class, container);
			assertInstanceOf(TrafficRecordingWithLabels.class, container);
		}

		@Test
		@DisplayName("should create via convenience constructor with null sequence order")
		void shouldCreateViaConvenienceConstructor() {
			final QueryContainer container = new QueryContainer(
				SESSION_ID, 0, "query desc", TEST_QUERY,
				Label.EMPTY_LABELS, CREATED, 100, 5, 2, 1024,
				new int[]{1, 2, 3}, null
			);
			assertNull(container.sessionSequenceOrder());
			assertNull(container.sessionRecordsCount());
			assertEquals(SESSION_ID, container.sessionId());
		}

		@Test
		@DisplayName("should create via canonical constructor")
		void shouldCreateViaCanonicalConstructor() {
			final QueryContainer container = new QueryContainer(
				42L, SESSION_ID, 0, 10, "query desc", TEST_QUERY,
				Label.EMPTY_LABELS, CREATED, 100, 5, 2, 1024,
				new int[]{1, 2, 3}, null
			);
			assertEquals(42L, container.sessionSequenceOrder());
			assertEquals(10, container.sessionRecordsCount());
		}

		@Test
		@DisplayName("should include queryDescription in equals comparison")
		void shouldIncludeQueryDescriptionInEquals() {
			final QueryContainer container1 = new QueryContainer(
				SESSION_ID, 0, "desc A", TEST_QUERY,
				Label.EMPTY_LABELS, CREATED, 100, 5, 2, 1024,
				new int[]{1, 2, 3}, null
			);
			final QueryContainer container2 = new QueryContainer(
				SESSION_ID, 0, "desc B", TEST_QUERY,
				Label.EMPTY_LABELS, CREATED, 100, 5, 2, 1024,
				new int[]{1, 2, 3}, null
			);
			// Known limitation: queryDescription is NOT included in equals/hashCode
			assertNotEquals(container1, container2);
		}

		@Test
		@DisplayName("should be equal for same values")
		void shouldBeEqualForSameValues() {
			final QueryContainer container1 = createQueryContainer();
			final QueryContainer container2 = createQueryContainer();
			assertEquals(container1, container2);
			assertEquals(container1.hashCode(), container2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different finishedWithError")
		void shouldNotBeEqualForDifferentError() {
			final QueryContainer container1 = new QueryContainer(
				SESSION_ID, 0, "desc", TEST_QUERY,
				Label.EMPTY_LABELS, CREATED, 100, 5, 2, 1024,
				new int[]{1}, null
			);
			final QueryContainer container2 = new QueryContainer(
				SESSION_ID, 0, "desc", TEST_QUERY,
				Label.EMPTY_LABELS, CREATED, 100, 5, 2, 1024,
				new int[]{1}, "error occurred"
			);
			assertNotEquals(container1, container2);
		}

		private QueryContainer createQueryContainer() {
			return new QueryContainer(
				SESSION_ID, 0, "test query", TEST_QUERY,
				Label.EMPTY_LABELS, CREATED, 100, 5, 2, 1024,
				new int[]{1, 2, 3}, null
			);
		}
	}

	@Nested
	@DisplayName("EntityFetchContainer")
	class EntityFetchContainerTests {

		@Test
		@DisplayName("should return FETCH type")
		void shouldReturnFetchType() {
			final EntityFetchContainer container = createFetchContainer();
			assertEquals(TrafficRecordingType.FETCH, container.type());
		}

		@Test
		@DisplayName("should implement TrafficRecording")
		void shouldImplementCorrectInterfaces() {
			final EntityFetchContainer container = createFetchContainer();
			assertInstanceOf(TrafficRecording.class, container);
		}

		@Test
		@DisplayName("should create via convenience constructor with null sequence order")
		void shouldCreateViaConvenienceConstructor() {
			final EntityFetchContainer container = new EntityFetchContainer(
				SESSION_ID, 1, TEST_QUERY, CREATED, 50, 3, 512, 42, null
			);
			assertNull(container.sessionSequenceOrder());
			assertNull(container.sessionRecordsCount());
			assertEquals(42, container.primaryKey());
		}

		@Test
		@DisplayName("should create via canonical constructor")
		void shouldCreateViaCanonicalConstructor() {
			final EntityFetchContainer container = new EntityFetchContainer(
				10L, SESSION_ID, 1, 5, TEST_QUERY, CREATED,
				50, 3, 512, 42, null
			);
			assertEquals(10L, container.sessionSequenceOrder());
			assertEquals(5, container.sessionRecordsCount());
		}

		@Test
		@DisplayName("should store finishedWithError")
		void shouldStoreFinishedWithError() {
			final EntityFetchContainer container = new EntityFetchContainer(
				SESSION_ID, 1, TEST_QUERY, CREATED, 50, 3, 512, 42,
				"fetch failed"
			);
			assertEquals("fetch failed", container.finishedWithError());
		}

		private EntityFetchContainer createFetchContainer() {
			return new EntityFetchContainer(
				SESSION_ID, 1, TEST_QUERY, CREATED, 50, 3, 512, 42, null
			);
		}
	}

	@Nested
	@DisplayName("EntityEnrichmentContainer")
	class EntityEnrichmentContainerTests {

		@Test
		@DisplayName("should return ENRICHMENT type")
		void shouldReturnEnrichmentType() {
			final EntityEnrichmentContainer container = createEnrichmentContainer();
			assertEquals(TrafficRecordingType.ENRICHMENT, container.type());
		}

		@Test
		@DisplayName("should implement TrafficRecording")
		void shouldImplementCorrectInterfaces() {
			final EntityEnrichmentContainer container = createEnrichmentContainer();
			assertInstanceOf(TrafficRecording.class, container);
		}

		@Test
		@DisplayName("should create via convenience constructor")
		void shouldCreateViaConvenienceConstructor() {
			final EntityEnrichmentContainer container = new EntityEnrichmentContainer(
				SESSION_ID, 2, TEST_QUERY, CREATED, 30, 1, 256, 7, null
			);
			assertNull(container.sessionSequenceOrder());
			assertEquals(7, container.primaryKey());
		}

		@Test
		@DisplayName("should create via canonical constructor")
		void shouldCreateViaCanonicalConstructor() {
			final EntityEnrichmentContainer container = new EntityEnrichmentContainer(
				5L, SESSION_ID, 2, 8, TEST_QUERY, CREATED,
				30, 1, 256, 7, null
			);
			assertEquals(5L, container.sessionSequenceOrder());
			assertEquals(8, container.sessionRecordsCount());
		}

		private EntityEnrichmentContainer createEnrichmentContainer() {
			return new EntityEnrichmentContainer(
				SESSION_ID, 2, TEST_QUERY, CREATED, 30, 1, 256, 7, null
			);
		}
	}

	@Nested
	@DisplayName("MutationContainer")
	class MutationContainerTests {

		@Test
		@DisplayName("should return MUTATION type")
		void shouldReturnMutationType() {
			final MutationContainer container = createMutationContainer();
			assertEquals(TrafficRecordingType.MUTATION, container.type());
		}

		@Test
		@DisplayName("should implement TrafficRecording")
		void shouldImplementCorrectInterfaces() {
			final MutationContainer container = createMutationContainer();
			assertInstanceOf(TrafficRecording.class, container);
		}

		@Test
		@DisplayName("should return 0 for ioFetchCount and ioFetchedSizeBytes")
		void shouldReturnZeroForIoFields() {
			final MutationContainer container = createMutationContainer();
			assertEquals(0, container.ioFetchCount());
			assertEquals(0, container.ioFetchedSizeBytes());
		}

		@Test
		@DisplayName("should create via convenience constructor")
		void shouldCreateViaConvenienceConstructor() {
			final MutationContainer container = new MutationContainer(
				SESSION_ID, 3, CREATED, 10,
				new RemoveEntitySchemaMutation("product"), null
			);
			assertNull(container.sessionSequenceOrder());
			assertNull(container.sessionRecordsCount());
		}

		@Test
		@DisplayName("should create via canonical constructor")
		void shouldCreateViaCanonicalConstructor() {
			final MutationContainer container = new MutationContainer(
				1L, SESSION_ID, 3, 12, CREATED, 10,
				new RemoveEntitySchemaMutation("product"), null
			);
			assertEquals(1L, container.sessionSequenceOrder());
			assertEquals(12, container.sessionRecordsCount());
		}

		private MutationContainer createMutationContainer() {
			return new MutationContainer(
				SESSION_ID, 3, CREATED, 10,
				new RemoveEntitySchemaMutation("product"), null
			);
		}
	}

	@Nested
	@DisplayName("SourceQueryContainer")
	class SourceQueryContainerTests {

		private static final UUID SOURCE_QUERY_ID = UUID.randomUUID();

		@Test
		@DisplayName("should return SOURCE_QUERY type")
		void shouldReturnSourceQueryType() {
			final SourceQueryContainer container = createSourceQueryContainer();
			assertEquals(TrafficRecordingType.SOURCE_QUERY, container.type());
		}

		@Test
		@DisplayName("should implement TransientTrafficRecording and TrafficRecordingWithLabels")
		void shouldImplementCorrectInterfaces() {
			final SourceQueryContainer container = createSourceQueryContainer();
			assertInstanceOf(TransientTrafficRecording.class, container);
			assertInstanceOf(TrafficRecordingWithLabels.class, container);
		}

		@Test
		@DisplayName("should return 0 for duration and IO fields")
		void shouldReturnZeroForTransientFields() {
			final SourceQueryContainer container = createSourceQueryContainer();
			assertEquals(0, container.durationInMilliseconds());
			assertEquals(0, container.ioFetchCount());
			assertEquals(0, container.ioFetchedSizeBytes());
		}

		@Test
		@DisplayName("should create via convenience constructor")
		void shouldCreateViaConvenienceConstructor() {
			final SourceQueryContainer container = new SourceQueryContainer(
				SESSION_ID, 0, SOURCE_QUERY_ID, CREATED,
				"SELECT * FROM products", Label.EMPTY_LABELS, null
			);
			assertNull(container.sessionSequenceOrder());
			assertNull(container.sessionRecordsCount());
			assertEquals(SOURCE_QUERY_ID, container.sourceQueryId());
		}

		@Test
		@DisplayName("should be equal for same values")
		void shouldBeEqualForSameValues() {
			final SourceQueryContainer c1 = createSourceQueryContainer();
			final SourceQueryContainer c2 = createSourceQueryContainer();
			assertEquals(c1, c2);
			assertEquals(c1.hashCode(), c2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different source query")
		void shouldNotBeEqualForDifferentSourceQuery() {
			final SourceQueryContainer c1 = new SourceQueryContainer(
				SESSION_ID, 0, SOURCE_QUERY_ID, CREATED,
				"query A", Label.EMPTY_LABELS, null
			);
			final SourceQueryContainer c2 = new SourceQueryContainer(
				SESSION_ID, 0, SOURCE_QUERY_ID, CREATED,
				"query B", Label.EMPTY_LABELS, null
			);
			assertNotEquals(c1, c2);
		}

		private SourceQueryContainer createSourceQueryContainer() {
			return new SourceQueryContainer(
				SESSION_ID, 0, SOURCE_QUERY_ID, CREATED,
				"SELECT * FROM products", Label.EMPTY_LABELS, null
			);
		}
	}

	@Nested
	@DisplayName("SourceQueryStatisticsContainer")
	class SourceQueryStatisticsContainerTests {

		private static final UUID SOURCE_QUERY_ID = UUID.randomUUID();

		@Test
		@DisplayName("should return SOURCE_QUERY_STATISTICS type")
		void shouldReturnSourceQueryStatisticsType() {
			final SourceQueryStatisticsContainer container =
				createSourceQueryStatisticsContainer();
			assertEquals(
				TrafficRecordingType.SOURCE_QUERY_STATISTICS,
				container.type()
			);
		}

		@Test
		@DisplayName("should implement TransientTrafficRecording and TrafficRecordingWithLabels")
		void shouldImplementCorrectInterfaces() {
			final SourceQueryStatisticsContainer container =
				createSourceQueryStatisticsContainer();
			assertInstanceOf(TransientTrafficRecording.class, container);
			assertInstanceOf(TrafficRecordingWithLabels.class, container);
		}

		@Test
		@DisplayName("should create via convenience constructor")
		void shouldCreateViaConvenienceConstructor() {
			final SourceQueryStatisticsContainer container =
				new SourceQueryStatisticsContainer(
					SESSION_ID, 5, SOURCE_QUERY_ID, CREATED,
					200, 10, 4096, 50, 100,
					Label.EMPTY_LABELS, null
				);
			assertNull(container.sessionSequenceOrder());
			assertNull(container.sessionRecordsCount());
		}

		@Test
		@DisplayName("should be equal for same values")
		void shouldBeEqualForSameValues() {
			final SourceQueryStatisticsContainer c1 =
				createSourceQueryStatisticsContainer();
			final SourceQueryStatisticsContainer c2 =
				createSourceQueryStatisticsContainer();
			assertEquals(c1, c2);
			assertEquals(c1.hashCode(), c2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different record counts")
		void shouldNotBeEqualForDifferentRecordCounts() {
			final SourceQueryStatisticsContainer c1 =
				new SourceQueryStatisticsContainer(
					SESSION_ID, 5, SOURCE_QUERY_ID, CREATED,
					200, 10, 4096, 50, 100,
					Label.EMPTY_LABELS, null
				);
			final SourceQueryStatisticsContainer c2 =
				new SourceQueryStatisticsContainer(
					SESSION_ID, 5, SOURCE_QUERY_ID, CREATED,
					200, 10, 4096, 99, 100,
					Label.EMPTY_LABELS, null
				);
			assertNotEquals(c1, c2);
		}

		private SourceQueryStatisticsContainer createSourceQueryStatisticsContainer() {
			return new SourceQueryStatisticsContainer(
				SESSION_ID, 5, SOURCE_QUERY_ID, CREATED,
				200, 10, 4096, 50, 100,
				Label.EMPTY_LABELS, null
			);
		}
	}

	@Nested
	@DisplayName("SessionStartContainer")
	class SessionStartContainerTests {

		@Test
		@DisplayName("should return SESSION_START type")
		void shouldReturnSessionStartType() {
			final SessionStartContainer container = createSessionStartContainer();
			assertEquals(TrafficRecordingType.SESSION_START, container.type());
		}

		@Test
		@DisplayName("should implement TransientTrafficRecording")
		void shouldImplementCorrectInterfaces() {
			final SessionStartContainer container = createSessionStartContainer();
			assertInstanceOf(TransientTrafficRecording.class, container);
		}

		@Test
		@DisplayName("should return 0 for duration and IO fields")
		void shouldReturnZeroForTransientFields() {
			final SessionStartContainer container = createSessionStartContainer();
			assertEquals(0, container.durationInMilliseconds());
			assertEquals(0, container.ioFetchCount());
			assertEquals(0, container.ioFetchedSizeBytes());
		}

		@Test
		@DisplayName("should return null for finishedWithError")
		void shouldReturnNullForFinishedWithError() {
			final SessionStartContainer container = createSessionStartContainer();
			assertNull(container.finishedWithError());
		}

		@Test
		@DisplayName("should create via convenience constructor")
		void shouldCreateViaConvenienceConstructor() {
			final SessionStartContainer container = new SessionStartContainer(
				SESSION_ID, 0, 1L, CREATED
			);
			assertNull(container.sessionSequenceOrder());
			assertNull(container.sessionRecordsCount());
			assertEquals(1L, container.catalogVersion());
		}

		@Test
		@DisplayName("should create via canonical constructor")
		void shouldCreateViaCanonicalConstructor() {
			final SessionStartContainer container = new SessionStartContainer(
				100L, SESSION_ID, 0, 15, 1L, CREATED
			);
			assertEquals(100L, container.sessionSequenceOrder());
			assertEquals(15, container.sessionRecordsCount());
		}

		private SessionStartContainer createSessionStartContainer() {
			return new SessionStartContainer(SESSION_ID, 0, 1L, CREATED);
		}
	}

	@Nested
	@DisplayName("SessionCloseContainer")
	class SessionCloseContainerTests {

		@Test
		@DisplayName("should return SESSION_CLOSE type")
		void shouldReturnSessionCloseType() {
			final SessionCloseContainer container = createSessionCloseContainer();
			assertEquals(TrafficRecordingType.SESSION_CLOSE, container.type());
		}

		@Test
		@DisplayName("should implement TransientTrafficRecording")
		void shouldImplementCorrectInterfaces() {
			final SessionCloseContainer container = createSessionCloseContainer();
			assertInstanceOf(TransientTrafficRecording.class, container);
		}

		@Test
		@DisplayName("should create via canonical constructor")
		void shouldCreateViaCanonicalConstructor() {
			final SessionCloseContainer container = new SessionCloseContainer(
				50L, SESSION_ID, 10, 11, 1L, CREATED,
				500, 20, 8192, 10, 3, 5, 3, 2, null
			);
			assertEquals(50L, container.sessionSequenceOrder());
			assertEquals(11, container.sessionRecordsCount());
			assertEquals(1L, container.catalogVersion());
			assertEquals(500, container.durationInMilliseconds());
			assertEquals(10, container.trafficRecordCount());
			assertEquals(3, container.trafficRecordsMissedOut());
		}

		@Test
		@DisplayName("should create via convenience constructor")
		void shouldCreateViaConvenienceConstructor() {
			final SessionCloseContainer container = new SessionCloseContainer(
				SESSION_ID, 10, 11, 1L, CREATED,
				500, 20, 8192, 10, 3, 5, 3, 2, null
			);
			assertNull(container.sessionSequenceOrder());
			assertEquals(500, container.durationInMilliseconds());
		}

		@Test
		@DisplayName("should store trafficRecordsMissedOut in convenience constructor")
		void shouldStoreTrafficRecordsMissedOut() {
			final SessionCloseContainer container = new SessionCloseContainer(
				SESSION_ID, 10, 11, 1L, CREATED,
				500, 20, 8192, 10, 5, 5, 3, 2, null
			);
			assertEquals(5, container.trafficRecordsMissedOut());
		}

		@Test
		@DisplayName("should store finishedWithError")
		void shouldStoreFinishedWithError() {
			final SessionCloseContainer container = new SessionCloseContainer(
				50L, SESSION_ID, 10, 11, 1L, CREATED,
				500, 20, 8192, 10, 3, 5, 3, 2, "session error"
			);
			assertEquals("session error", container.finishedWithError());
		}

		private SessionCloseContainer createSessionCloseContainer() {
			return new SessionCloseContainer(
				SESSION_ID, 10, 11, 1L, CREATED,
				500, 20, 8192, 10, 0, 5, 3, 2, null
			);
		}
	}
}
