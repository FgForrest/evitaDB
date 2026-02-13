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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown when attempting to define an entity-level attribute with the same name as a global catalog attribute
 * without explicitly reusing the global definition.
 *
 * evitaDB supports global attributes defined at the catalog level that can be shared across all entity types
 * within that catalog. When defining entity schemas, if you want to use a global attribute, you must
 * explicitly reference it using `withGlobalAttribute()` rather than defining a new attribute with the same
 * name. This prevents accidental shadowing of global attributes and ensures consistent attribute definitions
 * across entity types.
 *
 * **When this is thrown:**
 * - During entity schema evolution when adding an attribute that conflicts with a global attribute
 * - When using `withAttribute()` instead of `withGlobalAttribute()` for a globally defined attribute
 * - Thrown by `InternalEntitySchemaBuilder` during schema validation
 *
 * **Resolution:**
 * - Use `withGlobalAttribute(attributeName)` to explicitly reuse the global attribute definition
 * - Choose a different attribute name if you need a distinct entity-level attribute
 * - Review catalog schema to see which attributes are defined globally
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeAlreadyPresentInCatalogSchemaException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3205733087469383992L;
	/**
	 * Name of the catalog containing the global attribute.
	 */
	@Getter private final String catalogName;
	/**
	 * The existing global attribute schema that conflicts with the entity-level definition.
	 */
	@Getter private final AttributeSchemaContract existingSchema;

	/**
	 * Creates exception identifying the catalog and the conflicting global attribute.
	 */
	public AttributeAlreadyPresentInCatalogSchemaException(@Nonnull String catalogName, @Nonnull AttributeSchemaContract existingSchema) {
		super("Attribute `" + existingSchema.getName() + "` is already defined as global attribute of catalog `" + catalogName + "`, use `withGlobalAttribute` method to reuse it!");
		this.catalogName = catalogName;
		this.existingSchema = existingSchema;
	}

}
