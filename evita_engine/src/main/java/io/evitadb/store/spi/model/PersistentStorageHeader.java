/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.spi.model;

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.service.KeyCompressor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * Persistent storage header contains crucial information to read data from a single data storage file. The header needs
 * to be persisted in different location than the file it tracks. However, without the header the contents of the data
 * store file cannot be properly read because they contain only variable size binary data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public class PersistentStorageHeader implements PersistentStorageDescriptor, Serializable {
	@Serial private static final long serialVersionUID = 6321895659529914916L;

	/**
	 * Catalog entity header incremented with each update. Version is not stored on the disk, it serves only to distinguish
	 * whether there is any change made in the header and whether it needs to be persisted on disk.
	 */
	@Getter private final long version;
	/**
	 * Contains location of the last MemTable fragment for this version of the header / collection.
	 */
	@Getter @Nullable protected final FileLocation fileLocation;
	/**
	 * Contains key index extracted from {@link KeyCompressor} that is necessary for
	 * bootstraping {@link KeyCompressor} used for MemTable deserialization.
	 */
	@Getter @Nonnull protected final Map<Integer, Object> compressedKeys;

	public PersistentStorageHeader() {
		this.version = 1L;
		this.fileLocation = null;
		this.compressedKeys = Collections.emptyMap();
	}

	public PersistentStorageHeader(long version, @Nullable FileLocation fileLocation, @Nonnull Map<Integer, Object> compressedKeys) {
		this.version = version;
		this.fileLocation = fileLocation;
		this.compressedKeys = compressedKeys;
	}
}
