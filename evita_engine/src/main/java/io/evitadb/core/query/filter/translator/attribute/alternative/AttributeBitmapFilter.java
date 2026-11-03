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

package io.evitadb.core.query.filter.translator.attribute.alternative;

import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;

/**
 * Implementation of {@link EntityToBitmapFilter} that selects the prefetched entities holding an attribute value the
 * {@link AttributeValueMatcher} of a constraint accepts. It is the prefetch alternative of the filter index lookup the
 * attribute translators perform, and it asks the very matcher the index lookup is verified with, so both answer alike.
 * Translators create it with {@link #matching}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
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
	private final TriFunction<EntitySchemaContract, String, AttributeTrait[], AttributeSchemaContract> attributeSchemaAccessor;
	/**
	 * Contains the attribute value location function, that will extract the proper attribute value from the entity body.
	 */
	private final BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor;
	/**
	 * Contains the factory function that will create a predicate (based on passed entity schema) that must be fulfilled
	 * in order attribute value is accepted by the filter.
	 */
	private final Function<AttributeSchemaContract, Predicate<Stream<Optional<AttributeValue>>>> filterFactory;
	/**
	 * Contains array of all attribute required traits.
	 */
	private final AttributeTrait[] requiredAttributeTraits;
	/**
	 * Contains the Bitmap result that has been memoized for efficiency.
	 */
	private Bitmap memoizedResult;

	/**
	 * Creates the filter that selects an entity when any value it holds under the attribute name satisfies the
	 * {@link AttributeValueMatcher}. An array value satisfies it when any of its elements does, a missing value never
	 * does. The attribute content the filter needs prefetched is the attribute alone.
	 *
	 * @param attributeName           name of the attribute whose values are tested
	 * @param attributeSchemaAccessor locates the attribute schema in the entity schema
	 * @param attributeValueAccessor  locates the attribute values in the entity body
	 * @param matcherFactory          creates the matcher from the schema of the attribute, called once per entity type
	 *                                of the examined entities
	 * @param requiredAttributeTraits the traits the attribute schema must have
	 * @return the filter
	 */
	@Nonnull
	public static AttributeBitmapFilter matching(
		@Nonnull String attributeName,
		@Nonnull TriFunction<EntitySchemaContract, String, AttributeTrait[], AttributeSchemaContract>
			attributeSchemaAccessor,
		@Nonnull BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Function<AttributeSchemaContract, AttributeValueMatcher> matcherFactory,
		@Nonnull AttributeTrait... requiredAttributeTraits
	) {
		return new AttributeBitmapFilter(
			attributeName,
			attributeContent(attributeName),
			attributeSchemaAccessor,
			attributeValueAccessor,
			attributeSchema -> {
				final AttributeValueMatcher matcher = matcherFactory.apply(attributeSchema);
				return values -> values.anyMatch(value -> value.isPresent() && matcher.matches(value.get().value()));
			},
			requiredAttributeTraits
		);
	}

	/**
	 * Creates the filter from a raw factory of the test of the entity values. Prefer {@link #matching}, which wraps a
	 * matcher into that test.
	 *
	 * @param attributeName the name of the attribute
	 * @param requirements the content the prefetched entities must be fetched with
	 * @param attributeSchemaAccessor resolves the schema of the attribute in the entity schema
	 * @param attributeValueAccessor reads the values of the attribute of an entity
	 * @param filterFactory creates the test of the values of an entity from the attribute schema
	 * @param requiredAttributeTraits the traits the attribute schema must have
	 */
	public AttributeBitmapFilter(
		@Nonnull String attributeName,
		@Nonnull EntityContentRequire requirements,
		@Nonnull TriFunction<EntitySchemaContract, String, AttributeTrait[], AttributeSchemaContract> attributeSchemaAccessor,
		@Nonnull BiFunction<EntityContract, String, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Function<AttributeSchemaContract, Predicate<Stream<Optional<AttributeValue>>>> filterFactory,
		@Nonnull AttributeTrait... requiredAttributeTraits
	) {
		this.attributeName = attributeName;
		this.requirements = requirements;
		this.attributeSchemaAccessor = attributeSchemaAccessor;
		this.attributeValueAccessor = attributeValueAccessor;
		this.filterFactory = filterFactory;
		this.requiredAttributeTraits = requiredAttributeTraits;
	}

	@Nonnull
	@Override
	public EntityFetch getEntityRequire() {
		return new EntityFetch(this.requirements);
	}

	@Nonnull
	@Override
	public Bitmap filter(@Nonnull QueryExecutionContext context) {
		if (this.memoizedResult == null) {
			final List<ServerEntityDecorator> prefetchedEntities = context.getPrefetchedEntities();
			if (prefetchedEntities == null) {
				this.memoizedResult = EmptyBitmap.INSTANCE;
			} else {
				String entityType = null;
				Predicate<Stream<Optional<AttributeValue>>> filter = null;
				final BaseBitmap result = new BaseBitmap();
				// iterate over all entities
				for (SealedEntity entity : prefetchedEntities) {
					/* we can be sure entities are sorted by type because:
					   1. all entities share the same type
					   2. or entities are fetched via {@link QueryPlanningContext#prefetchEntities(EntityReference[], EntityContentRequire[])}
					      that fetches them by entity type in bulk
					*/
					final EntitySchemaContract entitySchema = entity.getSchema();
					if (!Objects.equals(entityType, entitySchema.getName())) {
						entityType = entitySchema.getName();
						final AttributeSchemaContract attributeSchema = this.attributeSchemaAccessor.apply(
							entitySchema, this.attributeName, this.requiredAttributeTraits
						);
						filter = this.filterFactory.apply(attributeSchema);
					}
					// and filter by predicate
					if (filter != null) {
						final Stream<Optional<AttributeValue>> valueStream = this.attributeValueAccessor.apply(entity, this.attributeName);
						if (valueStream != null && filter.test(valueStream)) {
							result.add(context.translateEntity(entity));
						}
					}
				}
				this.memoizedResult = result;
			}
		}
		return this.memoizedResult;
	}

}
