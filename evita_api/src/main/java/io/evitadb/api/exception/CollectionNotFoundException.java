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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to access an entity collection (entity type) that does
 * not exist in the catalog.
 *
 * Each catalog organizes entities into collections identified by entity type names (like
 * "Product", "Category", etc.). This exception indicates that no collection exists for the
 * requested entity type. Collections must be created by inserting an entity with a schema
 * definition or by explicitly defining the schema first.
 *
 * **Typical Causes:**
 * - Typo in the entity type name (entity type names are case-sensitive)
 * - Collection was never created (no entities of this type were inserted)
 * - Using a model class without proper `@Entity` or `@EntityRef` annotation
 * - Attempting to use an entity type primary key that doesn't exist
 *
 * **Resolution:**
 * - Verify the entity type name matches exactly (case-sensitive)
 * - Use `getEntityTypes()` to list all available collections in the catalog
 * - Insert at least one entity of this type, or define the schema explicitly
 * - For model classes: ensure the class is annotated with `@Entity` or `@EntityRef`
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CollectionNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 1445427449119582559L;

	/**
	 * Creates a new exception for a collection that does not exist.
	 *
	 * @param entityType name of the entity type whose collection was not found
	 */
	public CollectionNotFoundException(@Nonnull String entityType) {
		super("No collection found for entity type `" + entityType + "`!");
	}

	/**
	 * Creates a new exception when a model class is missing required entity type
	 * annotations.
	 *
	 * @param modelClass the model class that lacks `@Entity` or `@EntityRef` annotation
	 */
	public CollectionNotFoundException(@Nonnull Class<?> modelClass) {
		super(
			"Entity type cannot be resolved. Neither `@Entity` no `@EntityRef` " +
				"annotation was found on model class: `" + modelClass.getName() + "`!"
		);
	}

	/**
	 * Creates a new exception for a collection identified by its internal primary key.
	 *
	 * @param entityTypePrimaryKey internal primary key of the entity type
	 */
	public CollectionNotFoundException(int entityTypePrimaryKey) {
		super("No collection found for entity type with primary key `" + entityTypePrimaryKey + "`!");
	}

}
