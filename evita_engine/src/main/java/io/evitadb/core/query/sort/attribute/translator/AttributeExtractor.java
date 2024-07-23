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

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.EntityContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Interface allows accessing the entity attributes. Interface allows only two implementations based on the query
 * evaluation context. Either we extract attributes from the entity itself or from particular reference of the entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public sealed interface AttributeExtractor permits EntityAttributeExtractor, EntityReferenceAttributeExtractor {

	/**
	 * Returns attribute value for particular `attributeName` from the `entity`.
	 */
	@Nullable
	Comparable<?> extract(@Nonnull EntityContract entity, @Nonnull String attributeName);

	/**
	 * Returns attribute value for particular combination of `attributeName` and `locale` from the `entity`.
	 */
	@Nullable
	Comparable<?> extract(@Nonnull EntityContract entity, @Nonnull String attributeName, @Nonnull Locale locale);

	/**
	 * Requirements that will trigger fetching appropriate container to access attribute in {@link EntityContract}.
	 */
	@Nonnull
	EntityContentRequire getRequirements();

}
