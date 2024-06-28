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

package io.evitadb.api.file;

import io.evitadb.api.task.TaskStatus;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.UUIDUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Record that represents single file stored in the server export file available for download.
 *
 * @param fileId           ID of the file.
 * @param name             Name of the file.
 * @param path             Path to the data file.
 * @param description      Optional short description of the file in human readable form.
 * @param contentType      MIME type of the file.
 * @param totalSizeInBytes Total size of the file in bytes.
 * @param created          Date and time when the file was created.
 * @param origin           Optional origin of the file. Usually {@link TaskStatus#taskType()}.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record FileForFetch(
	@Nonnull UUID fileId,
	@Nonnull String name,
	@Nonnull Path path,
	@Nullable String description,
	@Nonnull String contentType,
	long totalSizeInBytes,
	@Nonnull OffsetDateTime created,
	@Nullable String[] origin
) implements Serializable {

	public static final String METADATA_EXTENSION = ".metadata";

	/**
	 * Returns path to the metadata file.
	 * @return Path to the metadata file.
	 */
	@Nonnull
	public Path metadataPath() {
		return path.getParent().resolve(fileId + METADATA_EXTENSION);
	}

	/**
	 * Creates new instance of the record from the metadata lines.
	 * Might throw exception and in that case metadata file is corrupted.
	 *
	 * @param metadataLines   Lines of the metadata file.
	 * @param exportDirectory Directory where the file is stored.
	 * @return New instance of the record.
	 */
	@Nonnull
	public static FileForFetch fromLines(
		@Nonnull List<String> metadataLines,
		@Nonnull Path exportDirectory
	) {
		return new FileForFetch(
			UUIDUtil.uuid(metadataLines.get(0)),
			metadataLines.get(1),
			exportDirectory.resolve(metadataLines.get(2)),
			metadataLines.get(3),
			metadataLines.get(4),
			Long.parseLong(metadataLines.get(5)),
			OffsetDateTime.parse(metadataLines.get(6), DateTimeFormatter.ISO_OFFSET_DATE_TIME),
			metadataLines.get(7).split(",")
		);
	}

	/**
	 * Returns contents of the record written as set of lines.
	 */
	@Nonnull
	public List<String> toLines() {
		return Arrays.asList(
			fileId.toString(),
			name,
			fileId + FileUtils.getFileExtension(name).map(it -> "." + it).orElse(""),
			description,
			contentType,
			Long.toString(totalSizeInBytes),
			created.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
			origin == null ? "" : String.join(",", origin)
		);
	}
}
