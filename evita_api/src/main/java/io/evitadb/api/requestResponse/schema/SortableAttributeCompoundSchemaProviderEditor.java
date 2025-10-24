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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.SortableAttributeCompoundSchemaBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link SortableAttributeCompoundSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SortableAttributeCompoundSchemaProviderEditor<
		T extends SortableAttributeCompoundSchemaProviderEditor<T, S, U>,
		S extends AttributeSchemaContract,
		U extends SortableAttributeCompoundSchemaContract
	> extends SortableAttributeCompoundSchemaProvider<S, U> {

	/**
	 * Adds new {@link SortableAttributeCompoundSchemaContract} to the entity or reference.
	 * Method cannot be used for updating attribute elements of existing {@link SortableAttributeCompoundSchemaContract}
	 * with particular name, you need to remove it first and then add it again.
	 *
	 * Sortable attribute compounds are used to sort entities or references by multiple attributes at once. evitaDB
	 * requires a pre-sorted index in order to be able to sort entities or references by particular attribute or
	 * combination of attributes, so it can deliver the results as fast as possible. Sortable attribute compounds
	 * are filtered the same way as {@link AttributeSchemaContract} attributes - using {@link AttributeNatural} ordering
	 * constraint.
	 *
	 * The referenced attributes must be already defined in the entity or reference schema.
	 *
	 * @param name              of the sortable attribute compound
	 * @param attributeElements array allowing to refer existing attributes and their behaviour in the compound sorting
	 */
	@Nonnull
	T withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement... attributeElements
	);

	/**
	 * Adds new {@link SortableAttributeCompoundSchemaContract} to the entity or reference or updates existing.
	 * Method cannot be used for updating attribute elements of existing {@link SortableAttributeCompoundSchemaContract}
	 * with particular name, you need to remove it first and then add it again.
	 *
	 * If you update existing sortable attribute compound all data must be specified again, nothing is preserved.
	 *
	 * Sortable attribute compounds are used to sort entities or references by multiple attributes at once. evitaDB
	 * requires a pre-sorted index in order to be able to sort entities or references by particular attribute or
	 * combination of attributes, so it can deliver the results as fast as possible. Sortable attribute compounds
	 * are filtered the same way as {@link AttributeSchemaContract} attributes - using {@link AttributeNatural} ordering
	 * constraint.
	 *
	 * The referenced attributes must be already defined in the entity or reference schema.
	 *
	 * @param name              of the sortable attribute compound
	 * @param attributeElements array allowing to refer existing attributes and their behaviour in the compound sorting
	 * @param whichIs           lambda that allows to specify properties of the sortable attribute compound itself
	 */
	@Nonnull
	T withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement[] attributeElements,
		@Nullable Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
	);

	/**
	 * Removes specific {@link SortableAttributeCompoundSchemaContract} from the entity or reference schema.
	 */
	@Nonnull
	T withoutSortableAttributeCompound(
		@Nonnull String name
	);

}
