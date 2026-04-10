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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.index.EntityIndexKey;

/**
 * Mixin interface implemented by storage parts that are logically associated with a specific
 * {@link io.evitadb.index.EntityIndex}. It provides a uniform way for the persistence layer to obtain the
 * {@link EntityIndexKey} from any index-related storage part without casting to a concrete type.
 *
 * This interface is implemented by storage parts such as
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart},
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart} and similar parts
 * that belong to a particular entity index scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityIndexKeyAccessor {

	/**
	 * Returns the composite business key that uniquely identifies the owning {@link io.evitadb.index.EntityIndex}
	 * within the entity collection. The key encodes the index type (global, reduced, referenced-type) and, when
	 * applicable, the reference name and entity primary key the index belongs to.
	 */
	EntityIndexKey entityIndexKey();

}
