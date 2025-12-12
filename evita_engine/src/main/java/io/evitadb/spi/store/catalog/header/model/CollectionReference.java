/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.spi.store.catalog.header.model;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Lightweight reference that identifies a single entity collection in a catalog.
 *
 * The reference exposes both the human-readable `entityType` and its compact integer surrogate
 * `entityTypePrimaryKey`. This allows the engine to pass collection identity between components and
 * persistence layers without binding to any particular storage implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CollectionReference extends Serializable {

	/**
	 * Name/type of the entity collection (e.g. {@link EntitySchemaContract#getName()}). It is stable within the catalog
	 * and maps to {@link #entityTypePrimaryKey()} for compact storage.
	 *
	 * @return non-null entity type name
	 */
	@Nonnull
	String entityType();

	/**
	 * Compact integer key representing {@link #entityType()} within the catalog. It is used to
	 * serialize references efficiently and is unique per catalog.
	 *
	 * @return non-negative internal type id
	 */
	int entityTypePrimaryKey();
}
