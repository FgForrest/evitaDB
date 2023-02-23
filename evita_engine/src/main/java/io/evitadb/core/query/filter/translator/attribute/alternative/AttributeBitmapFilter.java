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

package io.evitadb.core.query.filter.translator.attribute.alternative;

import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Implementation of {@link EntityToBitmapFilter} that verifies that the entity has the appropriate attribute value
 * matching the {@link #filter} predicate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class AttributeBitmapFilter implements EntityToBitmapFilter {

	/**
	 * Contains name of the attribute value that has to be retrieved and passed to the filter predicate.
	 */
	private final String attributeName;
	/**
	 * Contains the requirements for entity prefetching necessary to be fulfilled in order this filter works.
	 */
	private final EntityContentRequire requirements;
	/**
	 * Contains the attribute schema location function, that will extract the proper attribute schema from the entity
	 * schema.
	 */
	private final BiFunction<EntitySchemaContract, String, AttributeSchemaContract> attributeSchemaAccessor;
	/**
	 * Contains the attribute value location function, that will extract the proper attribute value from the entity body.
	 */
	private final BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor;
	/**
	 * Contains the factory function that will create a predicate (based on passed entity schema) that must be fulfilled
	 * in order attribute value is accepted by the filter.
	 */
	private final Function<AttributeSchemaContract, Predicate<Stream<Optional<AttributeValue>>>> filterFactory;

	@Nonnull
	@Override
	public EntityFetch getEntityRequire() {
		return new EntityFetch(requirements);
	}

	@Nonnull
	@Override
	public Bitmap filter(@Nonnull FilterByVisitor filterByVisitor) {
		final List<EntityDecorator> prefetchedEntities = filterByVisitor.getPrefetchedEntities();
		if (prefetchedEntities == null) {
			return EmptyBitmap.INSTANCE;
		} else {
			String entityType = null;
			Predicate<Stream<Optional<AttributeValue>>> filter = null;
			final BaseBitmap result = new BaseBitmap();
			// iterate over all entities
			for (SealedEntity entity : prefetchedEntities) {
			/* we can be sure entities are sorted by type because:
			   1. all entities share the same type
			   2. or entities are fetched via {@link QueryContext#prefetchEntities(EntityReference[], EntityContentRequire[])}
			      that fetches them by entity type in bulk
			*/
				final EntitySchemaContract entitySchema = entity.getSchema();
				if (!Objects.equals(entityType, entitySchema.getName())) {
					entityType = entitySchema.getName();
					final AttributeSchemaContract attributeSchema = ofNullable(attributeSchemaAccessor.apply(entitySchema, attributeName))
						.orElseThrow(() -> new EvitaInvalidUsageException("Attribute `" + attributeName + "` is not defined!"));
					filter = filterFactory.apply(attributeSchema);
				}
				// and filter by predicate
				if (filter != null && filter.test(attributeValueAccessor.apply(entity, attributeName))) {
					result.add(filterByVisitor.translateEntity(entity));
				}
			}
			return result;
		}
	}

}
