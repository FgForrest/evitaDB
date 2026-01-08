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

import io.evitadb.utils.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

/**
 * Basic implementation of {@link FileForFetch} interface.
 *
 * @param fileId            ID of the file.
 * @param name              Name of the file.
 * @param description       Optional short description of the file in human readable form.
 * @param contentType       MIME type of the file.
 * @param totalSizeInBytes  Total size of the file in bytes.
 * @param created           Date and time when the file was created.
 * @param origin            Optional origin of the file.
 * @param crc32             CRC32 checksum of the file content.
 * @param externallyManaged True if the file is externally managed and should not be automatically purged.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record BasicFileForFetch(
	@Nonnull UUID fileId,
	@Nonnull String name,
	@Nullable String description,
	@Nonnull String contentType,
	long totalSizeInBytes,
	@Nonnull OffsetDateTime created,
	@Nullable String[] origin,
	@Nullable String catalogName,
	long crc32,
	boolean externallyManaged
) implements FileForFetch, Serializable {

	public BasicFileForFetch {
		name = FileUtils.convertToSupportedName(name);
	}

	@Nonnull
	@Override
	public String toString() {
		return "FileForFetch{" +
			"fileId=" + this.fileId +
			", name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", contentType='" + this.contentType + '\'' +
			", totalSizeInBytes=" + this.totalSizeInBytes +
			", created=" + this.created +
			", origin=" + Arrays.toString(this.origin) +
			", catalogName='" + this.catalogName + '\'' +
			", crc32=" + this.crc32 +
			", externallyManaged=" + this.externallyManaged +
			'}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		FileForFetch that = (FileForFetch) o;
		return this.fileId.equals(that.fileId());
	}

	@Override
	public int hashCode() {
		return this.fileId.hashCode();
	}
}
