/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.offsetIndex.model;

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This DTO allows to keep the object that was written to the {@link OffsetIndex} but its location
 * was not yet flushed to the disk.
 *
 * @param primaryKey     Contains primary key of the stored container.
 * @param recordType     Contains type of the record stored on specified position.
 * @param fileLocation   Contains coordinates to the space in the file that is occupied by this record.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record VersionedValue(
	long primaryKey,
	byte recordType,
	@Nonnull FileLocation fileLocation
) implements Serializable {
	@Serial private static final long serialVersionUID = -4467999274212489366L;
	public static final long MEMORY_SIZE = 2 * MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
		MemoryMeasuringConstants.LONG_SIZE +
		MemoryMeasuringConstants.BYTE_SIZE +
		MemoryMeasuringConstants.LONG_SIZE +
		MemoryMeasuringConstants.INT_SIZE;

	/**
	 * Returns true if this non-flushed value represents removal of the record.
	 */
	public boolean removed() {
		return this.recordType < 0;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		VersionedValue that = (VersionedValue) o;
		return this.primaryKey == that.primaryKey &&
			Math.abs(this.recordType) == Math.abs(that.recordType) &&
			this.fileLocation.equals(that.fileLocation);
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + Long.hashCode(this.primaryKey);
		result = 31 * result + Byte.hashCode(this.recordType < 0 ? (byte) (this.recordType * -1) : this.recordType);
		result = 31 * result + this.fileLocation.hashCode();
		return result;
	}
}
