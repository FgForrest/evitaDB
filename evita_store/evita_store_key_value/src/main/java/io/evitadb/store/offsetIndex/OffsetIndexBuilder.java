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

package io.evitadb.store.offsetIndex;

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.RecordKey;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interface used in {@link OffsetIndex} to build the index of {@link FileLocation} for respective {@link RecordKey}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface OffsetIndexBuilder {

	/**
	 * Registers a record key with its corresponding file location in the built index and updates the histogram
	 * and total size.
	 *
	 * @param recordKey    The record key to register.
	 * @param fileLocation The file location associated with the record key.
	 */
	void register(@Nonnull RecordKey recordKey, @Nonnull FileLocation fileLocation);

	/**
	 * Checks if the specified record key is contained in the built index.
	 *
	 * @param recordKey The record key to check.
	 * @return `true` if the built index contains the record key, `false` otherwise.
	 */
	boolean contains(@Nonnull RecordKey recordKey);

	/**
	 * Returns the file location for the specified record key.
	 *
	 * @param recordKey The record key to get the file location for.
	 * @return The file location for the specified record key.
	 */
	@Nonnull
	Optional<FileLocation> getFileLocationFor(@Nonnull RecordKey recordKey);

	/**
	 * Returns the built index. We return here the specific map implementation to avoid unnecessary copying.
	 *
	 * @return The built index.
	 */
	@Nonnull
	ConcurrentHashMap<RecordKey, FileLocation> getBuiltIndex();

	/**
	 * Returns the histogram of record types in the built index. The histogram is a map from {@link RecordKey#recordType()}
	 * to the number of occurrences of the record type in the built index. We return here the specific map implementation
	 * to avoid unnecessary copying.
	 *
	 * @return The histogram of record types in the built index.
	 */
	@Nonnull
	ConcurrentHashMap<Byte, Integer> getHistogram();

	/**
	 * Returns total size of all active records in the built index on disk.
	 *
	 * @return Total size of all active records in the built index on disk.
	 */
	long getTotalSizeBytes();

	/**
	 * Returns the size of the largest record in the built index.
	 *
	 * @return The size of the largest record in the built index.
	 */
	int getMaxSizeBytes();

}
