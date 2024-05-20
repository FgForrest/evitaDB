/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.service;

import io.evitadb.store.model.StoragePart;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Implementations of this interface allows to provide set of {@link StoragePart} to be registered as known types
 * to the persistent data store.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface StoragePartRegistry {

	/**
	 * Returns all {@link StoragePart} implementations managed by this registry.
	 */
	@Nonnull
	Collection<StoragePartRecord> listStorageParts();

	/**
	 * Record representing single {@link StoragePart} type along with its unique byte id.
	 *
	 * @param id       unique id among all other storage parts
	 * @param partType the class of storage part
	 */
	record StoragePartRecord(
		byte id,
		@Nonnull Class<? extends StoragePart> partType
	) {
	}

}
