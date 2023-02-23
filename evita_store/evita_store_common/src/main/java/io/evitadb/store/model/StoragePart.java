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

package io.evitadb.store.model;

import io.evitadb.store.service.KeyCompressor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * StoragePart is a data container, that can be stored via {@link io.evitadb.storage.MemTable#put(long, long, StoragePart)}
 * method to the persistent storage. Each storage part is uniquely determined by {@link #getUniquePartId()} and the type
 * (e.g. {@link StoragePart#getClass() implementation class}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface StoragePart extends Serializable {

	/**
	 * Returns id unique for the entity part. Id must be unique among all parts of the same type.
	 */
	@Nullable
	Long getUniquePartId();

	/**
	 * Returns TRUE if the storage part is new and has never been stored to persistent storage (yet).
	 */
	default boolean isNew() {
		return getUniquePartId() == null;
	}

	/**
	 * Computes new id unique id for the entity part. Id must be unique among all parts of the same type.
	 */
	long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor);

}
