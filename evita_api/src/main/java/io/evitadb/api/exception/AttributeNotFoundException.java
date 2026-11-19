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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Thrown when attempting to access an attribute by name that is not defined in the relevant schema
 * (catalog global attributes, entity attributes, or reference attributes).
 *
 * This exception occurs when code tries to retrieve or filter by an attribute using a name that doesn't
 * exist in the appropriate schema's attribute definitions. This is a validation error that prevents
 * runtime issues from using undefined attribute names in queries or data access methods. The exception
 * has three variants for different attribute scopes: catalog-level global attributes, entity-level
 * attributes, and reference-level attributes.
 *
 * **When this is thrown:**
 * - Calling `getAttribute()` or similar methods with a non-existent attribute name
 * - Using attribute filtering/sorting constraints with undefined attributes
 * - Accessing attributes via proxy interfaces with invalid names
 * - Thrown by `AttributeSchemaAccessor`, entity/reference data structures, and query translators
 *
 * **Resolution:**
 * - Check the appropriate schema (catalog/entity/reference) for valid attribute names
 * - Define the attribute in the schema before attempting to use it
 * - Verify the spelling and case of the attribute name
 * - Use schema introspection methods to discover available attribute names
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeNotFoundException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -776076988391967761L;

	/**
	 * Creates exception for a global attribute that doesn't exist in the catalog schema.
	 */
	public AttributeNotFoundException(@Nonnull String attributeName, @Nonnull CatalogSchemaContract catalogSchema) {
		super("Global attribute with name `" + attributeName + "` is not present in schema of catalog `" + catalogSchema.getName() + "`.");
	}

	/**
	 * Creates exception for an attribute that doesn't exist in the entity schema.
	 */
	public AttributeNotFoundException(@Nonnull String attributeName, @Nonnull EntitySchemaContract entitySchema) {
		super("Attribute with name `" + attributeName + "` is not present in schema of `" + entitySchema.getName() + "` entity.");
	}

	/**
	 * Creates exception for an attribute that doesn't exist in a specific reference schema.
	 */
	public AttributeNotFoundException(@Nonnull String attributeName, @Nonnull ReferenceSchemaContract referenceSchema, @Nonnull EntitySchemaContract entitySchema) {
		super(
			"Attribute with name `" + attributeName + "` is not present in schema of reference " +
				"`" + referenceSchema.getName() + "` of entity `" + entitySchema.getName() + "`."
		);
	}

	/**
	 * Creates a missing-place diagnostic retaining both the schema level and the stated query constraint.
	 *
	 * @param attributeName the attribute the constraint names
	 * @param entitySchema schema of the entity holding the attribute, or holding the reference that should hold it
	 * @param referenceSchema NULL when the attribute belongs to the entity itself, otherwise the reference whose
	 *                        attributes were searched
	 * @param constraint the complete constraint as the query states it
	 */
	public AttributeNotFoundException(
		@Nonnull String attributeName, @Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema, @Nonnull String constraint
	) {
		super("Constraint `" + constraint + "`: attribute `" + attributeName + "` is not present in schema of " +
			(referenceSchema == null ? "" : "reference `" + referenceSchema.getName() + "` of ") +
			"entity `" + entitySchema.getName() + "`.");
	}

}
