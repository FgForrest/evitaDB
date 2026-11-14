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

package io.evitadb.store.catalog.task;

import io.evitadb.spi.export.ExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies cancellation during point-in-time and full backup copies with real storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(TASK)
@Tag(EXPORT)
@DisplayName("Backup cancellation during copy")
class BackupTaskCancelDuringCopyTest extends BackupTaskExportFixture {

	/**
	 * Cancels at the first archive write, while the fixture's large record is still being copied.
	 */
	@Test
	@DisplayName("Should report cancellation when interrupted during record copy")
	void shouldReportCancellationWhenInterruptedDuringRecordCopy() throws IOException {
		final AtomicReference<BackupTask> reference = new AtomicReference<>();
		final ExportService cancellingExports = new CancellingExportService(this.exports, () -> {
			assertTrue(reference.get().cancel());
			// A real cancellation interrupts the worker after cancelling its future.
			Thread.currentThread().interrupt();
		});
		final BackupTask task = new BackupTask(
			TEST_CATALOG, null, null, false, bootstrap(), cancellingExports, this.persistence, null
		);
		reference.set(task);
		task.transitionToIssued();
		try {
			assertDoesNotThrow(task::execute);
			assertTrue(task.getFutureResult().isCancelled());
			assertEquals("Task was cancelled.", task.getStatus().publicExceptionMessage());
			assertEquals(0, this.exports.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount());
		} finally {
			Thread.interrupted();
		}
	}

	/**
	 * Cancels at the first archive write. `Files.copy` reads through an uninterruptible channel, so the file in progress
	 * finishes and the progress checkpoint after it unwinds the backup.
	 */
	@Test
	@DisplayName("Should report cancellation when interrupted during full backup file copy")
	void shouldReportCancellationWhenInterruptedDuringFullBackupFileCopy() {
		final AtomicReference<FullBackupTask> reference = new AtomicReference<>();
		final ExportService cancellingExports = new CancellingExportService(this.exports, () -> {
			assertTrue(reference.get().cancel());
			Thread.currentThread().interrupt();
		});
		final FullBackupTask task = new FullBackupTask(TEST_CATALOG, cancellingExports, this.persistence, null);
		reference.set(task);
		task.transitionToIssued();
		try {
			assertDoesNotThrow(task::execute);
			assertAll(
				() -> assertTrue(task.getFutureResult().isCancelled()),
				() -> assertEquals("Task was cancelled.", task.getStatus().publicExceptionMessage()),
				() -> assertEquals(0, this.exports.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount())
			);
		} finally {
			Thread.interrupted();
		}
	}

}
