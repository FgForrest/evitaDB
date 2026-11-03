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

package io.evitadb.core.query;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.test.Entities;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.facetCalculationRules;
import static io.evitadb.api.query.QueryConstraints.facetGroupsConjunction;
import static io.evitadb.api.query.QueryConstraints.facetGroupsNegation;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the facet relations {@link QueryPlanningContext} memoizes for the reference summary answer every question
 * with its own answer: a relation resolved for one level, one reference or one relation type must never be handed out
 * for another one. Every question is asked in both orders, because a memo keyed too coarsely answers the second
 * question with the answer of the first one, whichever that is.
 *
 * The relations are declared without group filters, so no filter is planned and the context needs no indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Facet relations resolved by the query planning context")
@Tag(TestTags.ENGINE)
@Tag(TestTags.QUERY)
@Tag(TestTags.FACET)
class QueryPlanningContextFacetRelationTest {
	private static final ReferenceSchema BRAND_REFERENCE = createReferenceSchema(Entities.BRAND);
	private static final ReferenceSchema PARAMETER_REFERENCE = createReferenceSchema(Entities.PARAMETER);
	private static final int GROUP_ID = 1;

	@DisplayName("Should resolve the relation within a group apart from the relation between groups")
	@Test
	void shouldResolveRelationOfEachLevelOnItsOwn() {
		final RequireConstraint[] relations = {
			facetCalculationRules(FacetRelationType.DISJUNCTION, FacetRelationType.DISJUNCTION),
			facetGroupsConjunction(Entities.BRAND, WITH_DIFFERENT_FACETS_IN_GROUP)
		};

		final QueryPlanningContext withinFirst = createContext(relations);
		assertTrue(withinFirst.isFacetGroupConjunction(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP));
		assertFalse(withinFirst.isFacetGroupConjunction(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS));
		assertEquals(
			FacetRelationType.CONJUNCTION,
			withinFirst.getFacetRelationType(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP)
		);
		assertEquals(
			FacetRelationType.DISJUNCTION,
			withinFirst.getFacetRelationType(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS)
		);

		final QueryPlanningContext betweenFirst = createContext(relations);
		assertFalse(betweenFirst.isFacetGroupConjunction(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS));
		assertTrue(betweenFirst.isFacetGroupConjunction(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP));
		assertEquals(
			FacetRelationType.DISJUNCTION,
			betweenFirst.getFacetRelationType(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS)
		);
		assertEquals(
			FacetRelationType.CONJUNCTION,
			betweenFirst.getFacetRelationType(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP)
		);
	}

	@DisplayName("Should resolve the relations of each reference on their own")
	@Test
	void shouldResolveRelationOfEachReferenceOnItsOwn() {
		final RequireConstraint[] relations = {
			facetCalculationRules(FacetRelationType.DISJUNCTION, FacetRelationType.DISJUNCTION),
			facetGroupsNegation(Entities.BRAND, WITH_DIFFERENT_GROUPS)
		};

		final QueryPlanningContext declaringFirst = createContext(relations);
		assertEquals(
			FacetRelationType.NEGATION,
			declaringFirst.getFacetRelationType(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS)
		);
		assertEquals(
			FacetRelationType.DISJUNCTION,
			declaringFirst.getFacetRelationType(PARAMETER_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS)
		);
		assertTrue(declaringFirst.isFacetGroupNegation(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS));
		assertFalse(declaringFirst.isFacetGroupNegation(PARAMETER_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS));

		final QueryPlanningContext defaultFirst = createContext(relations);
		assertEquals(
			FacetRelationType.DISJUNCTION,
			defaultFirst.getFacetRelationType(PARAMETER_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS)
		);
		assertEquals(
			FacetRelationType.NEGATION,
			defaultFirst.getFacetRelationType(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS)
		);
		assertFalse(defaultFirst.isFacetGroupNegation(PARAMETER_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS));
		assertTrue(defaultFirst.isFacetGroupNegation(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_GROUPS));
	}

	@DisplayName("Should decide each relation type on its own")
	@Test
	void shouldDecideEachRelationTypeOnItsOwn() {
		final RequireConstraint[] relations = {facetGroupsConjunction(Entities.BRAND, WITH_DIFFERENT_FACETS_IN_GROUP)};

		final QueryPlanningContext conjunctionFirst = createContext(relations);
		assertTrue(conjunctionFirst.isFacetGroupConjunction(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP));
		assertFalse(conjunctionFirst.isFacetGroupNegation(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP));
		assertFalse(
			conjunctionFirst.isFacetGroupExclusivity(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP)
		);

		final QueryPlanningContext negationFirst = createContext(relations);
		assertFalse(negationFirst.isFacetGroupNegation(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP));
		assertFalse(negationFirst.isFacetGroupExclusivity(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP));
		assertTrue(negationFirst.isFacetGroupConjunction(BRAND_REFERENCE, GROUP_ID, WITH_DIFFERENT_FACETS_IN_GROUP));
	}

	/**
	 * Builds a planning context of a query over products declaring the passed relation requirements. The catalog,
	 * the session and the cache supervisor are mocked, because the relations of groups without a filter are decided
	 * by the request alone.
	 *
	 * @param relations the relation requirements of the query
	 * @return the planning context
	 */
	@Nonnull
	private static QueryPlanningContext createContext(@Nonnull RequireConstraint[] relations) {
		final Map<EntityIndexKey, EntityIndex> noIndexes = Map.of();
		return new QueryPlanningContext(
			null,
			Mockito.mock(Catalog.class),
			null,
			Mockito.mock(EvitaSession.class),
			new EvitaRequest(
				query(collection(Entities.PRODUCT), require(relations)),
				OffsetDateTime.now(),
				EntityReference.class,
				null
			),
			null,
			noIndexes,
			Map.of(),
			Mockito.mock(CacheSupervisor.class)
		);
	}

	/**
	 * Creates the schema of a faceted reference to the passed entity type, grouped by the same type.
	 *
	 * @param entityType the referenced entity type
	 * @return the reference schema
	 */
	@Nonnull
	private static ReferenceSchema createReferenceSchema(@Nonnull String entityType) {
		return ReferenceSchema._internalBuild(
			entityType, entityType, false, Cardinality.ZERO_OR_ONE, entityType, false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
			},
			new Scope[]{Scope.LIVE}
		);
	}

}
