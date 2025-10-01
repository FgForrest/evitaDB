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


import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * Schema provider interface that provides access to attribute schemas and sortable attribute compound schemas
 * for index mutation operations. This interface abstracts the source of schema information, allowing different
 * implementations to provide schemas from either entity-level or reference-level contexts.
 *
 * This provider is primarily used by {@link io.evitadb.index.mutation.index.AttributeIndexMutator} and related
 * index mutation components to retrieve schema information needed for attribute indexing operations such as:
 *
 * - Attribute value upserts and removals
 * - Sortable attribute compound management
 * - Index structure updates based on schema changes
 *
 * The interface supports two main implementation contexts:
 * - **Entity context**: Provides schemas for entity-level attributes
 * - **Reference context**: Provides schemas for reference-level attributes
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public sealed interface AttributeAndCompoundSchemaProvider permits EntitySchemaAttributeAndCompoundSchemaProvider, ReferenceSchemaAttributeAndCompoundSchemaProvider {

	/**
	 * Retrieves the attribute schema for the specified attribute name from the appropriate schema context
	 * (either entity or reference level, depending on the implementation).
	 *
	 * This method is essential for index mutation operations as it provides the schema information
	 * needed to properly handle attribute values, including their data types, constraints, and indexing
	 * requirements.
	 *
	 * @param attributeName the name of the attribute whose schema should be retrieved
	 * @return the attribute schema for the specified attribute name
	 * @throws io.evitadb.api.exception.AttributeNotFoundException if the attribute with the given name
	 *         does not exist in the schema context
	 */
	@Nonnull
	AttributeSchema getAttributeSchema(@Nonnull String attributeName);

	/**
	 * Returns a stream of sortable attribute compound schemas that include the specified attribute name
	 * as one of their constituent attributes.
	 *
	 * Sortable attribute compounds are composite indexes that combine multiple attributes to enable
	 * efficient sorting operations. This method is used during index mutation operations to identify
	 * which compound indexes need to be updated when a particular attribute value changes.
	 *
	 * The returned stream may be empty if the attribute is not part of any sortable attribute compounds.
	 *
	 * @param attributeName the name of the attribute to find compound schemas for
	 * @return a stream of sortable attribute compound schemas that include the specified attribute,
	 *         may be empty if no compounds exist for this attribute
	 */
	@Nonnull
	Stream<SortableAttributeCompoundSchema> getCompoundAttributeSchemas(@Nonnull String attributeName);

}
