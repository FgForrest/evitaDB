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

package io.evitadb.api.file;

import io.evitadb.api.task.TaskStatus;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Record that represents single file stored in the server export file available for download.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface FileForFetch extends Serializable {
	/**
	 * Returns ID of the file.
	 */
	@Nonnull
	UUID fileId();

	/**
	 * Returns name of the file.
	 */
	@Nonnull
	String name();

	/**
	 * Returns optional short description of the file in human readable form.
	 */
	@Nullable
	String description();

	/**
	 * Returns MIME type of the file.
	 */
	@Nonnull
	String contentType();

	/**
	 * Returns total size of the file in bytes.
	 */
	long totalSizeInBytes();

	/**
	 * Returns date and time when the file was created.
	 */
	@Nonnull
	OffsetDateTime created();

	/**
	 * Returns optional origin of the file. Usually {@link TaskStatus#taskType()}.
	 */
	@Nullable
	String[] origin();

	/**
	 * Returns optional catalog name.
	 */
	@Nullable
	String catalogName();

	/**
	 * Returns CRC32 checksum of the file content.
	 */
	long crc32();

	/**
	 * Returns true if the file is externally managed and should not be automatically purged
	 * due to age or size constraints. Such files can only be removed via explicit
	 * {@link io.evitadb.spi.export.ExportService#deleteFile(java.util.UUID)} calls.
	 * They still count towards the maximum total file size limit.
	 */
	boolean externallyManaged();

}
