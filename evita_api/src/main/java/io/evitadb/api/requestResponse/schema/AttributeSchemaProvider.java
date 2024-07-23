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
import java.util.Map;
import java.util.Optional;

/**
 * Generic interface that allows accessing schema attributes. Allow to unify access to attributes across multiple levels
 * of the schema - catalog, entity and reference which all allow defining attributes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AttributeSchemaProvider<T extends AttributeSchemaContract> {

	/**
	 * Returns definition of all attributes defined in this provider.
	 */
	@Nonnull
	Map<String, T> getAttributes();

	/**
	 * Returns definition of the attribute of particular name.
	 */
	@Nonnull
	Optional<T> getAttribute(@Nonnull String attributeName);

	/**
	 * Returns definition of the attribute of particular name in specific naming convention.
	 */
	@Nonnull
	Optional<T> getAttributeByName(@Nonnull String attributeName, @Nonnull NamingConvention namingConvention);

}
