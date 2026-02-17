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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Extension of {@link Attributes} that holds attributes associated with
 * a specific entity reference rather than the entity itself. When resolving
 * an attribute schema, the reference schema is consulted first; if no match
 * is found, the lookup falls back to the inherited `attributeTypes` map.
 *
 * Like its parent, instances of this class are immutable once constructed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ReferenceAttributes extends Attributes<AttributeSchemaContract> {
	@Serial private static final long serialVersionUID = 7625562503185582905L;
	/**
	 * Definition of the reference schema.
	 */
	final ReferenceSchemaContract referenceSchema;

	/**
	 * Creates reference attributes from a collection of attribute values.
	 * This constructor is intended for internal use by the evitaDB engine
	 * when loading reference attributes from persistent storage.
	 *
	 * @param entitySchema   schema of the entity owning the reference
	 * @param referenceSchema schema of the reference these attributes belong to
	 * @param attributeValues collection of attribute values to store
	 * @param attributeTypes  map of attribute names to their schema contracts
	 */
	public ReferenceAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Collection<AttributeValue> attributeValues,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		super(entitySchema, attributeValues, attributeTypes);
		this.referenceSchema = referenceSchema;
	}

	/**
	 * Creates reference attributes from a pre-indexed map of attribute values.
	 * This constructor is intended for internal use by the evitaDB engine
	 * when loading reference attributes from persistent storage with
	 * attribute values already indexed by their keys.
	 *
	 * @param entitySchema   schema of the entity owning the reference
	 * @param referenceSchema schema of the reference these attributes belong to
	 * @param attributeValues map of attribute keys to their values
	 * @param attributeTypes  map of attribute names to their schema contracts
	 */
	public ReferenceAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Map<AttributeKey, AttributeValue> attributeValues,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		super(entitySchema, attributeValues, attributeTypes);
		this.referenceSchema = referenceSchema;
	}

	/**
	 * Resolves the attribute schema for the given attribute name using
	 * a two-stage lookup: first checks the reference schema, then falls
	 * back to the `attributeTypes` map inherited from the parent class.
	 *
	 * @param attributeName name of the attribute to look up
	 * @return the attribute schema if found, empty optional otherwise
	 */
	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return this.referenceSchema.getAttribute(attributeName)
		                           .or(() -> ofNullable(this.attributeTypes.get(attributeName)));
	}

	/**
	 * Creates an {@link AttributeNotFoundException} that includes both
	 * the reference schema and entity schema context, allowing callers
	 * to understand which reference on which entity was missing the attribute.
	 *
	 * @param attributeName name of the attribute that was not found
	 * @return exception describing the missing attribute in reference context
	 */
	@Nonnull
	@Override
	protected AttributeNotFoundException createAttributeNotFoundException(@Nonnull String attributeName) {
		return new AttributeNotFoundException(attributeName, this.referenceSchema, this.entitySchema);
	}

}
