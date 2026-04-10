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

package io.evitadb.spi.store.catalog.persistence.storageParts;

import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityStoragePart;

/**
 * Marks {@link StoragePart} implementations (or their payload types) whose {@link StoragePart#getStoragePartPK()
 * primary key} is derived with the help of a {@link KeyCompressor}. When a part is written for the first time the
 * compressor maps the source key (e.g. an {@link io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey})
 * to a compact integer id, and the integer is then combined with other identifiers to produce the final 64-bit
 * storage part primary key.
 *
 * The complication this interface addresses is that the compressor mapping may still reside in volatile memory (not
 * yet flushed to disk) at the moment the part needs to be located by key. The {@link #getStoragePartSourceKey()}
 * method provides the original, pre-compression key so that callers can reconstruct the primary key once the
 * compressor state is available, without needing thread-safe access to the compressor at arbitrary times.
 *
 * @param <T> the type of the source key; must implement both {@link Comparable} and have consistent `equals`/`hashCode`
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see io.evitadb.core.buffer.DataStoreMemoryBuffer
 */
public interface RecordWithCompressedId<T extends Comparable<T>> {

	/**
	 * Returns the original, uncompressed source key from which the storage part primary key is computed. The key
	 * must uniquely identify this record among all records of the same type so that
	 * {@link StoragePart#getStoragePartPK()} can be fully reconstructed from it (given a {@link KeyCompressor}).
	 *
	 * Implementations must ensure proper `equals` and `hashCode` contracts on the returned key, as it is used as a
	 * map key within the `DataStoreMemoryBuffer`.
	 */
	T getStoragePartSourceKey();

}
