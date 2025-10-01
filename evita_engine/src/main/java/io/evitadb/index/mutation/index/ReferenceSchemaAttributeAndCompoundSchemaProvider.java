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

package io.evitadb.index.mutation.index;


import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * Reference-level implementation of {@link AttributeAndCompoundSchemaProvider} that provides access to attribute
 * schemas and sortable attribute compound schemas from a reference schema context.
 *
 * This implementation is used when performing index mutation operations on reference-level attributes.
 * It delegates schema lookups to the underlying {@link ReferenceSchemaContract} while maintaining access
 * to the parent {@link EntitySchemaContract} for context information. This provider gives access to:
 * - Reference attribute schemas for validation and indexing purposes
 * - Sortable attribute compound schemas that involve reference attributes
 *
 * This class is typically instantiated and used by {@link ReferenceIndexMutator} and related
 * components when processing reference attribute mutations. It requires both entity and reference
 * schema contracts to provide complete context for error reporting and schema resolution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public final class ReferenceSchemaAttributeAndCompoundSchemaProvider implements AttributeAndCompoundSchemaProvider {
	/**
	 * The entity schema contract that provides context information for error reporting and schema resolution.
	 * This is the parent schema that contains the reference schema.
	 */
	private final EntitySchemaContract entitySchema;

	/**
	 * The reference schema contract that provides access to reference-level attribute and compound schemas.
	 * This is the primary source of schema information for this provider.
	 */
	private final ReferenceSchemaContract referenceSchema;

	/**
	 * This implementation retrieves the attribute schema from the reference schema using
	 * {@link ReferenceSchemaContract#getAttribute(String)}. If the attribute is not found,
	 * an {@link AttributeNotFoundException} is thrown with context information about both
	 * the reference schema and the parent entity schema.
	 *
	 * @param attributeName the name of the reference attribute whose schema should be retrieved
	 * @return the attribute schema for the specified reference attribute
	 * @throws AttributeNotFoundException if the attribute does not exist in the reference schema
	 */
	@Nonnull
	@Override
	public AttributeSchema getAttributeSchema(@Nonnull String attributeName) {
		return this.referenceSchema
			.getAttribute(attributeName)
			.map(AttributeSchema.class::cast)
			.orElseThrow(
				() -> new AttributeNotFoundException(
					attributeName, this.referenceSchema,
					this.entitySchema
				));
	}

	/**
	 * This implementation retrieves sortable attribute compound schemas from the reference schema using
	 * {@link ReferenceSchemaContract#getSortableAttributeCompoundsForAttribute(String)}. The method returns
	 * all compound schemas that include the specified reference attribute as one of their constituent attributes.
	 *
	 * @param attributeName the name of the reference attribute to find compound schemas for
	 * @return a stream of sortable attribute compound schemas from the reference schema that include
	 * the specified attribute, may be empty if no compounds exist
	 */
	@Nonnull
	@Override
	public Stream<SortableAttributeCompoundSchema> getCompoundAttributeSchemas(@Nonnull String attributeName) {
		return this.referenceSchema
			.getSortableAttributeCompoundsForAttribute(attributeName)
			.stream()
			.map(SortableAttributeCompoundSchema.class::cast);
	}

}
