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
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;

/**
 * The interface concentrates the similar methods across all schema related interfaces that describe their
 * basic properties.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface NamedSchemaContract extends Serializable {

	/**
	 * Contains unique name of the model. Case-sensitive. Distinguishes one model item from another
	 * within single entity instance.
	 */
	@Nonnull
	String getName();

	/**
	 * Contains description of the model is optional but helps authors of the schema / client API to better
	 * explain the original purpose of the model to the consumers.
	 */
	@Nullable
	String getDescription();

	/**
	 * Map contains the {@link #getName()} variants in different {@link NamingConvention naming conventions}. The name
	 * is guaranteed to be unique among other model names in same convention. These names are used to quickly
	 * translate to / from names used in different protocols. Each API protocol prefers names in different naming
	 * conventions.
	 */
	@Nonnull
	Map<NamingConvention, String> getNameVariants();

	/**
	 * Method returns the name of the attribute in specified naming convention. The names are kept in the schema because
	 * the translation is computational expensive and also there is no guaranteed way that the name converted from
	 * original to a version in specific naming convention could be reverted to the original - some information is lost
	 * during the conversion. We also need to ensure that the name in all conventions stays unique.
	 *
	 * @param namingConvention to get name variant for
	 * @return attribute {@link #getName()} in specified naming convention
	 */
	@Nonnull
	String getNameVariant(@Nonnull NamingConvention namingConvention);

}
