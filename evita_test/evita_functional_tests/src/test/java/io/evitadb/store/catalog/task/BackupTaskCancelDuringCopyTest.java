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
import io.evitadb.spi.export.model.ExportFileHandle;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies cancellation during the point-in-time backup's record copy with real storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(TASK)
@Tag(EXPORT)
@DisplayName("Backup cancellation during record copy")
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

	/** Allocates a real export handle and wraps only its write stream. */
	private static class CancellingExportService implements ExportService {
		@Delegate private final ExportService delegate;
		private final Runnable cancellation;

		/** Creates a service with a deterministic cancellation hook. */
		private CancellingExportService(@Nonnull ExportService delegate, @Nonnull Runnable cancellation) {
			this.delegate = delegate;
			this.cancellation = cancellation;
		}

		/** {@inheritDoc} */
		@Nonnull
		@Override
		public ExportFileHandle storeFile(
			@Nonnull String fileName, @Nullable String description, @Nonnull String contentType, @Nullable String origin
		) {
			return new CancellingHandle(
				this.delegate.storeFile(fileName, description, contentType, origin), this.cancellation
			);
		}
	}

	/** Preserves the real handle lifecycle and cancels on its first stream write. */
	private static class CancellingHandle implements ExportFileHandle {
		@Delegate private final ExportFileHandle delegate;
		private final Runnable cancellation;

		/** Wraps the real handle with a cancellation hook. */
		private CancellingHandle(@Nonnull ExportFileHandle delegate, @Nonnull Runnable cancellation) {
			this.delegate = delegate;
			this.cancellation = cancellation;
		}

		/** {@inheritDoc} */
		@Nonnull
		@Override
		public OutputStream outputStream() {
			return new FilterOutputStream(this.delegate.outputStream()) {
				private boolean cancelled;

				/** {@inheritDoc} */
				@Override
				public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
					if (!this.cancelled) {
						this.cancelled = true;
						CancellingHandle.this.cancellation.run();
					}
					this.out.write(bytes, offset, length);
				}
			};
		}
	}
}
