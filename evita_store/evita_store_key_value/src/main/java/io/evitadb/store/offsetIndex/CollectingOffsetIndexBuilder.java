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

package io.evitadb.store.offsetIndex;

import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.RecordTypeUsage;
import io.evitadb.store.shared.model.FileLocation;
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
	private final ConcurrentHashMap<Byte, RecordTypeUsage> histogram = CollectionUtils.createConcurrentHashMap(
		OffsetIndex.HISTOGRAM_INITIAL_CAPACITY
	);
	private long totalSizeBytes;
	private int maxSizeBytes;

	@Override
	public void register(@Nonnull RecordKey recordKey, @Nonnull FileLocation fileLocation) {
		final FileLocation previousValue = this.builtIndex.put(recordKey, fileLocation);
		// every branch below feeds the per-type byte accumulator with exactly the delta it feeds `totalSizeBytes`,
		// including the count-neutral one - that is what makes `Σ histogram bytes == totalSizeBytes` true by
		// construction rather than by coincidence
		if (previousValue == null) {
			addUsage(recordKey.recordType(), 1, fileLocation.recordLength());
			this.totalSizeBytes += fileLocation.recordLength();
		} else if (recordKey.recordType() < 0) {
			addUsage(recordKey.recordType(), -1, -fileLocation.recordLength());
			this.totalSizeBytes -= fileLocation.recordLength();
		} else {
			addUsage(recordKey.recordType(), 0, fileLocation.recordLength() - previousValue.recordLength());
			this.totalSizeBytes += fileLocation.recordLength() - previousValue.recordLength();
		}
		if (this.maxSizeBytes < fileLocation.recordLength()) {
			this.maxSizeBytes = fileLocation.recordLength();
		}
	}

	/**
	 * Folds a signed `(count, bytes)` delta into the histogram entry of `recordType`. Written as a get/put pair rather
	 * than {@link ConcurrentHashMap#merge(Object, Object, java.util.function.BiFunction)} to keep the allocation
	 * count per registered record at one: the builder is filled by the single deserializing thread, which is the same
	 * assumption the plain `long` accumulators above already make.
	 *
	 * @param recordType  the record type whose usage to adjust
	 * @param countDelta  how many records of that type were added (negative when removed)
	 * @param bytesDelta  how many bytes those records added (negative when removed or replaced by a smaller record)
	 */
	private void addUsage(byte recordType, int countDelta, long bytesDelta) {
		final RecordTypeUsage existing = this.histogram.get(recordType);
		this.histogram.put(
			recordType,
			existing == null ?
				new RecordTypeUsage(countDelta, bytesDelta) :
				new RecordTypeUsage(existing.count() + countDelta, existing.totalBytes() + bytesDelta)
		);
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
