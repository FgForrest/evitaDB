/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.sort.primaryKey.translator;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReducedIndexResolver;
import io.evitadb.dataType.Scope;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReducedEntityIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.ATTRIBUTE_VARIANT;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.VARIANTS_REFERENCE_NAME;
import static io.evitadb.core.query.sort.generic.PrefetchedOwnersFixture.VARIANTS_REFERENCE_SCHEMA;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;

/**
 * Verifies the content {@link EntityPrimaryKeyNaturalTranslator} asks the prefetch for when it orders by the primary
 * key of the referenced entity over a reference allowing duplicates: a traversal only checks whether an owner has a
 * row to the referenced entity of a block, so it needs the rows alone, while a `pickFirst` ordering picks among the
 * rows sharing one referenced entity by their representative attributes, so it needs those as well.
 *
 * The translator runs in a real {@link OrderByVisitor} over the `variants` reference of
 * {@link PrefetchedOwnersFixture}, planned without a session.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Content prefetched for an ordering by the referenced primary key")
@Tag(ENGINE)
@Tag(ORDER)
class EntityPrimaryKeyNaturalTranslatorPrefetchTest {

	/**
	 * Creates the sorters of an ascending `entityPrimaryKeyNatural` within the ordering of the reference and returns
	 * the content the prefetch was asked for on the way.
	 *
	 * @param referenceSchema the ordered reference
	 * @param pickFirst       whether the ordering is a `pickFirst` one rather than a traversal
	 * @return the requirements to prefetch
	 */
	@Nonnull
	private static EntityContentRequire[] prefetchRequirementsOf(
		@Nonnull ReferenceSchema referenceSchema,
		boolean pickFirst
	) {
		// the catalog is the only mock: it cannot be built outside a running engine, and nothing the translator
		// does reads it; the query names no collection, so the planning context never asks the catalog for one
		final QueryPlanningContext queryContext = new QueryPlanningContext(
			mock(Catalog.class), null, null,
			new EvitaRequest(
				query(filterBy(entityPrimaryKeyInSet(1))), OffsetDateTime.now(), EntityReference.class, null
			),
			createHashMap(4), createHashMap(4), NoCacheSupervisor.INSTANCE
		);
		final OrderByVisitor orderByVisitor = new OrderByVisitor(queryContext);
		final PickFirstReducedIndexResolver pickFirstIndexResolver = pickFirst ?
			new PickFirstReducedIndexResolver(
				queryContext, referenceSchema, new Scope[]{Scope.LIVE}, null, false, Map.of(),
				() -> new ReducedEntityIndex[0]
			) :
			null;
		orderByVisitor.executeInContext(
			new ReferencePartition[0], referenceSchema, null,
			new AttributeSchemaAccessor(queryContext.getCatalogSchema(), null), null, pickFirstIndexResolver,
			() -> new EntityPrimaryKeyNaturalTranslator()
				.createSorter(entityPrimaryKeyNatural(OrderDirection.ASC), orderByVisitor)
				.toList()
		);
		return queryContext.getRequirementsToPrefetch();
	}

	@Test
	@DisplayName("should prefetch the rows of a traversal without their representative attributes")
	void shouldPrefetchReferencesWithoutAttributesForTraversal() {
		assertArrayEquals(
			new EntityContentRequire[]{referenceContent(VARIANTS_REFERENCE_NAME)},
			prefetchRequirementsOf(VARIANTS_REFERENCE_SCHEMA, false)
		);
	}

	@Test
	@DisplayName("should prefetch the rows of a pick-first ordering with the representative attributes ordering them")
	void shouldPrefetchRepresentativeAttributesForPickFirst() {
		assertArrayEquals(
			new EntityContentRequire[]{referenceContentWithAttributes(VARIANTS_REFERENCE_NAME, ATTRIBUTE_VARIANT)},
			prefetchRequirementsOf(VARIANTS_REFERENCE_SCHEMA, true)
		);
	}

}
