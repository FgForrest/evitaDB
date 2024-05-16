/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;

/**
 * This implementation of {@link AttributeExtractor} extracts the attribute values directly from the entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EntityAttributeExtractor implements AttributeExtractor {
	public static final EntityAttributeExtractor INSTANCE = new EntityAttributeExtractor();

	@Nullable
	@Override
	public Comparable<?> extract(@Nonnull EntityContract entity, @Nonnull String attributeName) {
		return entity.getAttribute(attributeName);
	}

	@Nullable
	@Override
	public Comparable<?> extract(@Nonnull EntityContract entity, @Nonnull String attributeName, @Nonnull Locale locale) {
		return entity.getAttribute(attributeName, locale);
	}

	@Nonnull
	@Override
	public EntityContentRequire getRequirements() {
		return attributeContentAll();
	}
}
