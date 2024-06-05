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
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class is used to build initial OffsetIndex in {@link OffsetIndexSerializationService} and switch atomically
 * the real (operative) OffsetIndex contents atomically once it's done.
 */
@Getter
class CollectingOffsetIndexBuilder implements OffsetIndexBuilder {
	private final ConcurrentHashMap<RecordKey, FileLocation> builtIndex = CollectionUtils.createConcurrentHashMap(OffsetIndex.KEY_HASH_MAP_INITIAL_SIZE);
	private final ConcurrentHashMap<Byte, Integer> histogram = CollectionUtils.createConcurrentHashMap(OffsetIndex.HISTOGRAM_INITIAL_CAPACITY);
	private long totalSizeBytes;
	private int maxSizeBytes;

	@Override
	public void register(@Nonnull RecordKey recordKey, @Nonnull FileLocation fileLocation) {
		final FileLocation previousValue = this.builtIndex.put(recordKey, fileLocation);
		if (previousValue == null) {
			this.histogram.merge(recordKey.recordType(), 1, Integer::sum);
			this.totalSizeBytes += fileLocation.recordLength();
		} else if (recordKey.recordType() < 0) {
			this.histogram.merge(recordKey.recordType(), -1, Integer::sum);
			this.totalSizeBytes -= fileLocation.recordLength();
		} else {
			this.totalSizeBytes += fileLocation.recordLength() - previousValue.recordLength();
		}
		if (this.maxSizeBytes < fileLocation.recordLength()) {
			this.maxSizeBytes = fileLocation.recordLength();
		}
	}

	@Override
	public boolean contains(@Nonnull RecordKey recordKey) {
		return this.builtIndex.containsKey(recordKey);
	}

	@Nonnull
	@Override
	public Optional<FileLocation> getFileLocationFor(@Nonnull RecordKey recordKey) {
		return Optional.ofNullable(this.builtIndex.get(recordKey));
	}

}
