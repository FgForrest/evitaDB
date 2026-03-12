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

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.expression.proxy.ExpressionProxyDescriptor;
import io.evitadb.core.expression.proxy.ExpressionProxyFactory;
import io.evitadb.core.expression.trigger.FacetExpressionTriggerFactory;
import io.evitadb.core.expression.trigger.FacetExpressionTriggerImpl;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.index.mutation.DependencyType;
import io.evitadb.index.mutation.ExpressionIndexTrigger;
import io.evitadb.index.mutation.FacetExpressionTrigger;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CatalogExpressionTriggerRegistryImpl} — the immutable, copy-on-write registry that maps
 * mutated entity types to expression index triggers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("CatalogExpressionTriggerRegistryImpl")
class CatalogExpressionTriggerRegistryImplTest {

	private static final String PRODUCT = "product";
	private static final String CATEGORY = "category";
	private static final String PARAMETER_REF = "parameter";
	private static final String ALT_PARAMETER_REF = "altParameter";
	private static final String PARAMETER_TYPE = "parameterType";
	private static final String PARAMETER_GROUP = "parameterGroup";
	private static final String BRAND = "brand";
	private static final String BRAND_REF = "brandRef";

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		"testCatalog",
		NamingConvention.generate("testCatalog"),
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	@Nested
	@DisplayName("Empty registry behavior")
	class EmptyRegistryTest {

		@Test
		@DisplayName("Should return empty list from getTriggersFor on empty registry")
		void shouldReturnEmptyListFromGetTriggersForOnEmptyRegistry() {
			final List<ExpressionIndexTrigger> result = CatalogExpressionTriggerRegistry.EMPTY.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should return empty list from getTriggersForAttribute on empty registry")
		void shouldReturnEmptyListFromGetTriggersForAttributeOnEmptyRegistry() {
			final List<ExpressionIndexTrigger> result =
				CatalogExpressionTriggerRegistry.EMPTY.getTriggersForAttribute(
					PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
				);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should return empty registry when rebuilding empty registry with empty triggers")
		void shouldReturnEmptyRegistryWhenRebuildingEmptyRegistryWithEmptyTriggers() {
			final CatalogExpressionTriggerRegistry rebuilt =
				CatalogExpressionTriggerRegistry.EMPTY.rebuildForEntityType(PRODUCT, List.of());

			assertTrue(rebuilt.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE).isEmpty());
		}

	}

	@Nested
	@DisplayName("getTriggersFor — broad lookup")
	class GetTriggersForTest {

		@Test
		@DisplayName("Should return singleton list for single trigger")
		void shouldReturnSingletonListForSingleTrigger() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertEquals(1, result.size());
			assertEquals(PRODUCT, result.get(0).getOwnerEntityType());
		}

		@Test
		@DisplayName("Should return all triggers for same key")
		void shouldReturnAllTriggersForSameKey() {
			// two references on same owner, both referencing same group entity with expressions
			final CatalogExpressionTriggerRegistry registry = buildRegistryFromSchemas(
				buildEntitySchemaWithTwoGroupRefs(
					PRODUCT, PARAMETER_REF, ALT_PARAMETER_REF,
					PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'",
					"$reference.groupEntity?.attributes['priority'] > 0"
				)
			);

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertEquals(2, result.size());
		}

		@Test
		@DisplayName("Should return empty for non-matching entity type")
		void shouldReturnEmptyForNonMatchingEntityType() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(BRAND, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should return empty for non-matching dependency type")
		void shouldReturnEmptyForNonMatchingDependencyType() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.REFERENCED_ENTITY_ATTRIBUTE);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should isolate triggers from different entity types")
		void shouldIsolateTriggersFromDifferentEntityTypes() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryFromSchemas(
				buildEntitySchemaWithGroupRef(
					PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
				),
				buildEntitySchemaWithRefEntityExpression(
					CATEGORY, BRAND_REF, BRAND,
					"$reference.referencedEntity.attributes['active'] == true"
				)
			);

			final List<ExpressionIndexTrigger> groupTriggers =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);
			final List<ExpressionIndexTrigger> brandTriggers =
				registry.getTriggersFor(BRAND, DependencyType.REFERENCED_ENTITY_ATTRIBUTE);

			assertEquals(1, groupTriggers.size());
			assertEquals(PRODUCT, groupTriggers.get(0).getOwnerEntityType());
			assertEquals(1, brandTriggers.size());
			assertEquals(CATEGORY, brandTriggers.get(0).getOwnerEntityType());
		}

		@Test
		@DisplayName("Should isolate triggers by dependency type")
		void shouldIsolateTriggersByDependencyType() {
			// same entity type, different dependency types
			final CatalogExpressionTriggerRegistry registry = buildRegistryFromSchemas(
				buildEntitySchemaWithRefEntityExpression(
					PRODUCT, PARAMETER_REF, PARAMETER_TYPE,
					"$reference.referencedEntity.attributes['code'] == 'A'"
				)
			);

			final List<ExpressionIndexTrigger> refTriggers =
				registry.getTriggersFor(PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE);
			final List<ExpressionIndexTrigger> groupTriggers =
				registry.getTriggersFor(PARAMETER_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertEquals(1, refTriggers.size());
			assertTrue(groupTriggers.isEmpty());
		}

	}

	@Nested
	@DisplayName("getTriggersForAttribute — filtered lookup")
	class GetTriggersForAttributeTest {

		@Test
		@DisplayName("Should return trigger for matching attribute")
		void shouldReturnTriggerForMatchingAttribute() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final List<ExpressionIndexTrigger> result = registry.getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
			);

			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("Should return empty for non-matching attribute")
		void shouldReturnEmptyForNonMatchingAttribute() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final List<ExpressionIndexTrigger> result = registry.getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "displayOrder"
			);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should return only matching triggers from multiple")
		void shouldReturnOnlyMatchingTriggersFromMultiple() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryFromSchemas(
				buildEntitySchemaWithTwoGroupRefs(
					PRODUCT, PARAMETER_REF, ALT_PARAMETER_REF,
					PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'",
					"$reference.groupEntity?.attributes['priority'] > 0"
				)
			);

			final List<ExpressionIndexTrigger> statusTriggers = registry.getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
			);
			final List<ExpressionIndexTrigger> priorityTriggers = registry.getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "priority"
			);

			assertEquals(1, statusTriggers.size());
			assertEquals(PARAMETER_REF, statusTriggers.get(0).getReferenceName());
			assertEquals(1, priorityTriggers.size());
			assertEquals(ALT_PARAMETER_REF, priorityTriggers.get(0).getReferenceName());
		}

		@Test
		@DisplayName("Should match trigger with multiple dependent attributes")
		void shouldMatchTriggerWithMultipleDependentAttributes() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'X' " +
					"&& $reference.groupEntity?.attributes['priority'] > 0"
			);

			// should match on either attribute
			assertEquals(1, registry.getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
			).size());
			assertEquals(1, registry.getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "priority"
			).size());
		}

		@Test
		@DisplayName("Should return empty when entity type mismatches despite attribute match")
		void shouldReturnEmptyWhenEntityTypeMismatchesDespiteAttributeMatch() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final List<ExpressionIndexTrigger> result = registry.getTriggersForAttribute(
				BRAND, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
			);

			assertTrue(result.isEmpty());
		}

	}

	@Nested
	@DisplayName("Returned list immutability")
	class ReturnedListImmutabilityTest {

		@Test
		@DisplayName("Should return unmodifiable list from getTriggersFor")
		void shouldReturnUnmodifiableListFromGetTriggersFor() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertThrows(UnsupportedOperationException.class, () -> result.add(null));
		}

		@Test
		@DisplayName("Should return unmodifiable list from getTriggersForAttribute")
		void shouldReturnUnmodifiableListFromGetTriggersForAttribute() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final List<ExpressionIndexTrigger> result = registry.getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
			);

			assertThrows(UnsupportedOperationException.class, () -> result.add(null));
		}

	}

	@Nested
	@DisplayName("rebuildForEntityType — immutability and copy-on-write")
	class RebuildImmutabilityTest {

		@Test
		@DisplayName("Should return new instance after rebuild")
		void shouldReturnNewInstanceAfterRebuild() {
			final CatalogExpressionTriggerRegistry original = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, List.of());

			assertNotSame(original, rebuilt);
		}

		@Test
		@DisplayName("Should not modify original registry after rebuild")
		void shouldNotModifyOriginalRegistryAfterRebuild() {
			final CatalogExpressionTriggerRegistry original = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			original.rebuildForEntityType(PRODUCT, List.of());

			// original still has the trigger
			assertEquals(1, original.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size());
		}

		@Test
		@DisplayName("Should reflect changes in new instance after rebuild")
		void shouldReflectChangesInNewInstanceAfterRebuild() {
			final CatalogExpressionTriggerRegistry original = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			// rebuild with new triggers from a different expression
			final List<ExpressionIndexTrigger> newTriggers = buildTriggersForGroupRef(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['priority'] > 0"
			);
			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, newTriggers);

			final List<ExpressionIndexTrigger> result =
				rebuilt.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);
			assertEquals(1, result.size());
			assertTrue(result.get(0).getDependentAttributes().contains("priority"));
		}

		@Test
		@DisplayName("Should preserve other entity types after rebuild")
		void shouldPreserveOtherEntityTypesAfterRebuild() {
			final CatalogExpressionTriggerRegistry original = buildRegistryFromSchemas(
				buildEntitySchemaWithGroupRef(
					PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
				),
				buildEntitySchemaWithRefEntityExpression(
					CATEGORY, BRAND_REF, BRAND,
					"$reference.referencedEntity.attributes['active'] == true"
				)
			);

			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, List.of());

			// category triggers preserved
			assertEquals(1, rebuilt.getTriggersFor(
				BRAND, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).size());
			// product triggers removed
			assertTrue(rebuilt.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should remove entity type triggers when rebuilt with empty list")
		void shouldRemoveEntityTypeTriggersWhenRebuiltWithEmptyList() {
			final CatalogExpressionTriggerRegistry original = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, List.of());

			assertTrue(rebuilt.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should return EMPTY singleton when last trigger is purged")
		void shouldReturnEmptySingletonWhenLastTriggerIsPurged() {
			final CatalogExpressionTriggerRegistry original = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, List.of());

			assertSame(CatalogExpressionTriggerRegistry.EMPTY, rebuilt);
		}

		@Test
		@DisplayName("Should handle no-op rebuild for unknown entity type")
		void shouldHandleNoOpRebuildForUnknownEntityType() {
			final CatalogExpressionTriggerRegistry original = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			// rebuild for a type that has no triggers — should preserve original triggers
			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType("nonExistent", List.of());

			final List<ExpressionIndexTrigger> result =
				rebuilt.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);
			assertEquals(1, result.size());
			assertEquals(PRODUCT, result.get(0).getOwnerEntityType());
		}

		@Test
		@DisplayName("Should handle multiple sequential rebuilds correctly")
		void shouldHandleMultipleSequentialRebuildsCorrectly() {
			// start with product triggers
			final CatalogExpressionTriggerRegistry initial = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			// rebuild A: add category triggers
			final List<ExpressionIndexTrigger> categoryTriggers = buildTriggersForGroupRef(
				CATEGORY, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['priority'] > 0"
			);
			final CatalogExpressionTriggerRegistry afterCategoryAdd =
				initial.rebuildForEntityType(CATEGORY, categoryTriggers);

			// rebuild B: add brand triggers (new reference on product → separate entity type)
			final List<ExpressionIndexTrigger> brandTriggers = buildTriggersForSchema(
				buildEntitySchemaWithRefEntityExpression(
					PRODUCT, BRAND_REF, BRAND,
					"$reference.referencedEntity.attributes['active'] == true"
				)
			);
			// rebuild product with both old group triggers + new ref triggers
			final List<ExpressionIndexTrigger> allProductTriggers = new ArrayList<>();
			allProductTriggers.addAll(buildTriggersForGroupRef(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			));
			allProductTriggers.addAll(brandTriggers);
			final CatalogExpressionTriggerRegistry afterBrandAdd =
				afterCategoryAdd.rebuildForEntityType(PRODUCT, allProductTriggers);

			// rebuild A again: replace product group triggers with new expression
			final List<ExpressionIndexTrigger> updatedProductTriggers = buildTriggersForGroupRef(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['weight'] > 10"
			);
			final CatalogExpressionTriggerRegistry afterProductUpdate =
				afterBrandAdd.rebuildForEntityType(PRODUCT, updatedProductTriggers);

			// verify: category triggers still intact
			final List<ExpressionIndexTrigger> groupTriggers =
				afterProductUpdate.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);
			assertEquals(2, groupTriggers.size());

			// verify: brand triggers removed (product was rebuilt without them)
			assertTrue(afterProductUpdate.getTriggersFor(
				BRAND, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).isEmpty());

			// verify: product group trigger has the updated attribute dependency
			final List<ExpressionIndexTrigger> weightTriggers =
				afterProductUpdate.getTriggersForAttribute(
					PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "weight"
				);
			assertEquals(1, weightTriggers.size());
			assertEquals(PRODUCT, weightTriggers.get(0).getOwnerEntityType());

			// verify: old "status" attribute is no longer dependent for product
			final List<ExpressionIndexTrigger> statusTriggers =
				afterProductUpdate.getTriggersForAttribute(
					PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
				);
			assertTrue(
				statusTriggers.isEmpty(),
				"Product trigger should no longer depend on 'status' after rebuild"
			);
		}

	}

	@Nested
	@DisplayName("Local-only trigger handling in registry")
	class LocalOnlyTriggerHandlingTest {

		@Test
		@DisplayName("Should silently skip local-only triggers in rebuild")
		void shouldSilentlySkipLocalOnlyTriggersInRebuild() {
			// create a local-only trigger (mutatedEntityType=null, dependencyType=null)
			final Expression expression = ExpressionFactory.parse("true");
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);
			final FacetExpressionTriggerImpl localOnlyTrigger = new FacetExpressionTriggerImpl(
				PRODUCT, PARAMETER_REF, Scope.LIVE, expression, descriptor
			);

			// rebuild empty registry with local-only trigger
			final CatalogExpressionTriggerRegistry rebuilt =
				CatalogExpressionTriggerRegistry.EMPTY.rebuildForEntityType(
					PRODUCT, List.of(localOnlyTrigger)
				);

			// local-only trigger should not appear under any lookup
			assertTrue(rebuilt.getTriggersFor(
				PRODUCT, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).isEmpty());
			assertTrue(rebuilt.getTriggersFor(
				PRODUCT, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
			assertTrue(rebuilt.getTriggersFor(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).isEmpty());
			assertTrue(rebuilt.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());

			// the returned registry should be the EMPTY singleton since no triggers were inserted
			assertSame(CatalogExpressionTriggerRegistry.EMPTY, rebuilt);
		}

		@Test
		@DisplayName("Should throw when trigger has mutatedEntityType but null dependencyType")
		void shouldThrowWhenTriggerHasMutatedEntityTypeButNullDependencyType() {
			final ExpressionIndexTrigger inconsistentTrigger = createInconsistentTrigger(
				PARAMETER_GROUP, null
			);

			assertThrows(
				IllegalStateException.class,
				() -> CatalogExpressionTriggerRegistry.EMPTY.rebuildForEntityType(
					PRODUCT, List.of(inconsistentTrigger)
				)
			);
		}

		@Test
		@DisplayName("Should throw when trigger has dependencyType but null mutatedEntityType")
		void shouldThrowWhenTriggerHasDependencyTypeButNullMutatedEntityType() {
			final ExpressionIndexTrigger inconsistentTrigger = createInconsistentTrigger(
				null, DependencyType.GROUP_ENTITY_ATTRIBUTE
			);

			assertThrows(
				IllegalStateException.class,
				() -> CatalogExpressionTriggerRegistry.EMPTY.rebuildForEntityType(
					PRODUCT, List.of(inconsistentTrigger)
				)
			);
		}

	}

	@Nested
	@DisplayName("Key inversion correctness in rebuild")
	class KeyInversionTest {

		@Test
		@DisplayName("Should index triggers under mutated entity type, not owner")
		void shouldIndexTriggersUnderMutatedEntityTypeNotOwner() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryWithGroupEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);

			// trigger is under parameterGroup (mutated), not product (owner)
			assertEquals(1, registry.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size());
			assertTrue(registry.getTriggersFor(
				PRODUCT, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should clean stale triggers from all keys on rebuild")
		void shouldCleanStaleTriggerFromAllKeysOnRebuild() {
			// product references both parameterType (REFERENCED) and parameterGroup (GROUP)
			final EntitySchemaContract schema = buildEntitySchema(PRODUCT, builder ->
				builder.withReferenceTo(
					PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.withGroupType(PARAMETER_GROUP)
						.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(
							"$reference.referencedEntity.attributes['code'] == 'A' " +
								"&& $reference.groupEntity?.attributes['status'] == 'VISIBLE'"
						))
				)
			);

			final CatalogExpressionTriggerRegistry original = buildRegistryFromSchemas(schema);

			// both keys should have triggers
			assertEquals(1, original.getTriggersFor(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).size());
			assertEquals(1, original.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size());

			// rebuild product with empty triggers — should clean both keys
			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, List.of());

			assertTrue(rebuilt.getTriggersFor(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).isEmpty());
			assertTrue(rebuilt.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should not remove triggers from other owners on rebuild")
		void shouldNotRemoveTriggersFromOtherOwnersOnRebuild() {
			final CatalogExpressionTriggerRegistry original = buildRegistryFromSchemas(
				buildEntitySchemaWithGroupRef(
					PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
				),
				buildEntitySchemaWithGroupRef(
					CATEGORY, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['priority'] > 0"
				)
			);

			// both product and category have triggers under parameterGroup
			assertEquals(2, original.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size());

			// rebuild only product with empty
			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, List.of());

			// category trigger preserved
			final List<ExpressionIndexTrigger> remaining =
				rebuilt.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);
			assertEquals(1, remaining.size());
			assertEquals(CATEGORY, remaining.get(0).getOwnerEntityType());
		}

		@Test
		@DisplayName("Should handle self-referencing entity type correctly")
		void shouldHandleSelfReferencingEntityTypeCorrectly() {
			// product references itself as group entity
			final EntitySchemaContract productSchema = buildEntitySchema(PRODUCT, builder ->
				builder.withReferenceTo(
					PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.withGroupType(PRODUCT)
						.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(
							"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
						))
				)
			);
			// category also references product as group
			final EntitySchemaContract categorySchema = buildEntitySchema(CATEGORY, builder ->
				builder.withReferenceTo(
					PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.withGroupType(PRODUCT)
						.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(
							"$reference.groupEntity?.attributes['priority'] > 0"
						))
				)
			);

			final CatalogExpressionTriggerRegistry original =
				buildRegistryFromSchemas(productSchema, categorySchema);

			// both triggers under "product" key (self-ref + category-ref)
			assertEquals(2, original.getTriggersFor(
				PRODUCT, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).size());

			// rebuild product — should remove product-owned trigger but keep category-owned
			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, List.of());

			final List<ExpressionIndexTrigger> remaining =
				rebuilt.getTriggersFor(PRODUCT, DependencyType.GROUP_ENTITY_ATTRIBUTE);
			assertEquals(1, remaining.size());
			assertEquals(CATEGORY, remaining.get(0).getOwnerEntityType());
		}

	}

	@Nested
	@DisplayName("buildFromSchemas — cold start full build")
	class BuildFromSchemasTest {

		@Test
		@DisplayName("Should produce empty registry from empty schema index")
		void shouldProduceEmptyRegistryFromEmptySchemaIndex() {
			final CatalogExpressionTriggerRegistry registry =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(Map.of());

			assertTrue(registry.getTriggersFor(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should return EMPTY singleton from buildFromSchemas with empty map")
		void shouldReturnEmptySingletonFromBuildFromSchemasWithEmptyMap() {
			final CatalogExpressionTriggerRegistry result =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(Map.of());

			assertSame(CatalogExpressionTriggerRegistry.EMPTY, result);
		}

		@Test
		@DisplayName("Should return EMPTY singleton when no schema has expressions")
		void shouldReturnEmptySingletonWhenNoSchemaHasExpressions() {
			// schemas with references but none use facetedPartially — no triggers produced
			final EntitySchemaContract schemaWithFaceted = buildEntitySchema(PRODUCT, builder ->
				builder.withReferenceTo(
					PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.withGroupType(PARAMETER_GROUP)
						.facetedInScope(Scope.LIVE)
				)
			);
			final EntitySchemaContract plainSchema = buildEntitySchema(CATEGORY, builder ->
				builder.withReferenceTo(BRAND_REF, BRAND, Cardinality.ZERO_OR_MORE)
			);

			final Map<String, EntitySchemaContract> schemas = new HashMap<>(4);
			schemas.put(PRODUCT, schemaWithFaceted);
			schemas.put(CATEGORY, plainSchema);

			final CatalogExpressionTriggerRegistry result =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(schemas);

			assertSame(CatalogExpressionTriggerRegistry.EMPTY, result);
		}

		@Test
		@DisplayName("Should produce no triggers for schema without facetedPartially")
		void shouldProduceNoTriggersForSchemaWithoutFacetedPartially() {
			final EntitySchemaContract schema = buildEntitySchema(PRODUCT, builder ->
				builder.withReferenceTo(
					PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.facetedInScope(Scope.LIVE)
				)
			);

			final CatalogExpressionTriggerRegistry registry =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(Map.of(PRODUCT, schema));

			assertTrue(registry.getTriggersFor(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should build trigger for single reference with expression")
		void shouldBuildTriggerForSingleReferenceWithExpression() {
			final EntitySchemaContract schema = buildEntitySchemaWithRefEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE,
				"$reference.referencedEntity.attributes['code'] == 'A'"
			);

			final CatalogExpressionTriggerRegistry registry =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(Map.of(PRODUCT, schema));

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE);
			assertEquals(1, result.size());
			assertEquals(PRODUCT, result.get(0).getOwnerEntityType());
			assertEquals(Scope.LIVE, result.get(0).getScope());
		}

		@Test
		@DisplayName("Should build two triggers for reference with both scopes")
		void shouldBuildTwoTriggersForReferenceWithBothScopes() {
			final Expression liveExpr =
				ExpressionFactory.parse("$reference.referencedEntity.attributes['code'] == 'A'");
			final Expression archivedExpr =
				ExpressionFactory.parse("$reference.referencedEntity.attributes['archived'] == true");

			final EntitySchemaContract schema = buildEntitySchema(PRODUCT, builder ->
				builder.withReferenceTo(
					PARAMETER_REF, PARAMETER_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.facetedPartiallyInScope(Scope.LIVE, liveExpr)
						.facetedPartiallyInScope(Scope.ARCHIVED, archivedExpr)
				)
			);

			final CatalogExpressionTriggerRegistry registry =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(Map.of(PRODUCT, schema));

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE);
			assertEquals(2, result.size());

			// both triggers returned by getTriggersForAttribute too
			assertEquals(1, registry.getTriggersForAttribute(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, "code"
			).size());
			assertEquals(1, registry.getTriggersForAttribute(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, "archived"
			).size());
		}

		@Test
		@DisplayName("Should build triggers from multiple schemas and references")
		void shouldBuildTriggersFromMultipleSchemasAndReferences() {
			final EntitySchemaContract productSchema = buildEntitySchemaWithRefEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE,
				"$reference.referencedEntity.attributes['code'] == 'A'"
			);
			final EntitySchemaContract categorySchema = buildEntitySchemaWithRefEntityExpression(
				CATEGORY, BRAND_REF, BRAND,
				"$reference.referencedEntity.attributes['active'] == true"
			);
			// a schema without conditional faceting — should be ignored
			final EntitySchemaContract plainSchema = buildEntitySchema("plain", builder ->
				builder.withReferenceTo("ref", "target", Cardinality.ZERO_OR_MORE)
			);

			final Map<String, EntitySchemaContract> schemas = new HashMap<>(4);
			schemas.put(PRODUCT, productSchema);
			schemas.put(CATEGORY, categorySchema);
			schemas.put("plain", plainSchema);

			final CatalogExpressionTriggerRegistry registry =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(schemas);

			assertEquals(1, registry.getTriggersFor(
				PARAMETER_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).size());
			assertEquals(1, registry.getTriggersFor(
				BRAND, DependencyType.REFERENCED_ENTITY_ATTRIBUTE
			).size());
		}

		@Test
		@DisplayName("Should skip reference without group type for group triggers")
		void shouldSkipReferenceWithoutGroupType() {
			// reference has no group type — only produces REFERENCED_ENTITY_ATTRIBUTE triggers
			final EntitySchemaContract schema = buildEntitySchemaWithRefEntityExpression(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE,
				"$reference.referencedEntity.attributes['code'] == 'A'"
			);

			final CatalogExpressionTriggerRegistry registry =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(Map.of(PRODUCT, schema));

			assertTrue(registry.getTriggersFor(
				PARAMETER_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE
			).isEmpty());
		}

		@Test
		@DisplayName("Should produce same result as incremental rebuilds")
		void shouldProduceSameResultAsIncrementalRebuilds() {
			final EntitySchemaContract productSchema = buildEntitySchemaWithGroupRef(
				PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
				"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);
			final EntitySchemaContract categorySchema = buildEntitySchemaWithRefEntityExpression(
				CATEGORY, BRAND_REF, BRAND,
				"$reference.referencedEntity.attributes['active'] == true"
			);

			final Map<String, EntitySchemaContract> schemas = new HashMap<>(4);
			schemas.put(PRODUCT, productSchema);
			schemas.put(CATEGORY, categorySchema);

			// full build
			final CatalogExpressionTriggerRegistry fullBuild =
				CatalogExpressionTriggerRegistryImpl.buildFromSchemas(schemas);

			// incremental build
			CatalogExpressionTriggerRegistry incremental = CatalogExpressionTriggerRegistry.EMPTY;
			for (final EntitySchemaContract schema : schemas.values()) {
				final List<ExpressionIndexTrigger> triggers =
					buildTriggersForSchema(schema);
				incremental = incremental.rebuildForEntityType(schema.getName(), triggers);
			}

			// verify both produce same results
			assertEquals(
				fullBuild.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE).size(),
				incremental.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE).size()
			);
			assertEquals(
				fullBuild.getTriggersFor(BRAND, DependencyType.REFERENCED_ENTITY_ATTRIBUTE).size(),
				incremental.getTriggersFor(BRAND, DependencyType.REFERENCED_ENTITY_ATTRIBUTE).size()
			);
		}

	}

	@Nested
	@DisplayName("Multiple references to same group entity")
	class MultipleReferencesToSameGroupTest {

		@Test
		@DisplayName("Should return triggers from both references to same group")
		void shouldReturnTriggersFromBothReferencesToSameGroup() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryFromSchemas(
				buildEntitySchemaWithTwoGroupRefs(
					PRODUCT, PARAMETER_REF, ALT_PARAMETER_REF,
					PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'",
					"$reference.groupEntity?.attributes['priority'] > 0"
				)
			);

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertEquals(2, result.size());
		}

		@Test
		@DisplayName("Should filter by attribute per trigger for same group")
		void shouldFilterByAttributePerTriggerForSameGroup() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryFromSchemas(
				buildEntitySchemaWithTwoGroupRefs(
					PRODUCT, PARAMETER_REF, ALT_PARAMETER_REF,
					PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'",
					"$reference.groupEntity?.attributes['priority'] > 0"
				)
			);

			final List<ExpressionIndexTrigger> statusTriggers = registry.getTriggersForAttribute(
				PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE, "status"
			);

			assertEquals(1, statusTriggers.size());
			assertEquals(PARAMETER_REF, statusTriggers.get(0).getReferenceName());
		}

	}

	@Nested
	@DisplayName("Cross-schema references with same target")
	class CrossSchemaReferencesTest {

		@Test
		@DisplayName("Should return triggers from different owners for same target")
		void shouldReturnTriggersFromDifferentOwnersForSameTarget() {
			final CatalogExpressionTriggerRegistry registry = buildRegistryFromSchemas(
				buildEntitySchemaWithGroupRef(
					PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
				),
				buildEntitySchemaWithGroupRef(
					CATEGORY, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['priority'] > 0"
				)
			);

			final List<ExpressionIndexTrigger> result =
				registry.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);

			assertEquals(2, result.size());
		}

		@Test
		@DisplayName("Should preserve other owner triggers on rebuild")
		void shouldPreserveOtherOwnerTriggersOnRebuild() {
			final CatalogExpressionTriggerRegistry original = buildRegistryFromSchemas(
				buildEntitySchemaWithGroupRef(
					PRODUCT, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
				),
				buildEntitySchemaWithGroupRef(
					CATEGORY, PARAMETER_REF, PARAMETER_TYPE, PARAMETER_GROUP,
					"$reference.groupEntity?.attributes['priority'] > 0"
				)
			);

			final CatalogExpressionTriggerRegistry rebuilt =
				original.rebuildForEntityType(PRODUCT, List.of());

			final List<ExpressionIndexTrigger> remaining =
				rebuilt.getTriggersFor(PARAMETER_GROUP, DependencyType.GROUP_ENTITY_ATTRIBUTE);
			assertEquals(1, remaining.size());
			assertEquals(CATEGORY, remaining.get(0).getOwnerEntityType());
		}

	}

	// --- Helper methods ---

	/**
	 * Builds a registry containing a single trigger for a group entity expression.
	 *
	 * @param ownerEntityType the owner entity type
	 * @param refName         the reference name
	 * @param refType         the referenced entity type
	 * @param groupType       the group entity type
	 * @param expressionStr   the expression string
	 * @return the populated registry
	 */
	@Nonnull
	private static CatalogExpressionTriggerRegistry buildRegistryWithGroupEntityExpression(
		@Nonnull String ownerEntityType,
		@Nonnull String refName,
		@Nonnull String refType,
		@Nonnull String groupType,
		@Nonnull String expressionStr
	) {
		return buildRegistryFromSchemas(
			buildEntitySchemaWithGroupRef(ownerEntityType, refName, refType, groupType, expressionStr)
		);
	}

	/**
	 * Builds a registry from one or more entity schemas using
	 * {@link CatalogExpressionTriggerRegistryImpl#buildFromSchemas}.
	 *
	 * @param schemas the entity schemas to build the registry from
	 * @return the populated registry
	 */
	@Nonnull
	private static CatalogExpressionTriggerRegistry buildRegistryFromSchemas(
		@Nonnull EntitySchemaContract... schemas
	) {
		final Map<String, EntitySchemaContract> schemaIndex = new HashMap<>(schemas.length);
		for (final EntitySchemaContract schema : schemas) {
			schemaIndex.put(schema.getName(), schema);
		}
		return CatalogExpressionTriggerRegistryImpl.buildFromSchemas(schemaIndex);
	}

	/**
	 * Builds triggers for all references in an entity schema (for incremental rebuild testing).
	 *
	 * @param schema the entity schema
	 * @return the list of all triggers from all references
	 */
	@Nonnull
	private static List<ExpressionIndexTrigger> buildTriggersForSchema(
		@Nonnull EntitySchemaContract schema
	) {
		final List<ExpressionIndexTrigger> allTriggers = new ArrayList<>();
		for (final ReferenceSchemaContract refSchema : schema.getReferences().values()) {
			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(schema.getName(), refSchema);
			allTriggers.addAll(triggers);
		}
		return allTriggers;
	}

	/**
	 * Builds triggers for a group entity reference (for rebuild testing).
	 *
	 * @param ownerEntityType the owner entity type
	 * @param refName         the reference name
	 * @param refType         the referenced entity type
	 * @param groupType       the group entity type
	 * @param expressionStr   the expression string
	 * @return the list of triggers
	 */
	@Nonnull
	private static List<ExpressionIndexTrigger> buildTriggersForGroupRef(
		@Nonnull String ownerEntityType,
		@Nonnull String refName,
		@Nonnull String refType,
		@Nonnull String groupType,
		@Nonnull String expressionStr
	) {
		final EntitySchemaContract schema =
			buildEntitySchemaWithGroupRef(ownerEntityType, refName, refType, groupType, expressionStr);
		return buildTriggersForSchema(schema);
	}

	/**
	 * Builds an entity schema with a reference that has a group type and a facetedPartially expression.
	 *
	 * @param entityType    the entity type name
	 * @param refName       the reference name
	 * @param refType       the referenced entity type
	 * @param groupType     the group entity type
	 * @param expressionStr the expression string
	 * @return the entity schema
	 */
	@Nonnull
	private static EntitySchemaContract buildEntitySchemaWithGroupRef(
		@Nonnull String entityType,
		@Nonnull String refName,
		@Nonnull String refType,
		@Nonnull String groupType,
		@Nonnull String expressionStr
	) {
		return buildEntitySchema(entityType, builder ->
			builder.withReferenceTo(refName, refType, Cardinality.ZERO_OR_MORE, whichIs -> whichIs
				.withGroupType(groupType)
				.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(expressionStr))
			)
		);
	}

	/**
	 * Builds an entity schema with a referenced entity expression (no group type).
	 *
	 * @param entityType    the entity type name
	 * @param refName       the reference name
	 * @param refType       the referenced entity type
	 * @param expressionStr the expression string
	 * @return the entity schema
	 */
	@Nonnull
	private static EntitySchemaContract buildEntitySchemaWithRefEntityExpression(
		@Nonnull String entityType,
		@Nonnull String refName,
		@Nonnull String refType,
		@Nonnull String expressionStr
	) {
		return buildEntitySchema(entityType, builder ->
			builder.withReferenceTo(refName, refType, Cardinality.ZERO_OR_MORE, whichIs -> whichIs
				.facetedPartiallyInScope(
					Scope.LIVE, ExpressionFactory.parse(expressionStr)
				)
			)
		);
	}

	/**
	 * Builds an entity schema with two references to the same group entity type, each with its own expression.
	 *
	 * @param entityType     the entity type name
	 * @param refName1       the first reference name
	 * @param refName2       the second reference name
	 * @param refType        the referenced entity type
	 * @param groupType      the group entity type
	 * @param expressionStr1 the first expression string
	 * @param expressionStr2 the second expression string
	 * @return the entity schema
	 */
	@Nonnull
	private static EntitySchemaContract buildEntitySchemaWithTwoGroupRefs(
		@Nonnull String entityType,
		@Nonnull String refName1,
		@Nonnull String refName2,
		@Nonnull String refType,
		@Nonnull String groupType,
		@Nonnull String expressionStr1,
		@Nonnull String expressionStr2
	) {
		return buildEntitySchema(entityType, builder ->
			builder
				.withReferenceTo(refName1, refType, Cardinality.ZERO_OR_MORE, whichIs -> whichIs
					.withGroupType(groupType)
					.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(expressionStr1))
				)
				.withReferenceTo(refName2, refType, Cardinality.ZERO_OR_MORE, whichIs -> whichIs
					.withGroupType(groupType)
					.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(expressionStr2))
				)
		);
	}

	/**
	 * Builds an entity schema by applying the given builder customizer.
	 *
	 * @param entityType      the entity type name
	 * @param schemaCustomizer the customizer that configures references/attributes
	 * @return the entity schema
	 */
	@Nonnull
	private static EntitySchemaContract buildEntitySchema(
		@Nonnull String entityType,
		@Nonnull Consumer<InternalEntitySchemaBuilder> schemaCustomizer
	) {
		final InternalEntitySchemaBuilder builder = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(entityType)
		);
		schemaCustomizer.accept(builder);
		return builder.toInstance();
	}

	/**
	 * Creates an {@link ExpressionIndexTrigger} with an inconsistent null state — exactly one of
	 * `mutatedEntityType` and `dependencyType` is null while the other is non-null. This should never
	 * happen in a correctly constructed trigger and is used to verify that `insertTrigger` detects
	 * the inconsistency.
	 *
	 * @param mutatedEntityType the mutated entity type (can be null for inconsistent state)
	 * @param dependencyType    the dependency type (can be null for inconsistent state)
	 * @return an inconsistent trigger stub
	 */
	@Nonnull
	private static ExpressionIndexTrigger createInconsistentTrigger(
		@Nullable String mutatedEntityType,
		@Nullable DependencyType dependencyType
	) {
		return new ExpressionIndexTrigger() {
			@Nonnull
			@Override
			public String getOwnerEntityType() {
				return PRODUCT;
			}

			@Nonnull
			@Override
			public String getReferenceName() {
				return PARAMETER_REF;
			}

			@Nonnull
			@Override
			public Scope getScope() {
				return Scope.LIVE;
			}

			@Nullable
			@Override
			public String getMutatedEntityType() {
				return mutatedEntityType;
			}

			@Nullable
			@Override
			public DependencyType getDependencyType() {
				return dependencyType;
			}

			@Nullable
			@Override
			public String getDependentReferenceName() {
				return null;
			}

			@Nonnull
			@Override
			public Set<String> getDependentAttributes() {
				return Set.of("status");
			}

			@Nonnull
			@Override
			public FilterBy getFilterByConstraint() {
				throw new UnsupportedOperationException("stub");
			}

			@Override
			public boolean evaluate(
				int ownerEntityPK,
				@Nonnull ReferenceKey referenceKey,
				@Nonnull WritableEntityStorageContainerAccessor storageAccessor,
				@Nonnull Function<String, EntitySchemaContract> schemaResolver
			) {
				throw new UnsupportedOperationException("stub");
			}
		};
	}

}
