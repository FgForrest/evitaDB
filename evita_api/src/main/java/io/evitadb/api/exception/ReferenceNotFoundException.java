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

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

import static java.util.Optional.ofNullable;

/**
 * Exception thrown when a client attempts to access, query, or manipulate a reference that does
 * not exist, either at the schema level or at the entity instance level.
 *
 * This exception can be raised in three distinct scenarios:
 *
 * **1. Unknown entity context**: Attempting to resolve a reference by name when the entity type
 * is not known. Without entity type information, evitaDB cannot determine which entity schema
 * to consult for reference definitions.
 *
 * **2. Reference not in schema**: The reference name is valid syntactically but does not exist
 * in the entity schema. This typically occurs when:
 * - A query references a non-existent reference name
 * - Client code tries to access a reference that was never defined in the schema
 * - A typo in the reference name
 *
 * **3. Reference instance not found on entity**: The reference schema exists, but the specific
 * reference instance (identified by referenced entity ID) is not present on the given entity.
 * This is a data-level, not schema-level, absence.
 *
 * This exception is commonly raised during query filtering
 * (e.g., `referenceHaving('brand', ...)`), entity manipulation
 * (e.g., `getReference('category', 123)`), and query translation when reference names are
 * resolved against the schema.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -8969284548331815445L;

	/**
	 * Constructs an exception indicating that a reference cannot be located because the entity
	 * type context is unknown.
	 *
	 * @param referenceName the name of the reference that cannot be resolved
	 */
	public ReferenceNotFoundException(@Nonnull String referenceName) {
		super("Reference with name `" + referenceName + "` cannot be located when entity type is not known.");
	}

	/**
	 * Constructs an exception indicating that a reference with the given name does not exist in
	 * the entity schema.
	 *
	 * @param referenceName the name of the reference that is not present in the schema
	 * @param entitySchema  the entity schema that does not contain the reference definition
	 */
	public ReferenceNotFoundException(@Nonnull String referenceName, @Nonnull EntitySchemaContract entitySchema) {
		super(
			"Reference with name `" + referenceName + "` is not present in schema of `" + entitySchema.getName() + "` entity.");
	}

	/**
	 * Constructs an exception indicating that a specific reference instance is not present on
	 * an entity, even though the reference schema exists.
	 *
	 * @param referenceName      the name of the reference schema
	 * @param referencedEntityId the primary key of the referenced entity that was not found
	 * @param entity             the entity instance on which the reference is missing
	 */
	public ReferenceNotFoundException(
		@Nonnull String referenceName, int referencedEntityId, @Nonnull EntityContract entity) {
		super("Reference with name `" + referenceName + "` to entity with id `" + referencedEntityId + "` " +
			      "is not present in the entity `" + entity.getType() + "` with " +
			      ofNullable(entity.getPrimaryKey())
				      .map(it -> "primary key `" + entity.getPrimaryKey() + "`")
				      .orElse("not yet assigned primary key") + "."
		);
	}

}
