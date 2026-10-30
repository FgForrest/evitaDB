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

package io.evitadb.core.exception;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when there is attempt to search the text of an attribute that is not marked as `searchable` -
 * in general, or in one of the scopes the query targets. A fulltext search is answered only from the fulltext index,
 * which is kept for searchable fields only - attributes and associated data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class AttributeNotSearchableException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 5140228513166582619L;

	/**
	 * Creates the exception for a global attribute of the catalog.
	 *
	 * @param attributeName name of the attribute
	 * @param catalogSchema schema of the catalog declaring it
	 */
	public AttributeNotSearchableException(
		@Nonnull String attributeName,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		super(
			"Global attribute with name `" + attributeName + "` in catalog `" + catalogSchema.getName() + "` is not " +
				"marked as \"searchable\" and cannot be searched in - there is no fulltext index of it."
		);
	}

	/**
	 * Creates the exception for an attribute of an entity, global attributes included.
	 *
	 * @param attributeName name of the attribute
	 * @param entitySchema  schema of the entity declaring it
	 */
	public AttributeNotSearchableException(
		@Nonnull String attributeName,
		@Nonnull EntitySchemaContract entitySchema
	) {
		super(
			"Attribute with name `" + attributeName + "` in entity `" + entitySchema.getName() + "` is not " +
				"marked as \"searchable\" and cannot be searched in - there is no fulltext index of it."
		);
	}

	/**
	 * Creates the exception for an attribute of an entity that is searchable, but not in the passed scope.
	 *
	 * @param attributeName name of the attribute
	 * @param entitySchema  schema of the entity declaring it
	 * @param scope         the scope the attribute is not searchable in
	 */
	public AttributeNotSearchableException(
		@Nonnull String attributeName,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Scope scope
	) {
		super(
			"Attribute with name `" + attributeName + "` in entity `" + entitySchema.getName() + "` is not " +
				"marked as \"searchable\" in scope `" + scope + "` and cannot be searched in there - there is no " +
				"fulltext index of it in that scope."
		);
	}

	/**
	 * Creates the exception for an attribute of a reference.
	 *
	 * @param attributeName   name of the attribute
	 * @param referenceSchema schema of the reference declaring it
	 * @param entitySchema    schema of the entity owning the reference
	 */
	public AttributeNotSearchableException(
		@Nonnull String attributeName,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntitySchemaContract entitySchema
	) {
		super(
			"Attribute with name `" + attributeName + "` in reference `" + referenceSchema.getName() + "` of entity " +
				"`" + entitySchema.getName() + "` is not marked as \"searchable\" and cannot be searched in - there " +
				"is no fulltext index of it."
		);
	}

}
