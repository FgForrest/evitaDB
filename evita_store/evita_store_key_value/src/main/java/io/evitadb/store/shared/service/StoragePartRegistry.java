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

package io.evitadb.store.shared.service;

import io.evitadb.api.statistics.StoragePartGroup;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

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
	 * Record representing single {@link StoragePart} type along with its unique byte id and the kind of data it holds.
	 *
	 * The `group` is what makes a storage-part breakdown readable by a client that has never heard of the class named
	 * in it: the set of part types is open and grows with every index structure the engine gains, while
	 * {@link StoragePartGroup} is closed. Declaring it here rather than deriving it later is deliberate - a new part
	 * type cannot be registered without being classified, because this constructor will not compile without it. The
	 * alternatives were both silent on omission: a name test ({@code EntityIdsStoragePart} and
	 * {@code HistogramCardinalityStoragePart} are index parts with no `Index` in their names) and a marker interface
	 * (a forgotten marker falls through to a default). Nor can the group be inferred from which registry declares the
	 * type: {@code EntitySchemaStoragePart} is declared by the entity registry yet is
	 * {@link StoragePartGroup#SCHEMA}, and {@code GlobalUniqueIndexStoragePart} is an
	 * {@link StoragePartGroup#ATTRIBUTE_INDEX} living in the catalog's own data store.
	 *
	 * @param id       unique id among all other storage parts
	 * @param partType the class of storage part
	 * @param group    the kind of data this part holds, as reported by the storage composition statistics
	 */
	record StoragePartRecord(
		byte id,
		@Nonnull Class<? extends StoragePart> partType,
		@Nonnull StoragePartGroup group
	) {
	}

}
