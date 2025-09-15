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
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * Entity-level implementation of {@link AttributeAndCompoundSchemaProvider} that provides access to attribute
 * schemas and sortable attribute compound schemas from an entity schema context.
 *
 * This implementation is used when performing index mutation operations on entity-level attributes.
 * It delegates schema lookups to the underlying {@link EntitySchemaContract} and provides access to:
 * - Entity attribute schemas for validation and indexing purposes
 * - Sortable attribute compound schemas that involve entity attributes
 *
 * This class is typically instantiated and used by {@link EntityIndexLocalMutationExecutor}
 * and related components when processing entity attribute mutations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public final class EntitySchemaAttributeAndCompoundSchemaProvider implements AttributeAndCompoundSchemaProvider {
	/**
	 * The entity schema contract that provides access to entity-level attribute and compound schemas.
	 */
	private final EntitySchemaContract entitySchema;

	/**
	 * This implementation retrieves the attribute schema from the entity schema using
	 * {@link EntitySchemaContract#getAttribute(String)}. If the attribute is not found,
	 * an {@link AttributeNotFoundException} is thrown with context information about the entity schema.
	 *
	 * @param attributeName the name of the entity attribute whose schema should be retrieved
	 * @return the attribute schema for the specified entity attribute
	 * @throws AttributeNotFoundException if the attribute does not exist in the entity schema
	 */
	@Nonnull
	@Override
	public AttributeSchema getAttributeSchema(@Nonnull String attributeName) {
		return this.entitySchema.getAttribute(attributeName)
			.map(AttributeSchema.class::cast)
			.orElseThrow(() -> new AttributeNotFoundException(attributeName, this.entitySchema));
	}

	/**
	 * {@inheritDoc}
	 *
	 * This implementation retrieves sortable attribute compound schemas from the entity schema using
	 * {@link EntitySchemaContract#getSortableAttributeCompoundsForAttribute(String)}. The method returns
	 * all compound schemas that include the specified attribute as one of their constituent attributes.
	 *
	 * @param attributeName the name of the entity attribute to find compound schemas for
	 * @return a stream of sortable attribute compound schemas from the entity schema that include
	 *         the specified attribute, may be empty if no compounds exist
	 */
	@Nonnull
	@Override
	public Stream<SortableAttributeCompoundSchema> getCompoundAttributeSchemas(@Nonnull String attributeName) {
		return this.entitySchema.getSortableAttributeCompoundsForAttribute(attributeName)
			.stream()
			.map(SortableAttributeCompoundSchema.class::cast);
	}

}
