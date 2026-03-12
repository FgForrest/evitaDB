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

package io.evitadb.spi.store.catalog.persistence;

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * A `StorageDescriptor` carries the metadata that is necessary to open and read a single evitaDB binary data file
 * (catalog file or entity collection file) serialized with Kryo. The descriptor is written alongside the file and
 * must be loaded before any records in that file can be deserialized. Without a valid descriptor the file is opaque
 * binary data.
 *
 * The descriptor is immutable after creation; a new instance is produced with each
 * {@link StoragePartPersistenceService#flush(long)}
 * call. Implementations typically carry additional fields beyond the base contract (e.g. file location metadata).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface StorageDescriptor {

	/**
	 * The monotonically increasing descriptor version. It is incremented with each logical update to the descriptor
	 * contents (e.g. when new keys are registered in the {@link KeyCompressor}). This counter is not persisted to
	 * disk; its sole purpose is to let callers detect in-memory changes and decide whether the updated descriptor
	 * needs to be written to the storage header.
	 */
	long version();

	/**
	 * Returns the full id → key mapping from the {@link KeyCompressor} that was active during the last write to
	 * this storage file. The map is keyed by the compact integer ids used in the Kryo-serialized records; the values
	 * are the original key objects (e.g. {@link io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey}).
	 *
	 * This data is persisted as part of the storage header so that a new {@link KeyCompressor} can be bootstrapped
	 * when the file is reopened, enabling correct deserialization of all records.
	 */
	@Nonnull
	Map<Integer, Object> compressedKeys();

}
