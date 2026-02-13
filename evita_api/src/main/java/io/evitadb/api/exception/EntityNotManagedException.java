/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
 * Exception thrown when an operation requires an entity type to be managed by evitaDB, but it is marked as external
 * (not managed).
 *
 * evitaDB distinguishes between **managed entity types** (those fully stored and indexed by evitaDB) and
 * **external entity types** (referenced but not managed by evitaDB). References can point to external entity types
 * via {@link io.evitadb.api.requestResponse.schema.ReferenceSchemaContract#isReferencedEntityTypeManaged()}.
 *
 * This exception is thrown when operations require full entity management capabilities but encounter external
 * (non-managed) entity types:
 *
 * - **Referenced entity fetching** with filters: attempting to use `entityHaving()` filter on references to external
 *   entities, since evitaDB doesn't store the referenced entity data
 * - **Entity fetch requirements** on external references: requesting full entity fetch via `entityFetch()` for
 *   references pointing to non-managed entity types
 * - **Index operations** on external entity collections: attempting to access global entity index methods for
 *   external entity types
 *
 * **Typical scenario:**
 *
 * A Product entity has a reference to Brand, but Brand is marked as external (managed by another system). Attempting
 * to filter products by brand properties via `entityHaving(attributeEquals('brandName', 'Nike'))` would fail because
 * evitaDB doesn't have Brand entities stored internally.
 *
 * **Resolution**: Either make the referenced entity type managed by evitaDB (store and index it), or remove operations
 * that require managed entity access (such as filtering by referenced entity properties).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class EntityNotManagedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 2826263371602773442L;

	/**
	 * Creates exception identifying the non-managed entity type.
	 *
	 * @param entityType the name of the entity type that is not managed by evitaDB
	 */
	public EntityNotManagedException(@Nonnull String entityType) {
		super(
			"Cannot execute the operation, entity type `" + entityType + "` is not managed by evitaDB!"
		);
	}

}
