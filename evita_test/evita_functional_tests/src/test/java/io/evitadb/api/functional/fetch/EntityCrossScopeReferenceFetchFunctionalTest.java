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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests verifying cross-scope reference entity fetching behavior. When a product is archived,
 * its references still point to LIVE-scope entities (parameters, parameter groups). This test
 * documents and verifies the asymmetric fetching rules:
 *
 * - ARCHIVED products CAN see LIVE referenced/group entities
 * - LIVE products CANNOT see ARCHIVED referenced/group entities
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Cross-scope reference entity fetching")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
class EntityCrossScopeReferenceFetchFunctionalTest implements EvitaTestSupport {

	private static final String TEST_CATALOG = "crossScopeFetch";
	private static final String PRODUCT = "product";
	private static final String PARAM = "param";
	private static final String PARAM_GROUP = "paramGroup";
	private static final String REF_TO_PARAM = "paramRef";
	private static final String ATTR_LABEL = "label";
	private static final String ATTR_VALUE = "basicValue";
	private static final String ATTR_PRIORITY = "priority";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths("EntityCrossScopeReferenceFetchFunctionalTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths).build()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(PARAM_GROUP)
				.withoutGeneratedPrimaryKey()
				.withAttribute(ATTR_LABEL, String.class, AttributeSchemaEditor::filterable)
				.updateVia(session);
			session.defineEntitySchema(PARAM)
				.withoutGeneratedPrimaryKey()
				.withAttribute(ATTR_VALUE, BigDecimal.class, AttributeSchemaEditor::filterable)
				.updateVia(session);
			session.defineEntitySchema(PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withAttribute(ATTR_LABEL, String.class, thatIs -> thatIs.filterableInScope(Scope.values()))
				.withReferenceToEntity(
					REF_TO_PARAM, PARAM, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioningInScope(Scope.values())
						.withGroupTypeRelatedToEntity(PARAM_GROUP)
						.withAttribute(ATTR_PRIORITY, Integer.class, AttributeSchemaEditor::filterable)
				)
				.updateVia(session);

			// create entities
			session.createNewEntity(PARAM_GROUP, 1)
				.setAttribute(ATTR_LABEL, "Group A")
				.upsertVia(session);
			session.createNewEntity(PARAM, 1)
				.setAttribute(ATTR_VALUE, new BigDecimal("1.5"))
				.upsertVia(session);
			session.createNewEntity(PRODUCT, 1)
				.setAttribute(ATTR_LABEL, "Product One")
				.setReference(REF_TO_PARAM, 1, whichIs -> {
					whichIs.setGroup(PARAM_GROUP, 1);
					whichIs.setAttribute(ATTR_PRIORITY, 5);
				})
				.upsertVia(session);

			session.goLiveAndClose();
		});
	}

	@AfterEach
	void tearDown() throws IOException {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("ARCHIVED product should see LIVE referenced entity with attributes")
	void shouldFetchLiveReferencedEntityForArchivedProduct() {
		archiveProduct();

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity product = session.queryOneSealedEntity(
				query(
					collection(PRODUCT),
					filterBy(scope(Scope.ARCHIVED)),
					require(entityFetch(referenceContent(REF_TO_PARAM, entityFetch(attributeContentAll())))),
					null
				)
			).orElseThrow();

			final ReferenceContract ref = product.getReferences(REF_TO_PARAM).iterator().next();
			final Optional<SealedEntity> refEntity = ref.getReferencedEntity();
			assertTrue(refEntity.isPresent(), "LIVE referenced entity must be visible from ARCHIVED product");
			assertEquals(
				new BigDecimal("1.5"),
				refEntity.get().getAttribute(ATTR_VALUE, BigDecimal.class),
				"Referenced entity attribute must be accessible"
			);
		});
	}

	@Test
	@DisplayName("ARCHIVED product should NOT see LIVE group entity (scope filtering)")
	void shouldNotFetchLiveGroupEntityForArchivedProduct() {
		archiveProduct();

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity product = session.queryOneSealedEntity(
				query(
					collection(PRODUCT),
					filterBy(scope(Scope.ARCHIVED)),
					require(entityFetch(referenceContent(REF_TO_PARAM, entityGroupFetch(attributeContentAll())))),
					null
				)
			).orElseThrow();

			final ReferenceContract ref = product.getReferences(REF_TO_PARAM).iterator().next();
			final Optional<SealedEntity> groupEntity = ref.getGroupEntity();
			assertTrue(
				groupEntity.isEmpty(),
				"LIVE group entity must NOT be visible from ARCHIVED product (scope filtering)"
			);
		});
	}

	@Test
	@DisplayName("LIVE product should NOT see ARCHIVED referenced entity")
	void shouldNotFetchArchivedReferencedEntityForLiveProduct() {
		archiveParam();

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity product = session.queryOneSealedEntity(
				query(
					collection(PRODUCT),
					filterBy(scope(Scope.LIVE)),
					require(entityFetch(referenceContent(REF_TO_PARAM, entityFetch(attributeContentAll())))),
					null
				)
			).orElseThrow();

			final ReferenceContract ref = product.getReferences(REF_TO_PARAM).iterator().next();
			final Optional<SealedEntity> refEntity = ref.getReferencedEntity();
			assertTrue(
				refEntity.isEmpty(),
				"ARCHIVED referenced entity must NOT be visible from LIVE product"
			);
		});
	}

	@Test
	@DisplayName("LIVE product should NOT see ARCHIVED group entity")
	void shouldNotFetchArchivedGroupEntityForLiveProduct() {
		archiveParamGroup();

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity product = session.queryOneSealedEntity(
				query(
					collection(PRODUCT),
					filterBy(scope(Scope.LIVE)),
					require(entityFetch(referenceContent(REF_TO_PARAM, entityGroupFetch(attributeContentAll())))),
					null
				)
			).orElseThrow();

			final ReferenceContract ref = product.getReferences(REF_TO_PARAM).iterator().next();
			final Optional<SealedEntity> groupEntity = ref.getGroupEntity();
			assertTrue(
				groupEntity.isEmpty(),
				"ARCHIVED group entity must NOT be visible from LIVE product"
			);
		});
	}

	@Test
	@DisplayName("Reference attributes should survive product archiving")
	void shouldPreserveReferenceAttributesAfterArchiving() {
		archiveProduct();

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity product = session.queryOneSealedEntity(
				query(
					collection(PRODUCT),
					filterBy(scope(Scope.ARCHIVED)),
					require(entityFetch(referenceContentAllWithAttributes())),
					null
				)
			).orElseThrow();

			final Collection<ReferenceContract> refs = product.getReferences(REF_TO_PARAM);
			assertFalse(refs.isEmpty(), "References must survive archiving");
			final ReferenceContract ref = refs.iterator().next();
			assertEquals(5, ref.getAttribute(ATTR_PRIORITY, Integer.class), "Reference attribute must be preserved");
		});
	}

	@Test
	@DisplayName("Multi-scope query should resolve entities from both scopes")
	void shouldResolveEntitiesFromBothScopesInMultiScopeQuery() {
		archiveProduct();

		this.evita.updateCatalog(TEST_CATALOG, session -> {
			// create a second LIVE product
			session.createNewEntity(PRODUCT, 2)
				.setAttribute(ATTR_LABEL, "Product Two")
				.setReference(REF_TO_PARAM, 1, whichIs -> {
					whichIs.setGroup(PARAM_GROUP, 1);
					whichIs.setAttribute(ATTR_PRIORITY, 3);
				})
				.upsertVia(session);
		});

		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final var response = session.querySealedEntity(
				query(
					collection(PRODUCT),
					filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
					require(entityFetch(
						referenceContent(REF_TO_PARAM,
							entityFetch(attributeContentAll()),
							entityGroupFetch(attributeContentAll())
						)
					)),
					null
				)
			);

			assertEquals(2, response.getTotalRecordCount(), "Both LIVE and ARCHIVED products must be returned");
			for (final SealedEntity product : response.getRecordPage().getData()) {
				final ReferenceContract ref = product.getReferences(REF_TO_PARAM).iterator().next();
				assertTrue(
					ref.getReferencedEntity().isPresent(),
					"Referenced entity must be resolved for product " + product.getPrimaryKey()
				);
				assertTrue(
					ref.getGroupEntity().isPresent(),
					"Group entity must be resolved for product " + product.getPrimaryKey()
				);
			}
		});
	}

	/**
	 * Archives product 1.
	 */
	private void archiveProduct() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(PRODUCT, 1);
		});
	}

	/**
	 * Archives param 1 (referenced entity).
	 */
	private void archiveParam() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(PARAM, 1);
		});
	}

	/**
	 * Archives param group 1 (group entity).
	 */
	private void archiveParamGroup() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.archiveEntity(PARAM_GROUP, 1);
		});
	}

}
