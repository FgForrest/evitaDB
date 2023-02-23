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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.filter.translator.entity.alternative;

import io.evitadb.api.query.require.EntityRequire;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.entityFetch;

/**
 * Implementation of {@link EntityToBitmapFilter} that verifies that the entity has the appropriate locale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class LocaleEntityToBitmapFilter implements EntityToBitmapFilter {
	private final Locale locale;

	@Nonnull
	@Override
	public Bitmap filter(@Nonnull FilterByVisitor filterByVisitor) {
		final List<EntityDecorator> entities = filterByVisitor.getPrefetchedEntities();
		if (entities == null) {
			return EmptyBitmap.INSTANCE;
		} else {
			return new BaseBitmap(
				entities.stream()
					.filter(it -> it.getLocales().contains(locale))
					.mapToInt(filterByVisitor::translateEntity)
					.toArray()
			);
		}
	}

	@Nonnull
	@Override
	public EntityRequire getEntityRequire() {
		return entityFetch();
	}
}
