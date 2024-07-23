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

import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Generic interface that allows accessing schema sortable attribute compounds. Allow to unify access to the compounds
 * across multiple levels of the schema - entity and reference which all allow defining sortable attribute compounds.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SortableAttributeCompoundSchemaProvider<T extends AttributeSchemaContract> extends AttributeSchemaProvider<T> {

	/**
	 * Returns definition of all sortable attribute compounds defined in this provider.
	 */
	@Nonnull
	Map<String, SortableAttributeCompoundSchemaContract> getSortableAttributeCompounds();

	/**
	 * Returns definition of the sortable attribute compound of particular name.
	 */
	@Nonnull
	Optional<SortableAttributeCompoundSchemaContract> getSortableAttributeCompound(@Nonnull String name);

	/**
	 * Returns definition of the sortable attribute compound of particular name in specific naming convention.
	 */
	@Nonnull
	Optional<SortableAttributeCompoundSchemaContract> getSortableAttributeCompoundByName(@Nonnull String name, @Nonnull NamingConvention namingConvention);

	/**
	 * Returns collection of all sortable attribute compounds that refer to an attribute of particular name.
	 * @param attributeName name of the attribute to look for in the sortable attribute compounds
	 * @return collection of sortable attribute compounds that refer to the attribute of particular name
	 */
	@Nonnull
	Collection<SortableAttributeCompoundSchemaContract> getSortableAttributeCompoundsForAttribute(@Nonnull String attributeName);

}
