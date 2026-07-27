/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.store.catalog;

import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.catalog.ObsoleteFileMaintainer.DataFilesBulkInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the active-reader floor tracking exposed by {@link ObsoleteFileMaintainer} through its public API.
 *
 * The active-reader floor is the minimal catalog version that is still referenced by an active reader (open
 * session) or writer. It is recorded monotonically by {@link ObsoleteFileMaintainer#catalogConsumersLeft(long, long)}
 * and read back via {@link ObsoleteFileMaintainer#getActiveReaderFloor()}. The floor is tracked regardless of
 * whether time travel is enabled, because the WAL-rotation purge is clamped by it so that a catalog data file
 * still needed by an active reader is never deleted.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Obsolete file maintainer active-reader floor tracking")
@Tag(STORAGE)
@Tag(WAL)
class ObsoleteFileMaintainerTest {

	/**
	 * Catalog name used for the maintainer under test; the value is irrelevant to floor tracking.
	 */
	private static final String CATALOG_NAME = "testCatalog";

	/**
	 * No-op supplier of oldest data file info; the floor logic never consults it.
	 */
	private static final Supplier<DataFilesBulkInfo> NO_OP_SUPPLIER = () -> null;

	/**
	 * Executor backing the scheduler; shut down after each test.
	 */
	private ScheduledThreadPoolExecutor executor;

	/**
	 * Scheduler handed to the maintainer; the floor logic never schedules work in these tests.
	 */
	private Scheduler scheduler;

	@TempDir
	private Path catalogStoragePath;

	@BeforeEach
	void setUp() {
		this.executor = new ScheduledThreadPoolExecutor(1);
		this.scheduler = new Scheduler(this.executor);
	}

	@AfterEach
	void tearDown() {
		this.executor.shutdownNow();
	}

	@Test
	@DisplayName("Floor is zero before any consumers-left notification")
	void shouldReportZeroFloorBeforeAnyConsumersLeftCall() {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
			assertEquals(0L, maintainer.getActiveReaderFloor());
		}
	}

	@Test
	@DisplayName("Single notification records the minimum of read and written versions")
	void shouldRecordMinimumOfReadAndWrittenVersions() {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
			maintainer.catalogConsumersLeft(5L, 3L);

			assertEquals(3L, maintainer.getActiveReaderFloor());
		}
	}

	@Test
	@DisplayName("Floor accumulates monotonically and never decreases")
	void shouldAccumulateFloorMonotonically() {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
			maintainer.catalogConsumersLeft(5L, 5L);
			assertEquals(5L, maintainer.getActiveReaderFloor());

			// a lower minimum must not lower the floor
			maintainer.catalogConsumersLeft(2L, 2L);
			assertEquals(5L, maintainer.getActiveReaderFloor());

			// a higher minimum raises the floor
			maintainer.catalogConsumersLeft(9L, 7L);
			assertEquals(7L, maintainer.getActiveReaderFloor());
		}
	}

	@Test
	@DisplayName("Notification whose minimum is zero or negative leaves the floor unchanged")
	void shouldIgnoreNonPositiveMinimum() {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(false)) {
			maintainer.catalogConsumersLeft(4L, 4L);
			assertEquals(4L, maintainer.getActiveReaderFloor());

			// minimum is zero -> guarded out
			maintainer.catalogConsumersLeft(8L, 0L);
			assertEquals(4L, maintainer.getActiveReaderFloor());

			// minimum is negative -> guarded out
			maintainer.catalogConsumersLeft(-1L, 8L);
			assertEquals(4L, maintainer.getActiveReaderFloor());
		}
	}

	@ParameterizedTest(name = "timeTravelEnabled={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("Floor accumulation is identical regardless of time-travel mode")
	void shouldAccumulateFloorIdenticallyInBothTimeTravelModes(boolean timeTravelEnabled) {
		try (ObsoleteFileMaintainer maintainer = newMaintainer(timeTravelEnabled)) {
			assertEquals(0L, maintainer.getActiveReaderFloor());

			maintainer.catalogConsumersLeft(6L, 4L);
			assertEquals(4L, maintainer.getActiveReaderFloor());

			// lower minimum does not lower the floor in either mode
			maintainer.catalogConsumersLeft(3L, 3L);
			assertEquals(4L, maintainer.getActiveReaderFloor());

			// higher minimum raises the floor in either mode
			maintainer.catalogConsumersLeft(10L, 8L);
			assertEquals(8L, maintainer.getActiveReaderFloor());
		}
	}

	/**
	 * Builds an {@link ObsoleteFileMaintainer} bound to the per-test scheduler and temporary storage path.
	 *
	 * @param timeTravelEnabled whether the maintainer should operate in time-travel mode
	 * @return a freshly constructed maintainer that the caller is responsible for closing
	 */
	@Nonnull
	private ObsoleteFileMaintainer newMaintainer(boolean timeTravelEnabled) {
		return new ObsoleteFileMaintainer(
			CATALOG_NAME,
			this.scheduler,
			this.catalogStoragePath,
			timeTravelEnabled,
			NO_OP_SUPPLIER
		);
	}

}
