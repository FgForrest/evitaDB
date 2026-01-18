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

package io.evitadb.store.model.reference;

import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.store.shared.model.CatalogVariableContentFileReference;
import io.evitadb.store.shared.model.FileLocation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * A class representing a reference to a WAL (Write-Ahead Log) file.
 *
 * @param walFileNameProvider Lambda function that provides the name of the WAL file based on the file index.
 * @param fileIndex           The index of the WAL file incremented each time the WAL file is rotated.
 * @param fileLocation        The location of the last processed transaction of the WAL file.
 * @param cumulativeChecksum  The cumulative CRC32C checksum computed over all bytes written to the WAL file
 *                            from the beginning up to the end of this transaction. This checksum is used to
 *                            verify WAL integrity and detect corruption.
 */
public record LogFileRecordReference(
	@Nonnull IntFunction<String> walFileNameProvider,
	int fileIndex,
	@Nullable FileLocation fileLocation,
	long cumulativeChecksum
) implements LogRecordReference, CatalogVariableContentFileReference {

	/**
	 * Constructor representing initial version of the WAL file reference with zero cumulative checksum.
	 *
	 * @param walFileNameProvider Lambda function that provides the name of the WAL file based on the file index.
	 */
	public LogFileRecordReference(
		@Nonnull IntFunction<String> walFileNameProvider
	) {
		this(walFileNameProvider, 0, null, 0L);
	}

	@Override
	@Nonnull
	public Path toFilePath(@Nonnull Path storageFolder) {
		return storageFolder.resolve(
			this.walFileNameProvider.apply(this.fileIndex)
		);
	}

	@Override
	@Nonnull
	public LogFileRecordReference incrementAndGet() {
		return new LogFileRecordReference(this.walFileNameProvider, this.fileIndex + 1, null, this.cumulativeChecksum);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final LogFileRecordReference that)) return false;

		return this.fileIndex == that.fileIndex &&
			this.cumulativeChecksum == that.cumulativeChecksum &&
			Objects.equals(this.fileLocation, that.fileLocation);
	}

	@Override
	public int hashCode() {
		int result = this.fileIndex;
		result = 31 * result + Objects.hashCode(this.fileLocation);
		result = 31 * result + Long.hashCode(this.cumulativeChecksum);
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return "LogFileRecordReference{" +
			"fileIndex=" + this.fileIndex +
			", location=" + this.fileLocation +
			", cumulativeChecksum=" + this.cumulativeChecksum +
			'}';
	}
}
