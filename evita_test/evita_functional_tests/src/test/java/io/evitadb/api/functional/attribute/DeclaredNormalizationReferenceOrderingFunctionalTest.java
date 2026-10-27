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
package io.evitadb.api.functional.attribute;

import io.evitadb.api.query.require.DefaultPrefetchRequirementCollector;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.requestResponse.schema.OrderBehaviour.NULLS_LAST;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.removeCharacters;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Checks that fetched reference comparators bind the collection owning the fetched entity. */
@Tag(ENGINE)
@Tag(ORDER)
@Tag(REFERENCE)
@DisplayName("Reference normalization follows the fetched owner")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeclaredNormalizationReferenceOrderingFunctionalTest implements EvitaTestSupport {
	private TestPaths paths;
	private Evita evita;

	/** Shares one owner with deliberately different raw and normalized reference order across all routes. */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("DeclaredNormalizationReferenceOrdering");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute("lookup", String.class, a -> a.uniqueGlobally().nullable())
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Target").withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema("Owner").withoutGeneratedPrimaryKey().withGlobalAttribute("lookup")
				.withReferenceToEntity("targets", "Target", Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering()
					.withAttribute("code", String.class, a -> a.sortable().normalized(removeCharacters("z-")))
					.withAttribute("number", Integer.class)
					.withSortableAttributeCompound("compound", new AttributeElement("code", ASC, NULLS_LAST),
						new AttributeElement("number", ASC, NULLS_LAST)))
				.updateVia(session);
			session.defineEntitySchema("Root").withoutGeneratedPrimaryKey()
				.withReferenceToEntity("owners", "Owner", Cardinality.ZERO_OR_MORE).updateVia(session);
			for (int id = 1; id <= 3; id++) {
				session.createNewEntity("Target", id).upsertVia(session);
			}
			session.createNewEntity("Owner", 1).setAttribute("lookup", "owner")
				.setReference("targets", 1, r -> r.setAttribute("code", "z-a").setAttribute("number", 1))
				.setReference("targets", 2, r -> r.setAttribute("code", "b").setAttribute("number", 1))
				.setReference("targets", 3, r -> r.setAttribute("code", "c").setAttribute("number", 1))
				.upsertVia(session);
			session.createNewEntity("Root", 1).setReference("owners", 1).upsertVia(session);
			session.goLiveAndClose();
		});
	}

	/** Closes the shared engine after every read route has completed. */
	@AfterAll
	void tearDown() {
		if (this.evita != null) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/** Scalar and compound ordering must agree across direct, collection-less and nested fetches, including pages. */
	@TestFactory
	@DisplayName("Scalar and compound reference ordering uses owner policy on every fetch route and page")
	Stream<DynamicTest> shouldOrderReferencesUsingTheirOwnersPolicy() {
		return Stream.of("code", "compound").flatMap(attribute -> Stream.of("qualified", "collectionless", "nested")
			.flatMap(route -> Stream.of(false, true).map(paged -> DynamicTest.dynamicTest(
				attribute + "/" + route + "/paged=" + paged, () -> assertOrder(attribute, route, paged)))));
	}

	/** Pins comparator construction when the supplied context belongs to a root collection or the catalog. */
	@TestFactory
	@DisplayName("Comparator construction binds the supplied entity schema even with a foreign query context")
	Stream<DynamicTest> shouldBindComparatorToSuppliedOwner() {
		return Stream.of("code", "compound").flatMap(attribute -> Stream.of(false, true).map(collectionless ->
			DynamicTest.dynamicTest(attribute + "/collectionless=" + collectionless, () -> {
				final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
				final EntityCollection owner = catalog.getCollectionForEntityOrThrowException("Owner");
				final QueryPlanningContext context = mock(QueryPlanningContext.class);
				when(context.getCatalogSchema()).thenReturn(catalog.getSchema());
				when(context.getScopes()).thenReturn(Set.of(Scope.LIVE));
				when(context.getAttributeNormalizationResolver()).thenReturn(collectionless ?
					catalog.getAttributeNormalizationResolver() :
					catalog.getCollectionForEntityOrThrowException("Root").getAttributeNormalizationResolver());
				when(context.getEntityCollectionOrThrowException(eq("Owner"), anyString())).thenReturn(owner);
				this.evita.queryCatalog(TEST_CATALOG, session -> {
					final SealedEntity entity = session.getEntity("Owner", 1,
						referenceContentWithAttributes("targets")).orElseThrow();
					final List<ReferenceContract> references = new ArrayList<>(entity.getReferences("targets"));
					references.sort(ReferenceOrderByVisitor.getComparator(context,
						new DefaultPrefetchRequirementCollector(), orderBy(attributeNatural(attribute)),
						owner.getInternalSchema(), owner.getInternalSchema().getReferenceOrThrowException("targets"))
						.comparator());
					assertEquals(List.of(1, 2, 3), references.stream()
						.map(ReferenceContract::getReferencedPrimaryKey).toList());
					return null;
				});
			})));
	}

	/**
	 * Reads the same owner through each query anchor and compares the actual returned reference sequence.
	 *
	 * @param attribute name of the owner's reference attribute or compound to order by
	 * @param route query anchor: `nested` (through a referenced entity of `Root`), `qualified` (collection stated in
	 *     the query), any other value for a collection-less query
	 * @param paged `true` to fetch only the second reference through a page, `false` to fetch all of them
	 */
	private void assertOrder(@Nonnull String attribute, @Nonnull String route, boolean paged) {
		final ReferenceContent references = referenceContentWithAttributes("targets", null,
			orderBy(attributeNatural(attribute)), attributeContentAll(), null, null, paged ? page(2, 1) : null);
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity owner;
			if (route.equals("nested")) {
				final SealedEntity root = session.queryOne(query(collection("Root"), filterBy(entityPrimaryKeyInSet(1)),
					require(entityFetch(referenceContent("owners", entityFetch(references))))),
					SealedEntity.class).orElseThrow();
				owner = root.getReferences("owners").iterator().next().getReferencedEntity().orElseThrow();
			} else {
				owner = session.queryOne(query(route.equals("qualified") ? collection("Owner") : null,
					filterBy(attributeEquals("lookup", "owner")), require(entityFetch(references))),
					SealedEntity.class).orElseThrow();
			}
			assertEquals(paged ? List.of(2) : List.of(1, 2, 3), owner.getReferences("targets").stream()
				.map(ReferenceContract::getReferencedPrimaryKey).toList());
			return null;
		});
	}
}
