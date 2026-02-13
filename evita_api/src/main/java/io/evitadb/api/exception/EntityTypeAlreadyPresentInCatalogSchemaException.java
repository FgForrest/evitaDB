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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to define an entity schema whose name conflicts with an existing entity schema
 * in the catalog.
 *
 * evitaDB enforces **global uniqueness of entity collection names** within a catalog across all
 * {@link NamingConvention} variants. Each entity schema name is automatically transformed into multiple naming
 * conventions (camelCase, snake_case, PascalCase, etc.), and all these variants must be unique across the catalog.
 *
 * This exception is thrown when:
 *
 * - **Creating new entity collection** whose name (in any naming convention) collides with an existing collection
 * - **Schema persistence** detects naming conflicts during catalog loading or schema updates
 * - **Multiple entity schemas** would produce identical names in at least one naming convention variant
 *
 * **Example conflict scenarios:**
 *
 * - Attempting to create "ProductCategory" when "product_category" already exists (both map to same snake_case)
 * - Adding "userAccount" when "UserAccount" exists (both map to same PascalCase variant)
 * - Creating "order-item" when "OrderItem" exists (normalized forms collide)
 *
 * **Why this matters:**
 *
 * External APIs (GraphQL, REST, gRPC) use different naming conventions, and evitaDB must ensure that entity
 * collection names remain unambiguous across all API representations. A collision in any convention would make
 * it impossible to distinguish between entity types in that API.
 *
 * **Resolution**: Choose a different entity schema name that doesn't conflict in any naming convention variant
 * with existing entity collections in the catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityTypeAlreadyPresentInCatalogSchemaException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4058501830732065207L;
	/**
	 * The catalog name where the naming conflict occurred.
	 */
	@Getter private final String catalogName;
	/**
	 * The existing entity schema that conflicts with the new schema being added.
	 */
	@Getter private final EntitySchemaContract existingSchema;

	/**
	 * Creates exception identifying the naming conflict between entity schemas.
	 *
	 * @param catalogName the name of the catalog where the conflict occurred
	 * @param existingEntitySchema the existing entity schema that conflicts
	 * @param addedEntityName the name of the entity schema being added
	 * @param convention the naming convention in which the conflict was detected
	 * @param conflictingName the actual conflicting name variant
	 */
	public EntityTypeAlreadyPresentInCatalogSchemaException(
		@Nonnull String catalogName,
		@Nonnull EntitySchemaContract existingEntitySchema,
		@Nonnull String addedEntityName,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName) {
		super(
			"Entity schema `" + addedEntityName + "` and existing " +
				"entity schema `" + existingEntitySchema.getName() + "` produce the same " +
				"name `" + conflictingName + "` in `" + convention + "` convention! " +
				"Please choose different entity schema name."
		);
		this.catalogName = catalogName;
		this.existingSchema = existingEntitySchema;
	}
}
