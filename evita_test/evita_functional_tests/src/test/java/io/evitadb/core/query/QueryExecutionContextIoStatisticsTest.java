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

package io.evitadb.core.query;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.buffer.StorageAccessScope;
import io.evitadb.core.query.filter.translator.TestQueryExecutionContext;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a query execution reports the I/O **it** performed, and not the I/O of the execution it is nested in.
 *
 * A nested plan runs on the same thread and therefore joins the {@link StorageAccessScope} the outer execution opened
 * rather than starting one of its own - the counters it finds there already hold everything the outer query read before
 * the nested one started.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(QUERY)
@DisplayName("I/O statistics of a query execution")
class QueryExecutionContextIoStatisticsTest {

	/**
	 * Minimal storage part - the accounting never looks inside a record, it only bills it.
	 *
	 * @param primaryKey numeric key the part would be stored under
	 */
	private record TestStoragePart(long primaryKey) implements StoragePart {
		@Serial private static final long serialVersionUID = 4_878_222_640_312_940_233L;

		@Nullable
		@Override
		public Long getStoragePartPK() {
			return this.primaryKey;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			return this.primaryKey;
		}
	}

	/**
	 * Builds an execution context over an empty product query - the statistics are accumulated by the scope and never
	 * consult the query itself.
	 */
	@Nonnull
	private static TestQueryExecutionContext executionContext() {
		return new TestQueryExecutionContext(
			Mockito.mock(EntitySchemaContract.class),
			Query.query(collection(Entities.PRODUCT)),
			Map.of()
		);
	}

	/**
	 * No test may leave a scope bound to the thread - the surefire fork reuses it for the next test class.
	 */
	@AfterEach
	void releaseLeakedScopes() {
		StorageAccessScope leaked = StorageAccessScope.getIfActive();
		while (leaked != null) {
			leaked.close();
			leaked = StorageAccessScope.getIfActive();
		}
	}

	@Test
	@DisplayName("an execution reports the reads it performed itself")
	void shouldReportItsOwnReads() {
		try (final QueryExecutionContext context = executionContext()) {
			context.openStorageAccessScope();
			StorageAccessScope.noteRecordRead(new TestStoragePart(1L), 100);
			StorageAccessScope.noteRecordRead(new TestStoragePart(2L), 50);

			assertEquals(2, context.getIoFetchCount());
			assertEquals(150, context.getIoFetchedBytes());
		}
	}

	@Test
	@DisplayName("a nested execution does not report the reads of the one it is nested in")
	void shouldNotReportOuterReadsFromNestedExecution() {
		try (final QueryExecutionContext outer = executionContext()) {
			outer.openStorageAccessScope();
			StorageAccessScope.noteRecordRead(new TestStoragePart(1L), 100);
			StorageAccessScope.noteRecordRead(new TestStoragePart(2L), 50);

			try (final QueryExecutionContext nested = executionContext()) {
				// joins the scope the outer execution opened, which already holds the two reads above
				nested.openStorageAccessScope();
				StorageAccessScope.noteRecordRead(new TestStoragePart(3L), 25);

				assertEquals(1, nested.getIoFetchCount());
				assertEquals(25, nested.getIoFetchedBytes());
			}

			// and the outer execution still owns everything read within it, the nested read included
			assertEquals(3, outer.getIoFetchCount());
			assertEquals(175, outer.getIoFetchedBytes());
		}
	}

	@Test
	@DisplayName("an execution that never opened a scope reports nothing")
	void shouldReportZeroWithoutScope() {
		try (final QueryExecutionContext context = executionContext()) {
			assertEquals(0, context.getIoFetchCount());
			assertEquals(0, context.getIoFetchedBytes());
		}
	}
}
