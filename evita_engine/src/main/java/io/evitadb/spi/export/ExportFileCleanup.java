/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.spi.export;

import io.evitadb.api.file.FileForFetch;
import io.evitadb.spi.export.model.ExportFileHandle;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Removes what a failed export left in the export storage without replacing its original failure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ExportFileCleanup {

	/**
	 * Removes a failed export without ever replacing the reason it failed.
	 *
	 * A failed write leaves the handle open: `ZipOutputStream#close` skips `out.close()` if `finish()` throws. Neither
	 * the future nor `deleteFile` sees that file, so it has to be discarded. The published-file branch is reached only
	 * when a handle's own close failed after publishing, or an upload finished while a cancel landed in the task's
	 * wait. Its future is read by state, because an upload that failed completes it exceptionally and `getNow` would
	 * then replace the primary failure. A delete that fails is logged and attached to the primary failure when one is
	 * available, because a purge or a client may simply have removed the file first.
	 *
	 * @param handle        the export being written
	 * @param exportService the service holding the export
	 * @param description   what failed, for the cleanup log message
	 * @param failure       the reason the export failed, or null when no exception is available
	 */
	public static void abandon(
		@Nonnull ExportFileHandle handle,
		@Nonnull ExportService exportService,
		@Nonnull String description,
		@Nullable Exception failure
	) {
		if (!handle.discard()) {
			final CompletableFuture<FileForFetch> published = handle.fileForFetchFuture();
			if (published.state() == Future.State.SUCCESS) {
				final FileForFetch publishedFile = published.resultNow();
				try {
					exportService.deleteFile(publishedFile.fileId());
				} catch (RuntimeException cleanupFailure) {
					log.warn(
						"Failed to delete the published file `{}` of {}.",
						publishedFile.fileId(), description, cleanupFailure
					);
					if (failure != null) {
						failure.addSuppressed(cleanupFailure);
					}
				}
			}
		}
	}
}
