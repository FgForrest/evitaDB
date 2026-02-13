/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.UUID;

/**
 * Exception thrown when attempting to access a file that does not exist in the export service.
 *
 * This exception is raised when operations reference files by UUID that are not registered in evitaDB's export
 * file tracking system. The export service maintains a registry of files available for fetch (e.g., backup files,
 * export snapshots, task results), and this exception indicates the requested file is not in that registry.
 *
 * **When this exception occurs:**
 *
 * - **File fetching** via `fetchFile(UUID)` when the file ID is not registered
 * - **File deletion** via `deleteFile(UUID)` when the file does not exist
 * - **File metadata retrieval** when the file has been deleted or never existed
 * - **Remote file operations** over gRPC when the client requests a non-existent file
 *
 * **Common causes:**
 *
 * - File was already deleted but client still holds the UUID
 * - File expired and was automatically cleaned up by retention policies
 * - UUID was never valid (typo, or file creation failed)
 * - File exists in storage but metadata was lost (storage inconsistency)
 *
 * **Context:**
 *
 * Files tracked by the export service are typically stored in:
 * - Local filesystem (via `ExportFileService`)
 * - S3-compatible storage (via `ExportS3Service`)
 *
 * **Resolution**: Verify the file ID is correct, check if the file has expired or been deleted, or regenerate
 * the file if it was a transient export/backup result.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class FileForFetchNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 6188715534953413955L;
	/**
	 * The UUID of the file that could not be found.
	 */
	@Getter private final UUID fileId;

	/**
	 * Creates exception identifying the missing file by its unique identifier.
	 *
	 * @param fileId the UUID of the file that was not found
	 */
	public FileForFetchNotFoundException(@Nonnull UUID fileId) {
		super(
			"File not found: " + fileId,
			"File not found."
		);
		this.fileId = fileId;
	}
}
