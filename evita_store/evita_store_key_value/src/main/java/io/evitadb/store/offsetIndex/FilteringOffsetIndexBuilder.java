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
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class is used to build initial OffsetIndex in {@link OffsetIndexSerializationService} and switch atomically
 * the real (operative) OffsetIndex contents atomically once it's done.
 */
@RequiredArgsConstructor
class FilteringOffsetIndexBuilder implements OffsetIndexBuilder {
	private final RecordKey filteredKey;
	private final AtomicReference<FileLocation> filteredLocation = new AtomicReference<>();

	/**
	 * Compares two record keys and returns true if they're practically equals. The implementation ignores the sign of
	 * the removed record type (negativity).
	 *
	 * @param keyA Record key A
	 * @param keyB Record key B
	 * @return True if the keys are practically equals.
	 */
	private static boolean equals(@Nonnull RecordKey keyA, @Nonnull RecordKey keyB) {
		final int result = Byte.compare(abs(keyA.recordType()), abs(keyB.recordType()));
		return (result == 0 ? Long.compare(keyA.primaryKey(), keyB.primaryKey()) : result) == 0;
	}

	/**
	 * Returns the absolute value of the given byte.
	 * @param value Byte value
	 * @return Absolute value
	 */
	private static byte abs(byte value) {
		return value < 0 ? (byte) -value : value;
	}

	@Override
	public void register(@Nonnull RecordKey recordKey, @Nonnull FileLocation fileLocation) {
		// overwrite the filtered key and location
		if (equals(this.filteredKey, recordKey)) {
			this.filteredLocation.set(recordKey.recordType() < 0 ? null : fileLocation);
		}
	}

	@Override
	public boolean contains(@Nonnull RecordKey recordKey) {
		return recordKey.equals(this.filteredKey) && this.filteredLocation.get() != null;
	}

	@Nonnull
	@Override
	public ConcurrentHashMap<RecordKey, FileLocation> getBuiltIndex() {
		return this.filteredLocation.get() == null ?
			new ConcurrentHashMap<>(0) :
			new ConcurrentHashMap<>(
				Map.of(this.filteredKey, this.filteredLocation.get())
			);
	}

	@Nonnull
	@Override
	public ConcurrentHashMap<Byte, Integer> getHistogram() {
		return this.filteredLocation.get() == null ?
			new ConcurrentHashMap<>(0) :
			new ConcurrentHashMap<>(this.filteredKey.recordType(), 1);
	}

	@Override
	public long getTotalSizeBytes() {
		return this.filteredLocation.get() == null ?
			0 : this.filteredLocation.get().recordLength();
	}

	@Override
	public int getMaxSizeBytes() {
		return this.filteredLocation.get() == null ?
			0 : this.filteredLocation.get().recordLength();
	}

	@Nonnull
	@Override
	public Optional<FileLocation> getFileLocationFor(@Nonnull RecordKey recordKey) {
		return recordKey.equals(this.filteredKey) ? Optional.ofNullable(this.filteredLocation.get()) : Optional.empty();
	}
}
