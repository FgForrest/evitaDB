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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.dataType.EvitaDataTypes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.function.Consumer;

/**
 * Generic interface that allows altering schema attributes. Allow to unify altering process of attributes across
 * multiple levels of the schema - catalog, entity and reference which all allow defining attributes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AttributeProviderSchemaEditor<S, T extends AttributeSchemaContract, U extends AttributeSchemaEditor<U>> extends AttributeSchemaProvider<T> {

	/**
	 * Adds new {@link AttributeSchemaContract} to the set of allowed attributes of the entity/reference schema or
	 * updates existing.
	 *
	 * If you update existing associated data type all data must be specified again, nothing is preserved.
	 *
	 * Entity (global) attributes allows defining set of data that are fetched in bulk along with the entity body.
	 * Attributes may be indexed for fast filtering ({@link AttributeSchemaContract#isFilterable()}) or can be used to sort along
	 * ({@link AttributeSchemaContract#isSortable()}). Attributes are not automatically indexed in order not to waste precious
	 * memory space for data that will never be used in search queries.
	 *
	 * Filtering in attributes is executed by using constraints like {@link io.evitadb.api.query.filter.And},
	 * {@link io.evitadb.api.query.filter.Not}, {@link AttributeEquals}, {@link AttributeContains}
	 * and many others. Sorting can be achieved with {@link AttributeNatural}, {@link ReferenceProperty} or others.
	 *
	 * Attributes are not recommended for bigger data as they are all loaded at once when {@link AttributeContent}
	 * requirement is used. Large data that are occasionally used store in {@link io.evitadb.api.requestResponse.data.structure.AssociatedData}.
	 *
	 * @param ofType type of the entity. Must be one of {@link EvitaDataTypes#getSupportedDataTypes()} types
	 */
	@Nonnull
	S withAttribute(@Nonnull String attributeName, @Nonnull Class<? extends Serializable> ofType);

	/**
	 * Adds new {@link AttributeSchemaContract} to the set of allowed attributes of the entity/reference schema or
	 * updates existing.
	 *
	 * If you update existing associated data type all data must be specified again, nothing is preserved.
	 *
	 * Entity (global) attributes allows defining set of data that are fetched in bulk along with the entity body.
	 * Attributes may be indexed for fast filtering ({@link AttributeSchemaContract#isFilterable()}) or can be used to sort along
	 * ({@link AttributeSchemaContract#isSortable()}). Attributes are not automatically indexed in order not to waste precious
	 * memory space for data that will never be used in search queries.
	 *
	 * Filtering in attributes is executed by using constraints like {@link io.evitadb.api.query.filter.And},
	 * {@link io.evitadb.api.query.filter.Not}, {@link AttributeEquals}, {@link AttributeContains}
	 * and many others. Sorting can be achieved with {@link AttributeNatural} or others.
	 *
	 * Attributes are not recommended for bigger data as they are all loaded at once when {@link AttributeContent}
	 * requirement is used. Large data that are occasionally used store in {@link io.evitadb.api.requestResponse.data.structure.AssociatedData}.
	 *
	 * @param ofType  type of the entity. Must be one of {@link EvitaDataTypes#getSupportedDataTypes()} types
	 * @param whichIs lambda that allows to specify attributes of the attribute itself
	 */
	@Nonnull
	S withAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<U> whichIs
	);

	/**
	 * Removes specific {@link AttributeSchemaContract} from the set of allowed attributes of the entity or reference
	 * schema.
	 */
	@Nonnull
	S withoutAttribute(@Nonnull String attributeName);

}
