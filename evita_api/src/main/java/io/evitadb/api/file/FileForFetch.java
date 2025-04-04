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
	@Nullable String description,
	@Nonnull String contentType,
	long totalSizeInBytes,
	@Nonnull OffsetDateTime created,
	@Nullable String[] origin
) implements Serializable {
	public static final String METADATA_EXTENSION = ".metadata";

	public FileForFetch(
		@Nonnull UUID fileId,
		@Nonnull String name,
		@Nullable String description,
		@Nonnull String contentType,
		long totalSizeInBytes,
		@Nonnull OffsetDateTime created,
		@Nullable String[] origin
	) {
		this.fileId = fileId;
		this.name = FileUtils.convertToSupportedName(name);
		this.description = description;
		this.contentType = contentType;
		this.totalSizeInBytes = totalSizeInBytes;
		this.created = created;
		this.origin = origin;
	}

	/**
	 * Returns path to the metadata file in target directory.
	 *
	 * @param directory Target directory.
	 * @return Path to the metadata file.
	 */
	@Nonnull
	public Path metadataPath(@Nonnull Path directory) {
		return directory.resolve(fileId + METADATA_EXTENSION);
	}

	/**
	 * Returns path to the file contents in target directory.
	 *
	 * @param directory Target directory.
	 * @return Path to the file contents in target directory.
	 */
	@Nonnull
	public Path path(@Nonnull Path directory) {
		return directory.resolve(fileId + FileUtils.getFileExtension(name).map(it -> "." + it).orElse(""));
	}

	/**
	 * Creates new instance of the record from the metadata lines.
	 * Might throw exception and in that case metadata file is corrupted.
	 *
	 * @param metadataLines   Lines of the metadata file.
	 * @return New instance of the record.
	 */
	@Nonnull
	public static FileForFetch fromLines(@Nonnull List<String> metadataLines) {
		return new FileForFetch(
			UUIDUtil.uuid(metadataLines.get(0)),
			metadataLines.get(1),
			metadataLines.get(2),
			metadataLines.get(3),
			Long.parseLong(metadataLines.get(4)),
			OffsetDateTime.parse(metadataLines.get(5), DateTimeFormatter.ISO_OFFSET_DATE_TIME),
			metadataLines.get(6).split(",")
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
			description,
			contentType,
			Long.toString(totalSizeInBytes),
			created.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
			origin == null ? "" : String.join(",", origin)
		);
	}

	@Override
	public String toString() {
		return "FileForFetch{" +
			"fileId=" + fileId +
			", name='" + name + '\'' +
			", description='" + description + '\'' +
			", contentType='" + contentType + '\'' +
			", totalSizeInBytes=" + totalSizeInBytes +
			", created=" + created +
			", origin=" + Arrays.toString(origin) +
			'}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		FileForFetch that = (FileForFetch) o;
		return fileId.equals(that.fileId);
	}

	@Override
	public int hashCode() {
		return fileId.hashCode();
	}
}
