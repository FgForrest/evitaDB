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

package io.evitadb.core.expression.trigger;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.expression.proxy.ExpressionProxyDescriptor;
import io.evitadb.core.expression.proxy.ExpressionProxyFactory;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.utils.NamingConvention;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPRESSION;
import static io.evitadb.test.TestTags.FACET;

/**
 * Tests for {@link DefaultFacetExpressionTrigger} — construction, getters, immutability, and evaluate() behavior.
 * Uses real schema objects and a simple test storage accessor instead of mocks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("DefaultFacetExpressionTrigger")
@Tag(ENGINE)
@Tag(EXPRESSION)
@Tag(FACET)
class DefaultFacetExpressionTriggerTest {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "parameter";
	private static final String REFERENCED_ENTITY_TYPE = "parameterType";
	private static final int ENTITY_PK = 1;
	private static final int REFERENCED_PK = 10;

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		"testCatalog",
		NamingConvention.generate("testCatalog"),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	@Nested
	@DisplayName("Construction and getter correctness")
	class ConstructionAndGetterTest {

		@Test
		@DisplayName("Should return owner entity type passed at construction")
		void shouldReturnOwnerEntityType() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger("true", Scope.LIVE);
			assertEquals(ENTITY_TYPE, trigger.getOwnerEntityType());
		}

		@Test
		@DisplayName("Should return reference name passed at construction")
		void shouldReturnReferenceName() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger("true", Scope.LIVE);
			assertEquals(REFERENCE_NAME, trigger.getReferenceName());
		}

		@Test
		@DisplayName("Should return LIVE scope passed at construction")
		void shouldReturnLiveScope() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger("true", Scope.LIVE);
			assertEquals(Scope.LIVE, trigger.getScope());
		}

		@Test
		@DisplayName("Should return ARCHIVED scope passed at construction")
		void shouldReturnArchivedScope() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger("true", Scope.ARCHIVED);
			assertEquals(Scope.ARCHIVED, trigger.getScope());
		}

		@Test
		@DisplayName("Should return REFERENCED_ENTITY_ATTRIBUTE dependency type")
		void shouldReturnReferencedEntityAttributeDependencyType() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Set.of("code")
			);
			assertEquals(DependencyType.REFERENCED_ENTITY_ATTRIBUTE, trigger.getDependencyType());
		}

		@Test
		@DisplayName("Should return GROUP_ENTITY_ATTRIBUTE dependency type")
		void shouldReturnGroupEntityAttributeDependencyType() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE, DependencyType.GROUP_ENTITY_ATTRIBUTE, Set.of("status")
			);
			assertEquals(DependencyType.GROUP_ENTITY_ATTRIBUTE, trigger.getDependencyType());
		}

		@Test
		@DisplayName("Should return dependent attributes passed at construction")
		void shouldReturnDependentAttributes() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Set.of("status", "code")
			);
			assertEquals(Set.of("status", "code"), trigger.getDependentAttributes());
		}

		@Test
		@DisplayName("Should return REFERENCED_ENTITY_REFERENCE_ATTRIBUTE dependency type")
		void shouldReturnReferencedEntityReferenceAttributeDependencyType() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE,
				DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, "tags",
				Set.of("visible")
			);
			assertEquals(
				DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, trigger.getDependencyType()
			);
		}

		@Test
		@DisplayName("Should return GROUP_ENTITY_REFERENCE_ATTRIBUTE dependency type")
		void shouldReturnGroupEntityReferenceAttributeDependencyType() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE,
				DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE, "categories",
				Set.of("weight")
			);
			assertEquals(
				DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE, trigger.getDependencyType()
			);
		}

		@Test
		@DisplayName("Should return mutated entity type passed at construction")
		void shouldReturnMutatedEntityTypePassedAtConstruction() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE,
				DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Set.of("code")
			);

			assertEquals(REFERENCED_ENTITY_TYPE, trigger.getMutatedEntityType());
		}

		@Test
		@DisplayName("Should return null mutated entity type for local-only trigger")
		void shouldReturnNullMutatedEntityTypeForLocalOnlyTrigger() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("true");

			assertNull(trigger.getMutatedEntityType());
		}

	}

	@Nested
	@DisplayName("FilterBy constraint handling")
	class FilterByConstraintTest {

		@Test
		@DisplayName("Should return same FilterBy instance on repeated calls")
		void shouldReturnSameFilterByInstanceOnRepeatedCalls() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger("true", Scope.LIVE);

			final FilterBy first = trigger.getFilterByConstraint();
			final FilterBy second = trigger.getFilterByConstraint();

			assertNotNull(first);
			assertSame(first, second);
		}

		@Test
		@DisplayName("Should return pre-translated FilterBy, not null")
		void shouldReturnNonNullFilterBy() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger("true", Scope.LIVE);
			assertNotNull(trigger.getFilterByConstraint());
		}

		@Test
		@DisplayName("Local-only trigger should throw UnsupportedOperationException on getFilterByConstraint()")
		void shouldThrowForLocalOnlyTriggerGetFilterBy() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("true");

			final UnsupportedOperationException exception = assertThrows(
				UnsupportedOperationException.class, trigger::getFilterByConstraint
			);
			assertTrue(
				exception.getMessage().contains(REFERENCE_NAME),
				"Exception message should contain reference name `" + REFERENCE_NAME + "`, " +
					"but was: " + exception.getMessage()
			);
		}

	}

	@Nested
	@DisplayName("Local-only trigger properties")
	class LocalOnlyTriggerTest {

		@Test
		@DisplayName("Should return null dependency type for local-only trigger")
		void shouldReturnNullDependencyTypeForLocalOnly() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("true");
			assertNull(trigger.getDependencyType());
		}

		@Test
		@DisplayName("Should return empty dependent attributes for local-only trigger")
		void shouldReturnEmptyDependentAttributesForLocalOnly() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("true");
			assertTrue(trigger.getDependentAttributes().isEmpty());
		}

	}

	@Nested
	@DisplayName("Dependent reference name")
	class DependentReferenceNameTest {

		@Test
		@DisplayName("Should return null dependent reference name for entity attribute dependency")
		void shouldReturnNullDependentReferenceNameForEntityAttributeDependency() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Set.of("code")
			);
			assertNull(trigger.getDependentReferenceName());
		}

		@Test
		@DisplayName("Should return null dependent reference name for local-only trigger")
		void shouldReturnNullDependentReferenceNameForLocalOnlyTrigger() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("true");
			assertNull(trigger.getDependentReferenceName());
		}

		@Test
		@DisplayName("Should return dependent reference name for REFERENCED_ENTITY_REFERENCE_ATTRIBUTE")
		void shouldReturnDependentReferenceNameForReferencedEntityReferenceAttribute() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE,
				DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, "tags",
				Set.of("visible")
			);
			assertEquals("tags", trigger.getDependentReferenceName());
		}

		@Test
		@DisplayName("Should return dependent reference name for GROUP_ENTITY_REFERENCE_ATTRIBUTE")
		void shouldReturnDependentReferenceNameForGroupEntityReferenceAttribute() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE,
				DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE, "categories",
				Set.of("weight")
			);
			assertEquals("categories", trigger.getDependentReferenceName());
		}

	}

	@Nested
	@DisplayName("Immutability")
	class ImmutabilityTest {

		@Test
		@DisplayName("Should return unmodifiable dependent attributes set")
		void shouldReturnUnmodifiableDependentAttributesSet() {
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Set.of("a")
			);
			assertThrows(UnsupportedOperationException.class, () -> trigger.getDependentAttributes().add("b"));
		}

		@Test
		@DisplayName("Should not be affected by external modification of input set")
		void shouldDefensivelyCopyInputSet() {
			final HashSet<String> input = new HashSet<>();
			input.add("original");
			final DefaultFacetExpressionTrigger trigger = createCrossEntityTrigger(
				"true", Scope.LIVE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE, input
			);
			input.add("injected");
			assertEquals(Set.of("original"), trigger.getDependentAttributes());
		}

	}

	@Nested
	@DisplayName("Constant expression evaluation")
	class ConstantExpressionEvaluationTest {

		@Test
		@DisplayName("Should return true for constant true expression")
		void shouldReturnTrueForConstantTrueExpression() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("true");
			assertTrue(evaluateWithMinimalSetup(trigger));
		}

		@Test
		@DisplayName("Should return false for constant false expression")
		void shouldReturnFalseForConstantFalseExpression() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("false");
			assertFalse(evaluateWithMinimalSetup(trigger));
		}

		@Test
		@DisplayName("Should return false when expression evaluates to null")
		void shouldReturnFalseWhenExpressionReturnsNull() {
			// The expression `null` parses to ConstantOperand(null) whose compute() returns null.
			// The convertResult(null) method should treat null as false.
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("null");
			assertFalse(evaluateWithMinimalSetup(trigger));
		}

	}

	@Nested
	@DisplayName("Entity attribute expression evaluation")
	class EntityAttributeEvaluationTest {

		@Test
		@DisplayName("Should evaluate entity attribute expression to true when attribute matches")
		void shouldEvaluateEntityAttributeExpressionToTrue() {
			final EntitySchemaContract schema = buildSchemaWithAttribute("status", String.class);
			final Expression expression = ExpressionFactory.parse("$entity.attributes['status'] == 'ACTIVE'");
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);
			final DefaultFacetExpressionTrigger trigger = new DefaultFacetExpressionTrigger(
				ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE, Set.of(), Set.of(), Set.of(), false, expression, descriptor
			);

			final TestStorageAccessor accessor = new TestStorageAccessor(ENTITY_PK);
			accessor.setGlobalAttributes(createAttributesPart(
				ENTITY_PK, new AttributeValue(new AttributeKey("status"), "ACTIVE")
			));

			final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_PK);
			final boolean result = trigger.evaluate(ENTITY_PK, refKey, accessor, name -> schema, Scope.LIVE);
			assertTrue(result);
		}

		@Test
		@DisplayName("Should evaluate entity attribute expression to false when attribute does not match")
		void shouldEvaluateEntityAttributeExpressionToFalse() {
			final EntitySchemaContract schema = buildSchemaWithAttribute("status", String.class);
			final Expression expression = ExpressionFactory.parse("$entity.attributes['status'] == 'ACTIVE'");
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);
			final DefaultFacetExpressionTrigger trigger = new DefaultFacetExpressionTrigger(
				ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE, Set.of(), Set.of(), Set.of(), false, expression, descriptor
			);

			final TestStorageAccessor accessor = new TestStorageAccessor(ENTITY_PK);
			accessor.setGlobalAttributes(createAttributesPart(
				ENTITY_PK, new AttributeValue(new AttributeKey("status"), "INACTIVE")
			));

			final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_PK);
			final boolean result = trigger.evaluate(ENTITY_PK, refKey, accessor, name -> schema, Scope.LIVE);
			assertFalse(result);
		}

		@Test
		@DisplayName("Should return false when attribute value is null (never set)")
		void shouldReturnFalseWhenAttributeValueIsNull() {
			final EntitySchemaContract schema = buildSchemaWithAttribute("status", String.class);
			final Expression expression = ExpressionFactory.parse(
				"$entity.attributes['status'] == 'ACTIVE'"
			);
			final ExpressionProxyDescriptor descriptor =
				ExpressionProxyFactory.buildDescriptor(expression);
			final DefaultFacetExpressionTrigger trigger = new DefaultFacetExpressionTrigger(
				ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
				Set.of(), Set.of(), Set.of(), false, expression, descriptor
			);

			// accessor returns empty AttributesStoragePart — 'status' exists in schema but has no value
			final TestStorageAccessor accessor = new TestStorageAccessor(ENTITY_PK);
			final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_PK);

			// null attribute value compared to 'ACTIVE' must not NPE — should evaluate to false
			final boolean result = trigger.evaluate(ENTITY_PK, refKey, accessor, name -> schema, Scope.LIVE);
			assertFalse(result);
		}

	}

	@Nested
	@DisplayName("Reference variable binding in evaluate()")
	class ReferenceVariableBindingTest {

		@Test
		@DisplayName("Should produce descriptor with needsReferences for $reference.attributes expression")
		void shouldProduceDescriptorWithNeedsReferencesForReferenceAttributeExpression() {
			final Expression expression =
				ExpressionFactory.parse("$reference.attributes['priority'] > 0");
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

			// verify descriptor has reference partials (needed to instantiate reference proxy)
			assertNotNull(
				descriptor.referencePartials(),
				"Proxy descriptor must have non-null referencePartials for $reference.attributes expression"
			);
			// verify entity recipe loads references so that the reference can be found during
			// proxy instantiation — without this, the reference proxy will be null and $reference
			// variable will not be bound, causing VariableNotDefinedException
			assertTrue(
				descriptor.entityRecipe().needsReferences(),
				"Entity recipe must need references when expression uses $reference variable"
			);
		}

		@Test
		@DisplayName("Should produce descriptor with needsReferences for $reference.referencedEntity expression")
		void shouldProduceDescriptorWithNeedsReferencesForReferencedEntityExpression() {
			final Expression expression =
				ExpressionFactory.parse("$reference.referencedEntity.attributes['code'] == 'A'");
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

			assertNotNull(
				descriptor.referencePartials(),
				"Proxy descriptor must have non-null referencePartials for $reference.referencedEntity expression"
			);
			assertTrue(
				descriptor.entityRecipe().needsReferences(),
				"Entity recipe must need references when expression uses $reference variable"
			);
		}

		@Test
		@DisplayName("Should produce descriptor with needsReferences for $reference.groupEntity expression")
		void shouldProduceDescriptorWithNeedsReferencesForGroupEntityExpression() {
			final Expression expression =
				ExpressionFactory.parse("$reference.groupEntity?.attributes['widgetType'] == 'CHECKBOX'");
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

			assertNotNull(
				descriptor.referencePartials(),
				"Proxy descriptor must have non-null referencePartials for $reference.groupEntity expression"
			);
			assertTrue(
				descriptor.entityRecipe().needsReferences(),
				"Entity recipe must need references when expression uses $reference variable"
			);
		}

		@Test
		@DisplayName("Should return false when reference not yet in storage (insert-time evaluation)")
		void shouldReturnFalseWhenReferenceNotYetInStorage() {
			final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
			)
				.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.withAttribute("priority", Long.class)
				)
				.toInstance();

			final Expression expression = ExpressionFactory.parse("$reference.attributes['priority'] > 0");
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

			final DefaultFacetExpressionTrigger trigger = new DefaultFacetExpressionTrigger(
				ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
				Set.of(), Set.of("priority"), Set.of(), false,
				expression, descriptor
			);

			// the storage accessor returns an EMPTY references storage part — simulating
			// the condition during InsertReferenceMutation processing where the reference
			// has not yet been written to storage
			final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_PK);
			final TestStorageAccessor accessor = new TestStorageAccessor(ENTITY_PK);

			// evaluate must return false (not throw VariableNotDefinedException) because
			// the reference data is not available yet — re-evaluation will happen later
			// when the reference attribute mutation is processed
			final boolean result = trigger.evaluate(ENTITY_PK, refKey, accessor, name -> schema, Scope.LIVE);
			assertFalse(
				result,
				"Expression using $reference should return false when reference not yet in storage"
			);
		}

	}

	@Nested
	@DisplayName("Cross-entity expression evaluation")
	class CrossEntityExpressionEvaluationTest {

		private static final String GROUP_ENTITY_TYPE = "parameterGroup";
		private static final int GROUP_PK = 50;

		@Test
		@DisplayName("Should evaluate referencedEntity attribute expression to true with correct schema resolver")
		void shouldEvaluateReferencedEntityAttributeExpressionToTrue() {
			// owner entity schema (product) — does NOT have 'status' attribute
			final EntitySchemaContract ownerSchema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
			)
				.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE)
				.toInstance();

			// referenced entity schema (parameterType) — HAS 'status' attribute
			final EntitySchemaContract referencedSchema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, EntitySchema._internalBuild(REFERENCED_ENTITY_TYPE)
			)
				.withAttribute("status", String.class)
				.toInstance();

			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.attributes['status'] == 'ACTIVE'"
			);
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

			final DefaultFacetExpressionTrigger trigger = new DefaultFacetExpressionTrigger(
				ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
				Set.of(), Set.of(), Set.of(), false,
				expression, descriptor
			);

			// set up storage with a reference and the referenced entity's attribute
			final CrossEntityTestStorageAccessor accessor = new CrossEntityTestStorageAccessor();
			final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_PK);
			final ReferenceSchemaContract refSchemaContract =
				ownerSchema.getReferenceOrThrowException(REFERENCE_NAME);
			final Reference ref = new Reference(ownerSchema, refSchemaContract, refKey, null);
			accessor.setReferencesStoragePart(
				ENTITY_TYPE, ENTITY_PK,
				new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1)
			);

			// set the referenced entity's attributes — 'status' = 'ACTIVE'
			accessor.setGlobalAttributes(
				REFERENCED_ENTITY_TYPE, REFERENCED_PK,
				createAttributesPart(REFERENCED_PK, new AttributeValue(new AttributeKey("status"), "ACTIVE"))
			);

			// schema resolver that correctly maps entity types to their schemas
			final boolean result = trigger.evaluate(ENTITY_PK, refKey, accessor, name -> {
				if (ENTITY_TYPE.equals(name)) {
					return ownerSchema;
				} else if (REFERENCED_ENTITY_TYPE.equals(name)) {
					return referencedSchema;
				}
				throw new IllegalStateException("Unexpected entity type: " + name);
			}, Scope.LIVE);

			assertTrue(
				result,
				"Expression '$reference.referencedEntity.attributes[\"status\"] == \"ACTIVE\"' " +
					"should evaluate to true when the referenced entity has status=ACTIVE and the " +
					"schema resolver provides the correct (referenced entity's) schema"
			);
		}

		@Test
		@DisplayName("Should fail when schema resolver returns wrong schema for referenced entity type")
		void shouldFailWhenSchemaResolverReturnsWrongSchemaForReferencedEntityType() {
			// owner entity schema (product) — does NOT have 'status' attribute
			final EntitySchemaContract ownerSchema = new InternalEntitySchemaBuilder(
				CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
			)
				.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE)
				.toInstance();

			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.attributes['status'] == 'ACTIVE'"
			);
			final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);

			final DefaultFacetExpressionTrigger trigger = new DefaultFacetExpressionTrigger(
				ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
				Set.of(), Set.of(), Set.of(), false,
				expression, descriptor
			);

			// set up storage with a reference
			final CrossEntityTestStorageAccessor accessor = new CrossEntityTestStorageAccessor();
			final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_PK);
			final ReferenceSchemaContract refSchemaContract =
				ownerSchema.getReferenceOrThrowException(REFERENCE_NAME);
			final Reference ref = new Reference(ownerSchema, refSchemaContract, refKey, null);
			accessor.setReferencesStoragePart(
				ENTITY_TYPE, ENTITY_PK,
				new ReferencesStoragePart(ENTITY_PK, 1, new Reference[]{ref}, -1)
			);

			// BUGGY resolver — always returns the owner (product) schema, even when asked for
			// the referenced entity type. The product schema does NOT have 'status' attribute,
			// so getAttributeSchema("status") will return Optional.empty(), causing the error
			// "Attribute schema for element `status` not found."
			assertThrows(
				ExpressionEvaluationException.class,
				() -> trigger.evaluate(ENTITY_PK, refKey, accessor, name -> ownerSchema, Scope.LIVE),
				"Should throw ExpressionEvaluationException when schema resolver provides the " +
					"wrong schema for the referenced entity type"
			);
		}

	}

	@Nested
	@DisplayName("Non-boolean expression result errors")
	class NonBooleanResultErrorTest {

		@Test
		@DisplayName("Should throw ExpressionEvaluationException for string expression result")
		void shouldThrowForStringExpressionResult() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("'hello'");

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class, () -> evaluateWithMinimalSetup(trigger)
			);
			assertTrue(
				exception.getPublicMessage().contains("String"),
				"Exception message should mention 'String' type, but was: " + exception.getPublicMessage()
			);
		}

		@Test
		@DisplayName("Should throw ExpressionEvaluationException for integer expression result")
		void shouldThrowForIntegerExpressionResult() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("42");

			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class, () -> evaluateWithMinimalSetup(trigger)
			);
			// expression engine may return Long or BigDecimal — just verify the message mentions the type
			assertTrue(
				exception.getPublicMessage().contains("instead of Boolean"),
				"Exception message should mention 'instead of Boolean', " +
					"but was: " + exception.getPublicMessage()
			);
		}

	}

	@Nested
	@DisplayName("Type marker conformance")
	class TypeMarkerTest {

		@Test
		@DisplayName("Implementation should be instance of FacetExpressionTrigger")
		void shouldBeInstanceOfFacetExpressionTrigger() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("true");
			assertInstanceOf(FacetExpressionTrigger.class, trigger);
		}

		@Test
		@DisplayName("Implementation should be instance of ExpressionIndexTrigger")
		void shouldBeInstanceOfExpressionIndexTrigger() {
			final DefaultFacetExpressionTrigger trigger = createLocalOnlyTrigger("true");
			assertInstanceOf(ExpressionIndexTrigger.class, trigger);
		}

	}

	// --- Helper methods ---

	/**
	 * Builds a real {@link EntitySchema} with a single attribute and a reference for testing.
	 *
	 * @param attributeName the attribute name
	 * @param attributeType the attribute type
	 * @return the constructed entity schema
	 */
	@Nonnull
	private static EntitySchemaContract buildSchemaWithAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> attributeType
	) {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA,
			EntitySchema._internalBuild(ENTITY_TYPE)
		)
			.withAttribute(attributeName, attributeType)
			.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE)
			.toInstance();
	}

	/**
	 * Creates an {@link AttributesStoragePart} with the given attribute values using real
	 * {@link AttributeSchema} objects.
	 *
	 * @param entityPK the entity primary key
	 * @param values   the attribute values to store
	 * @return the populated attributes storage part
	 */
	@Nonnull
	private static AttributesStoragePart createAttributesPart(
		int entityPK,
		@Nonnull AttributeValue... values
	) {
		final AttributesStoragePart part = new AttributesStoragePart(entityPK);
		for (final AttributeValue value : values) {
			final Class<? extends Serializable> type = value.value().getClass();
			final AttributeSchema attrSchema = AttributeSchema._internalBuild(
				value.key().attributeName(), type, false,
				ConflictResolutionOverride.INHERITED
			);
			part.upsertAttribute(value.key(), attrSchema, existing -> value);
		}
		return part;
	}

	/**
	 * Creates a cross-entity trigger with default dependency type and attributes.
	 *
	 * @param expressionStr the expression string
	 * @param scope         the scope
	 * @return the constructed trigger
	 */
	@Nonnull
	private static DefaultFacetExpressionTrigger createCrossEntityTrigger(
		@Nonnull String expressionStr,
		@Nonnull Scope scope
	) {
		return createCrossEntityTrigger(
			expressionStr, scope,
			DependencyType.REFERENCED_ENTITY_ATTRIBUTE, Set.of("code")
		);
	}

	/**
	 * Creates a cross-entity trigger with specified dependency type and attributes.
	 *
	 * @param expressionStr       the expression string
	 * @param scope               the scope
	 * @param dependencyType      the dependency type
	 * @param dependentAttributes the dependent attribute names
	 * @return the constructed trigger
	 */
	@Nonnull
	private static DefaultFacetExpressionTrigger createCrossEntityTrigger(
		@Nonnull String expressionStr,
		@Nonnull Scope scope,
		@Nonnull DependencyType dependencyType,
		@Nonnull Set<String> dependentAttributes
	) {
		return createCrossEntityTrigger(expressionStr, scope, dependencyType, null, dependentAttributes);
	}

	/**
	 * Creates a cross-entity trigger with specified dependency type, reference name, and attributes.
	 *
	 * @param expressionStr          the expression string
	 * @param scope                  the scope
	 * @param dependencyType         the dependency type
	 * @param dependentReferenceName the dependent reference name on target entity, or `null`
	 * @param dependentAttributes    the dependent attribute names
	 * @return the constructed trigger
	 */
	@Nonnull
	private static DefaultFacetExpressionTrigger createCrossEntityTrigger(
		@Nonnull String expressionStr,
		@Nonnull Scope scope,
		@Nonnull DependencyType dependencyType,
		@Nullable String dependentReferenceName,
		@Nonnull Set<String> dependentAttributes
	) {
		final Expression expression = ExpressionFactory.parse(expressionStr);
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);
		final FilterBy filterBy = filterBy(attributeEquals("code", "test"));
		return new DefaultFacetExpressionTrigger(
			ENTITY_TYPE, REFERENCE_NAME, scope,
			REFERENCED_ENTITY_TYPE, dependencyType, dependentReferenceName, dependentAttributes,
			Set.of(), Set.of(), Set.of(), false,
			expression, descriptor, filterBy
		);
	}

	/**
	 * Creates a local-only trigger (no dependency type, no FilterBy).
	 *
	 * @param expressionStr the expression string
	 * @return the constructed trigger
	 */
	@Nonnull
	private static DefaultFacetExpressionTrigger createLocalOnlyTrigger(@Nonnull String expressionStr) {
		final Expression expression = ExpressionFactory.parse(expressionStr);
		final ExpressionProxyDescriptor descriptor = ExpressionProxyFactory.buildDescriptor(expression);
		return new DefaultFacetExpressionTrigger(
			ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE, Set.of(), Set.of(), Set.of(), false, expression, descriptor
		);
	}

	/**
	 * Evaluates a trigger with a minimal real entity schema (no attributes) and empty storage.
	 *
	 * @param trigger the trigger to evaluate
	 * @return the evaluation result
	 */
	private static boolean evaluateWithMinimalSetup(@Nonnull DefaultFacetExpressionTrigger trigger) {
		final EntitySchemaContract schema = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
		)
			.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE)
			.toInstance();

		final TestStorageAccessor accessor = new TestStorageAccessor(ENTITY_PK);
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_PK);
		return trigger.evaluate(ENTITY_PK, refKey, accessor, name -> schema, Scope.LIVE);
	}

	/**
	 * Simple test implementation of {@link WritableEntityStorageContainerAccessor} backed by in-memory storage parts.
	 * Returns empty parts by default, with setters for customization. Entity-type-unaware — suitable for
	 * single-entity tests.
	 */
	private static final class TestStorageAccessor implements WritableEntityStorageContainerAccessor {

		private final int entityPK;
		@Nullable private AttributesStoragePart globalAttributes;

		/**
		 * Creates a new test accessor for the given entity PK.
		 *
		 * @param entityPK the entity primary key
		 */
		TestStorageAccessor(int entityPK) {
			this.entityPK = entityPK;
		}

		/**
		 * Sets the global attributes part to return from {@link #getAttributeStoragePart(String, int)}.
		 *
		 * @param globalAttributes the attributes part
		 */
		void setGlobalAttributes(@Nonnull AttributesStoragePart globalAttributes) {
			this.globalAttributes = globalAttributes;
		}

		@Override
		public boolean isEntityRemovedEntirely() {
			return false;
		}

		@Override
		public void registerAssignedPriceId(
			int entityPrimaryKey, @Nonnull PriceKey priceKey, int internalPriceId
		) {
			// no-op for tests
		}

		@Nonnull
		@Override
		public OptionalInt findExistingInternalId(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey
		) {
			return OptionalInt.empty();
		}

		@Nonnull
		@Override
		public LocaleWithScope[] getAddedLocales() {
			return new LocaleWithScope[0];
		}

		@Nonnull
		@Override
		public LocaleWithScope[] getRemovedLocales() {
			return new LocaleWithScope[0];
		}

		@Override
		public int getLocalesIdentityHash() {
			return 0;
		}

		@Nonnull
		@Override
		public EntityBodyStoragePart getEntityStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects
		) {
			return new EntityBodyStoragePart(this.entityPK);
		}

		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(
			@Nonnull String entityType, int entityPrimaryKey
		) {
			return this.globalAttributes != null
				? this.globalAttributes
				: new AttributesStoragePart(this.entityPK);
		}

		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nullable Locale locale
		) {
			return new AttributesStoragePart(this.entityPK, locale);
		}

		@Nonnull
		@Override
		public AssociatedDataStoragePart getAssociatedDataStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key
		) {
			return new AssociatedDataStoragePart(this.entityPK, key);
		}

		@Nonnull
		@Override
		public ReferencesStoragePart getReferencesStoragePart(
			@Nonnull String entityType, int entityPrimaryKey
		) {
			return new ReferencesStoragePart(this.entityPK);
		}

		@Nonnull
		@Override
		public PricesStoragePart getPriceStoragePart(
			@Nonnull String entityType, int entityPrimaryKey
		) {
			return new PricesStoragePart(this.entityPK);
		}
	}

	/**
	 * Entity-type-aware test implementation of {@link WritableEntityStorageContainerAccessor}. Supports
	 * cross-entity storage access by routing storage part lookups based on `(entityType, entityPK)` tuples.
	 * Used for testing cross-entity expressions like `$reference.referencedEntity.attributes['status']`.
	 */
	private static final class CrossEntityTestStorageAccessor implements WritableEntityStorageContainerAccessor {

		private final Map<String, AttributesStoragePart> globalAttributesMap = new HashMap<>();
		private final Map<String, ReferencesStoragePart> referencesMap = new HashMap<>();

		/**
		 * Generates a composite key for the `(entityType, entityPK)` pair.
		 *
		 * @param entityType the entity type name
		 * @param entityPK   the entity primary key
		 * @return the composite key
		 */
		@Nonnull
		private static String key(@Nonnull String entityType, int entityPK) {
			return entityType + "#" + entityPK;
		}

		/**
		 * Registers global attributes for a specific entity.
		 *
		 * @param entityType the entity type name
		 * @param entityPK   the entity primary key
		 * @param part       the attributes storage part
		 */
		void setGlobalAttributes(
			@Nonnull String entityType, int entityPK, @Nonnull AttributesStoragePart part
		) {
			this.globalAttributesMap.put(key(entityType, entityPK), part);
		}

		/**
		 * Registers a references storage part for a specific entity.
		 *
		 * @param entityType the entity type name
		 * @param entityPK   the entity primary key
		 * @param part       the references storage part
		 */
		void setReferencesStoragePart(
			@Nonnull String entityType, int entityPK, @Nonnull ReferencesStoragePart part
		) {
			this.referencesMap.put(key(entityType, entityPK), part);
		}

		@Override
		public boolean isEntityRemovedEntirely() {
			return false;
		}

		@Override
		public void registerAssignedPriceId(
			int entityPrimaryKey, @Nonnull PriceKey priceKey, int internalPriceId
		) {
			// no-op
		}

		@Nonnull
		@Override
		public OptionalInt findExistingInternalId(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey
		) {
			return OptionalInt.empty();
		}

		@Nonnull
		@Override
		public LocaleWithScope[] getAddedLocales() {
			return new LocaleWithScope[0];
		}

		@Nonnull
		@Override
		public LocaleWithScope[] getRemovedLocales() {
			return new LocaleWithScope[0];
		}

		@Override
		public int getLocalesIdentityHash() {
			return 0;
		}

		@Nonnull
		@Override
		public EntityBodyStoragePart getEntityStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects
		) {
			return new EntityBodyStoragePart(entityPrimaryKey);
		}

		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(
			@Nonnull String entityType, int entityPrimaryKey
		) {
			final AttributesStoragePart part = this.globalAttributesMap.get(key(entityType, entityPrimaryKey));
			return part != null ? part : new AttributesStoragePart(entityPrimaryKey);
		}

		@Nonnull
		@Override
		public AttributesStoragePart getAttributeStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nullable Locale locale
		) {
			return new AttributesStoragePart(entityPrimaryKey, locale);
		}

		@Nonnull
		@Override
		public AssociatedDataStoragePart getAssociatedDataStoragePart(
			@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key
		) {
			return new AssociatedDataStoragePart(entityPrimaryKey, key);
		}

		@Nonnull
		@Override
		public ReferencesStoragePart getReferencesStoragePart(
			@Nonnull String entityType, int entityPrimaryKey
		) {
			final ReferencesStoragePart part = this.referencesMap.get(key(entityType, entityPrimaryKey));
			return part != null ? part : new ReferencesStoragePart(entityPrimaryKey);
		}

		@Nonnull
		@Override
		public PricesStoragePart getPriceStoragePart(
			@Nonnull String entityType, int entityPrimaryKey
		) {
			return new PricesStoragePart(entityPrimaryKey);
		}
	}

}
