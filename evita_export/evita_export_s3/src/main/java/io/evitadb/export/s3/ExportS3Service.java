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

package io.evitadb.export.s3;

import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.export.s3.configuration.S3ExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.OpenOption;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of {@link ExportService} that stores exported files in S3-compatible storage
 * (such as Amazon S3 or MinIO).
 *
 * This is a skeleton implementation that throws {@link UnsupportedOperationException} for all operations.
 * The actual S3 operations need to be implemented using the MinIO Java client.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class ExportS3Service implements ExportService {

	/**
	 * S3-specific export configuration options containing all settings.
	 */
	private final S3ExportOptions s3Options;

	/**
	 * Scheduler for background tasks.
	 */
	private final Scheduler scheduler;

	/**
	 * Creates a new instance of {@link ExportS3Service}.
	 *
	 * @param exportOptions the export configuration options
	 * @param scheduler     the scheduler for background tasks
	 */
	public ExportS3Service(@Nonnull ExportOptions exportOptions, @Nonnull Scheduler scheduler) {
		if (!(exportOptions instanceof S3ExportOptions)) {
			throw new IllegalArgumentException(
				"ExportS3Service requires S3ExportOptions but got: " + exportOptions.getClass().getSimpleName()
			);
		}
		this.s3Options = (S3ExportOptions) exportOptions;
		this.scheduler = scheduler;
	}

	@Nonnull
	@Override
	public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin) {
		throw new UnsupportedOperationException("S3 export service is not yet implemented.");
	}

	@Nonnull
	@Override
	public Optional<FileForFetch> getFile(@Nonnull UUID fileId) {
		throw new UnsupportedOperationException("S3 export service is not yet implemented.");
	}

	@Nonnull
	@Override
	public ExportFileHandle storeFile(
		@Nonnull String fileName,
		@Nullable String description,
		@Nonnull String contentType,
		@Nullable String origin
	) {
		throw new UnsupportedOperationException("S3 export service is not yet implemented.");
	}

	@Nonnull
	@Override
	public InputStream fetchFile(@Nonnull UUID fileId) {
		throw new UnsupportedOperationException("S3 export service is not yet implemented.");
	}

	@Override
	public void deleteFile(@Nonnull UUID fileId) {
		throw new UnsupportedOperationException("S3 export service is not yet implemented.");
	}

	@Override
	public long purgeFiles() {
		throw new UnsupportedOperationException("S3 export service is not yet implemented.");
	}

	@Override
	public void purgeFiles(@Nonnull OffsetDateTime thresholdDate) {
		throw new UnsupportedOperationException("S3 export service is not yet implemented.");
	}

	@Override
	public void writeFileMetadata(@Nonnull FileForFetch fileForFetch, @Nonnull OpenOption... options) {
		throw new UnsupportedOperationException("S3 export service is not yet implemented.");
	}

	@Override
	public void close() throws IOException {
		// No resources to close in skeleton implementation
	}

}
