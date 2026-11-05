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

package io.evitadb.store.catalog.task;

import io.evitadb.api.file.FileForFetch;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * The part of the failure path both backup tasks share: removing what a failed backup left in the export storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BackupExportCleanup {

	/**
	 * Removes the export of a failed backup, without ever replacing the reason the backup failed.
	 *
	 * A failed write leaves the handle open: `ZipOutputStream#close` skips `out.close()` if `finish()` throws. Neither
	 * the future nor `deleteFile` sees that file, so it has to be discarded. What is left is a file already published.
	 * Its future is read by state, because an upload that failed completes it exceptionally and `getNow` would then
	 * replace the primary failure. A delete that fails is only attached to the primary failure, because a purge or a
	 * client may simply have removed the file first.
	 *
	 * @param handle        the export the backup was writing
	 * @param exportService the service holding the export
	 * @param catalogName   the catalog being backed up
	 * @param failure       the reason the backup failed
	 */
	static void abandon(
		@Nonnull ExportFileHandle handle,
		@Nonnull ExportService exportService,
		@Nonnull String catalogName,
		@Nonnull Exception failure
	) {
		if (!handle.discard()) {
			final CompletableFuture<FileForFetch> published = handle.fileForFetchFuture();
			if (published.state() == Future.State.SUCCESS) {
				final FileForFetch publishedFile = published.resultNow();
				try {
					exportService.deleteFile(publishedFile.fileId());
				} catch (RuntimeException cleanupFailure) {
					log.warn(
						"Failed to delete the published file `{}` of a failed backup of catalog `{}`.",
						publishedFile.fileId(), catalogName, cleanupFailure
					);
					failure.addSuppressed(cleanupFailure);
				}
			}
		}
	}
}
