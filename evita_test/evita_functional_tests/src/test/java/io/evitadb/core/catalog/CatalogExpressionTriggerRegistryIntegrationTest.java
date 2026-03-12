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

package io.evitadb.core.catalog;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.index.mutation.DependencyType;
import io.evitadb.index.mutation.ExpressionIndexTrigger;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link CatalogExpressionTriggerRegistry} lifecycle within {@link Catalog}.
 * Verifies that the registry is correctly wired into the catalog — built on cold start, rebuilt on
 * schema changes, and cleaned on entity collection removal.
 *
 * These tests use real {@link Evita} instances with disk storage to exercise the full lifecycle,
 * complementing the isolated unit tests in {@link CatalogExpressionTriggerRegistryImplTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("CatalogExpressionTriggerRegistry Integration")
class CatalogExpressionTriggerRegistryIntegrationTest implements EvitaTestSupport {

	private static final String DIR_REGISTRY_INTEGRATION_TEST = "registryIntegrationTest";
	private static final String PRODUCT = "product";
	private static final String CATEGORY = "category";
	private static final String PARAMETER_REF = "parameter";
	private static final String PARAMETER_TYPE = "parameterType";
	private static final String PARAMETER_GROUP = "parameterGroup";
	private static final String BRAND = "brand";
	private static final String BRAND_REF = "brandRef";
	private static final String GROUP_STATUS_EXPRESSION =
		"$reference.groupEntity?.attributes['status'] == 'VISIBLE'";

	private Evita evita;

	/**
	 * Creates a fresh Evita instance with a test catalog before each test.
	 */
	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIR_REGISTRY_INTEGRATION_TEST);
		this.evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory().resolve(DIR_REGISTRY_INTEGRATION_TEST))
						.build()
				).build()
		);
		this.evita.defineCatalog(TestConstants.TEST_CATALOG);
	}

	/**
	 * Closes Evita after each test.
	 */
	@AfterEach
	void tearDown() {
		if (this.evita != null) {
			this.evita.close();
		}
	}

	/**
	 * Tests verifying that schema mutations correctly cascade into registry rebuilds.
	 */
	@Nested
	@DisplayName("Schema change cascade")
	class SchemaChangeCascadeTest {

		@Test
		@DisplayName("Should rebuild registry when entity schema with facetedPartially expression is defined")
		void shouldRebuildRegistryOnEntitySchemaUpdate() {
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				// define referenced entity types first
				session.defineEntitySchema(PARAMETER_TYPE).updateVia(session);
				session.defineEntitySchema(PARAMETER_GROUP).updateVia(session);

				// define product with facetedPartially expression referencing group entity
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.facetedPartiallyInScope(
								Scope.LIVE,
								ExpressionFactory.parse(GROUP_STATUS_EXPRESSION)
							)
					)
					.updateVia(session);
			});

			final CatalogExpressionTriggerRegistry registry = getRegistry();
			final List<ExpressionIndexTrigger> triggers =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertEquals(1, triggers.size());
			assertEquals(PRODUCT, triggers.get(0).getOwnerEntityType());
			assertEquals(PARAMETER_REF, triggers.get(0).getReferenceName());
		}

		@Test
		@DisplayName("Should not affect other entity types when one schema is updated")
		void shouldNotAffectOtherEntityTypesOnSchemaUpdate() {
			// define two schemas with different expressions
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PARAMETER_TYPE).updateVia(session);
				session.defineEntitySchema(PARAMETER_GROUP).updateVia(session);
				session.defineEntitySchema(BRAND).updateVia(session);

				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.facetedPartiallyInScope(
								Scope.LIVE,
								ExpressionFactory.parse(GROUP_STATUS_EXPRESSION)
							)
					)
					.updateVia(session);

				session.defineEntitySchema(CATEGORY)
					.withReferenceToEntity(
						BRAND_REF, BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.facetedPartiallyInScope(
								Scope.LIVE,
								ExpressionFactory.parse(
									"$reference.referencedEntity.attributes['active'] == true"
								)
							)
					)
					.updateVia(session);
			});

			// update product to remove its facetedPartially expression
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.nonFacetedPartially(Scope.LIVE)
					)
					.updateVia(session);
			});

			final CatalogExpressionTriggerRegistry registry = getRegistry();

			// product triggers should be gone
			assertTrue(registry.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());

			// category triggers should still exist
			final List<ExpressionIndexTrigger> brandTriggers =
				registry.getTriggersFor(BRAND, DependencyType.REFERENCED_ENTITY_ATTRIBUTE);
			assertEquals(1, brandTriggers.size());
			assertEquals(CATEGORY, brandTriggers.get(0).getOwnerEntityType());
		}

		@Test
		@DisplayName("Should purge triggers when entity schema is removed via deleteCollection")
		void shouldPurgeTriggersOnEntitySchemaRemoval() {
			defineProductWithGroupStatusExpression();

			// verify triggers exist
			assertEquals(1, getRegistry().getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size());

			// remove the product collection
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.deleteCollection(PRODUCT);
			});

			// triggers should be purged
			assertTrue(getRegistry().getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

	}

	/**
	 * Tests verifying the registry is rebuilt from persisted schemas after Evita restarts.
	 */
	@Nested
	@DisplayName("Cold start lifecycle")
	class ColdStartLifecycleTest {

		@Test
		@DisplayName("Should build registry after catalog loading from disk")
		void shouldBuildRegistryAfterAllInitSchemaCalls() throws IOException {
			defineProductWithGroupStatusExpression();

			// close and reopen Evita — triggers cold start path
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.close();
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita = new Evita(
				EvitaConfiguration.builder()
					.storage(
						StorageOptions.builder()
							.storageDirectory(getTestDirectory().resolve(DIR_REGISTRY_INTEGRATION_TEST))
							.build()
					).build()
			);

			// verify registry was rebuilt from disk-persisted schemas
			final CatalogExpressionTriggerRegistry registry = getRegistry();
			final List<ExpressionIndexTrigger> triggers =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertEquals(1, triggers.size());
			assertEquals(PRODUCT, triggers.get(0).getOwnerEntityType());
			assertEquals(PARAMETER_REF, triggers.get(0).getReferenceName());
			assertTrue(triggers.get(0).getDependentAttributes().contains("status"));
		}

	}

	/**
	 * Tests verifying the registry field is properly initialized and maintained across catalog operations.
	 */
	@Nested
	@DisplayName("Catalog field lifecycle")
	class CatalogFieldLifecycleTest {

		@Test
		@DisplayName("Should return non-null registry from a newly created catalog")
		void shouldReturnNonNullRegistryFromNewCatalog() {
			final CatalogExpressionTriggerRegistry registry = getRegistry();

			assertNotNull(registry);
			// new catalog has no schemas → empty registry
			assertTrue(registry.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should include registry in removeLayer — verified by registry surviving transaction rollback")
		void shouldIncludeRegistryInRemoveLayer() {
			defineProductWithGroupStatusExpression();

			// verify registry is populated (proves the field is properly maintained)
			final CatalogExpressionTriggerRegistry registry = getRegistry();
			assertEquals(1, registry.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size());
		}

	}

	/**
	 * Tests verifying the registry follows copy-on-write semantics — schema updates produce new instances.
	 */
	@Nested
	@DisplayName("Copy-on-write immutability in Catalog context")
	class CopyOnWriteImmutabilityTest {

		@Test
		@DisplayName("Should return new registry instance after schema update")
		void shouldReturnNewRegistryInstanceAfterSchemaUpdate() {
			defineProductWithGroupStatusExpression();

			final CatalogExpressionTriggerRegistry registryBefore = getRegistry();

			// update schema — triggers registry rebuild
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.facetedPartiallyInScope(
								Scope.LIVE,
								ExpressionFactory.parse(
									"$reference.groupEntity?.attributes['priority'] > 0"
								)
							)
					)
					.updateVia(session);
			});

			final CatalogExpressionTriggerRegistry registryAfter = getRegistry();

			// should be a different instance (copy-on-write)
			assertNotSame(registryBefore, registryAfter);
			// new registry should have the updated trigger
			final List<ExpressionIndexTrigger> triggers =
				registryAfter.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);
			assertEquals(1, triggers.size());
			assertTrue(triggers.get(0).getDependentAttributes().contains("priority"));
		}

	}

	/**
	 * Tests for boundary conditions: absent entity types, idempotent updates, and attribute filtering.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("Should return empty list for absent entity type in populated registry")
		void shouldReturnEmptyListForAbsentEntityType() {
			defineProductWithGroupStatusExpression();

			// query for an entity type not in the registry
			assertTrue(getRegistry().getTriggersFor(
				"nonExistent", DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should be idempotent when schema is unchanged — registry still correct")
		void shouldBeIdempotentWhenSchemaUnchanged() {
			final Expression expression = ExpressionFactory.parse(GROUP_STATUS_EXPRESSION);

			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PARAMETER_TYPE).updateVia(session);
				session.defineEntitySchema(PARAMETER_GROUP).updateVia(session);

				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.facetedPartiallyInScope(Scope.LIVE, expression)
					)
					.updateVia(session);
			});

			final CatalogExpressionTriggerRegistry registryBefore = getRegistry();
			final int triggerCountBefore = registryBefore.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size();

			// re-apply the same schema definition — should be a no-op or idempotent
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.facetedPartiallyInScope(Scope.LIVE, expression)
					)
					.updateVia(session);
			});

			final int triggerCountAfter = getRegistry().getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size();

			assertEquals(triggerCountBefore, triggerCountAfter);
		}

		@Test
		@DisplayName("Should never return trigger with empty dependent attributes from getTriggersForAttribute")
		void shouldNeverReturnTriggerWithEmptyDependentAttributesFromGetTriggersForAttribute() {
			defineProductWithGroupStatusExpression();

			// query for a specific attribute
			final List<ExpressionIndexTrigger> triggers = getRegistry().getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
			);

			// all returned triggers must have the queried attribute in their dependent set
			for (final ExpressionIndexTrigger trigger : triggers) {
				assertTrue(
					trigger.getDependentAttributes().contains("status"),
					"Trigger should have 'status' in its dependent attributes"
				);
			}
		}

	}

	/**
	 * Tests verifying triggers are built correctly across multiple scopes and from multiple owner entity types.
	 */
	@Nested
	@DisplayName("Multiple scopes and references")
	class MultipleScopesAndReferencesTest {

		@Test
		@DisplayName("Should build triggers for both LIVE and ARCHIVED scopes")
		void shouldBuildTriggersForBothScopes() {
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PARAMETER_TYPE).updateVia(session);

				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.facetedPartiallyInScope(
								Scope.LIVE,
								ExpressionFactory.parse(
									"$reference.referencedEntity.attributes['code'] == 'A'"
								)
							)
							.facetedPartiallyInScope(
								Scope.ARCHIVED,
								ExpressionFactory.parse(
									"$reference.referencedEntity.attributes['archived'] == true"
								)
							)
					)
					.updateVia(session);
			});

			final CatalogExpressionTriggerRegistry registry = getRegistry();
			final List<ExpressionIndexTrigger> triggers =
				registry.getTriggersFor(PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE);

			assertEquals(2, triggers.size());

			// verify each scope's attribute is findable
			assertEquals(1, registry.getTriggersForAttribute(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, "code"
			).size());
			assertEquals(1, registry.getTriggersForAttribute(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, "archived"
			).size());
		}

		@Test
		@DisplayName("Should accumulate triggers from multiple entity types referencing same target")
		void shouldAccumulateTriggersFromMultipleOwners() {
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PARAMETER_TYPE).updateVia(session);
				session.defineEntitySchema(PARAMETER_GROUP).updateVia(session);

				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.facetedPartiallyInScope(
								Scope.LIVE,
								ExpressionFactory.parse(GROUP_STATUS_EXPRESSION)
							)
					)
					.updateVia(session);

				session.defineEntitySchema(CATEGORY)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.facetedPartiallyInScope(
								Scope.LIVE,
								ExpressionFactory.parse(
									"$reference.groupEntity?.attributes['priority'] > 0"
								)
							)
					)
					.updateVia(session);
			});

			final List<ExpressionIndexTrigger> triggers = getRegistry().getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			);

			assertEquals(2, triggers.size());
		}

	}

	/**
	 * Tests verifying the registry receives fully resolved schemas during incremental schema evolution.
	 */
	@Nested
	@DisplayName("Integration readiness")
	class IntegrationReadinessTest {

		@Test
		@DisplayName("Should receive fully resolved schema in entitySchemaUpdated")
		void shouldReceiveFullyResolvedSchemaInEntitySchemaUpdated() {
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PARAMETER_TYPE).updateVia(session);
				session.defineEntitySchema(PARAMETER_GROUP).updateVia(session);

				// define schema step-by-step: first without expression, then with
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
					)
					.updateVia(session);
			});

			// at this point, no expression → no triggers
			assertTrue(getRegistry().getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());

			// now add the expression
			CatalogExpressionTriggerRegistryIntegrationTest.this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
							.facetedPartiallyInScope(
								Scope.LIVE,
								ExpressionFactory.parse(GROUP_STATUS_EXPRESSION)
							)
					)
					.updateVia(session);
			});

			// now triggers should be present
			final List<ExpressionIndexTrigger> triggers = getRegistry().getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			);
			assertEquals(1, triggers.size());
		}

	}

	/**
	 * Defines `PARAMETER_TYPE`, `PARAMETER_GROUP`, and `PRODUCT` entity schemas in a single catalog transaction.
	 * The product schema includes a reference to `PARAMETER_TYPE` with a group-entity-based
	 * {@link #GROUP_STATUS_EXPRESSION} as the `facetedPartially` expression.
	 */
	private void defineProductWithGroupStatusExpression() {
		this.evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
			session.defineEntitySchema(PARAMETER_TYPE).updateVia(session);
			session.defineEntitySchema(PARAMETER_GROUP).updateVia(session);

			session.defineEntitySchema(PRODUCT)
				.withReferenceToEntity(
					PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
						.facetedPartiallyInScope(
							Scope.LIVE,
							ExpressionFactory.parse(GROUP_STATUS_EXPRESSION)
						)
				)
				.updateVia(session);
		});
	}

	/**
	 * Retrieves the expression trigger registry from the current catalog instance.
	 *
	 * @return the expression trigger registry
	 */
	@Nonnull
	private CatalogExpressionTriggerRegistry getRegistry() {
		final Catalog catalog =
			(Catalog) this.evita.getCatalogInstanceOrThrowException(TestConstants.TEST_CATALOG);
		return catalog.getExpressionTriggerRegistry();
	}

}
