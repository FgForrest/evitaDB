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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.expression.query.NonTranslatableExpressionException;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.index.mutation.DependencyType;
import io.evitadb.index.mutation.FacetExpressionTrigger;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FacetExpressionTriggerFactory} — trigger construction from reference schemas.
 * Uses real schema objects built via {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetExpressionTriggerFactory")
class FacetExpressionTriggerFactoryTest {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "parameter";
	private static final String REFERENCED_ENTITY_TYPE = "parameterType";
	private static final String GROUP_ENTITY_TYPE = "parameterGroup";

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		"testCatalog",
		NamingConvention.generate("testCatalog"),
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	@Nested
	@DisplayName("References without facetedPartially expressions")
	class NoExpressionTest {

		@Test
		@DisplayName("Should return empty list when reference has no facetedPartially expressions")
		void shouldReturnEmptyListWhenNoFacetedPartially() {
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE
				)
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertTrue(triggers.isEmpty());
		}

		@Test
		@DisplayName("Should return empty list when reference is faceted but has no partial expression")
		void shouldReturnEmptyListWhenFacetedWithoutPartial() {
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.facetedInScope(Scope.LIVE)
				)
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertTrue(triggers.isEmpty());
		}

	}

	@Nested
	@DisplayName("Local-only expression triggers")
	class LocalOnlyExpressionTest {

		@Test
		@DisplayName("Should build local-only trigger for entity attribute expression")
		void shouldBuildLocalOnlyTriggerForEntityAttribute() {
			final Expression expression =
				ExpressionFactory.parse("$entity.attributes['status'] == 'ACTIVE'");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			final FacetExpressionTrigger trigger = triggers.get(0);
			assertEquals(ENTITY_TYPE, trigger.getOwnerEntityType());
			assertEquals(REFERENCE_NAME, trigger.getReferenceName());
			assertEquals(Scope.LIVE, trigger.getScope());
			assertNull(trigger.getDependencyType());
			assertNull(trigger.getMutatedEntityType());
			assertTrue(trigger.getDependentAttributes().isEmpty());
		}

		@Test
		@DisplayName("Local-only trigger should throw on getFilterByConstraint()")
		void shouldThrowOnFilterByForLocalOnlyTrigger() {
			final Expression expression =
				ExpressionFactory.parse("$entity.attributes['status'] == 'ACTIVE'");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			assertThrows(
				UnsupportedOperationException.class,
				() -> triggers.get(0).getFilterByConstraint()
			);
		}

		@Test
		@DisplayName("Should build local-only trigger for reference attribute expression")
		void shouldBuildLocalOnlyTriggerForReferenceAttribute() {
			final Expression expression =
				ExpressionFactory.parse("$reference.attributes['order'] > 0");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			assertNull(triggers.get(0).getDependencyType());
		}

		@Test
		@DisplayName("Should build local-only trigger for constant boolean expression")
		void shouldBuildLocalOnlyTriggerForConstantExpression() {
			final Expression expression = ExpressionFactory.parse("true");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			assertNull(triggers.get(0).getDependencyType());
		}

	}

	@Nested
	@DisplayName("Cross-entity referenced entity triggers")
	class ReferencedEntityTriggerTest {

		@Test
		@DisplayName("Should build REFERENCED_ENTITY_ATTRIBUTE trigger for referencedEntity path")
		void shouldBuildReferencedEntityAttributeTrigger() {
			final Expression expression =
				ExpressionFactory.parse("$reference.referencedEntity.attributes['code'] == 'A'");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			final FacetExpressionTrigger trigger = triggers.get(0);
			assertEquals(DependencyType.REFERENCED_ENTITY_ATTRIBUTE, trigger.getDependencyType());
			assertEquals(REFERENCED_ENTITY_TYPE, trigger.getMutatedEntityType());
			assertEquals(Set.of("code"), trigger.getDependentAttributes());
			assertNotNull(trigger.getFilterByConstraint());
		}

		@Test
		@DisplayName("Should collect multiple dependent attributes from same dependency type")
		void shouldCollectMultipleDependentAttributes() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.attributes['code'] == 'A' " +
					"&& $reference.referencedEntity.attributes['status'] == 'ACTIVE'"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			assertEquals(Set.of("code", "status"), triggers.get(0).getDependentAttributes());
		}

		@Test
		@DisplayName("Should build trigger for localized attribute on referenced entity")
		void shouldBuildReferencedEntityAttributeTriggerForLocalizedAttribute() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.localizedAttributes['name'] == 'x'"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			final FacetExpressionTrigger trigger = triggers.get(0);
			assertEquals(DependencyType.REFERENCED_ENTITY_ATTRIBUTE, trigger.getDependencyType());
			assertEquals(Set.of("name"), trigger.getDependentAttributes());
			assertNotNull(trigger.getFilterByConstraint());
		}

	}

	@Nested
	@DisplayName("Cross-entity group entity triggers")
	class GroupEntityTriggerTest {

		@Test
		@DisplayName("Should build GROUP_ENTITY_ATTRIBUTE trigger for groupEntity path")
		void shouldBuildGroupEntityAttributeTrigger() {
			final Expression expression =
				ExpressionFactory.parse(
					"$reference.groupEntity?.attributes['status'] == 'VISIBLE'"
				);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroupAndExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			final FacetExpressionTrigger trigger = triggers.get(0);
			assertEquals(DependencyType.GROUP_ENTITY_ATTRIBUTE, trigger.getDependencyType());
			assertEquals(GROUP_ENTITY_TYPE, trigger.getMutatedEntityType());
			assertEquals(Set.of("status"), trigger.getDependentAttributes());
			assertNotNull(trigger.getFilterByConstraint());
		}

	}

	@Nested
	@DisplayName("Cross-entity referenced entity reference attribute triggers")
	class ReferencedEntityReferenceAttributeTest {

		@Test
		@DisplayName("Should build REFERENCED_ENTITY_REFERENCE_ATTRIBUTE trigger for reference attribute path")
		void shouldBuildReferencedEntityReferenceAttributeTrigger() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.references['tags'].attributes['visible'] == true"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			final FacetExpressionTrigger trigger = triggers.get(0);
			assertEquals(
				DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE, trigger.getDependencyType()
			);
			assertEquals("tags", trigger.getDependentReferenceName());
			assertEquals(Set.of("visible"), trigger.getDependentAttributes());
			assertNotNull(trigger.getFilterByConstraint());
		}

		@Test
		@DisplayName("Should collect multiple attributes from same reference on referenced entity")
		void shouldCollectMultipleAttributesFromSameReferenceOnReferencedEntity() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.references['tags'].attributes['visible'] == true " +
					"&& $reference.referencedEntity.references['tags'].attributes['priority'] > 0"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			assertEquals(
				Set.of("visible", "priority"), triggers.get(0).getDependentAttributes()
			);
		}

		@Test
		@DisplayName("Should build separate triggers for different reference names on referenced entity")
		void shouldBuildSeparateTriggersForDifferentReferenceNamesOnReferencedEntity() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.references['tags'].attributes['visible'] == true " +
					"&& $reference.referencedEntity.references['links'].attributes['weight'] > 0"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(2, triggers.size());

			final FacetExpressionTrigger tagsTrigger = triggers.stream()
				.filter(t -> "tags".equals(t.getDependentReferenceName()))
				.findFirst().orElseThrow();
			final FacetExpressionTrigger linksTrigger = triggers.stream()
				.filter(t -> "links".equals(t.getDependentReferenceName()))
				.findFirst().orElseThrow();

			assertEquals(Set.of("visible"), tagsTrigger.getDependentAttributes());
			assertEquals(Set.of("weight"), linksTrigger.getDependentAttributes());
		}

	}

	@Nested
	@DisplayName("Cross-entity group entity reference attribute triggers")
	class GroupEntityReferenceAttributeTest {

		@Test
		@DisplayName("Should build GROUP_ENTITY_REFERENCE_ATTRIBUTE trigger for group entity reference path")
		void shouldBuildGroupEntityReferenceAttributeTrigger() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.groupEntity?.references['links'].attributes['weight'] > 0"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroupAndExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			final FacetExpressionTrigger trigger = triggers.get(0);
			assertEquals(
				DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE, trigger.getDependencyType()
			);
			assertEquals("links", trigger.getDependentReferenceName());
			assertEquals(Set.of("weight"), trigger.getDependentAttributes());
		}

		@Test
		@DisplayName("Should collect multiple attributes from same reference on group entity")
		void shouldCollectMultipleAttributesFromSameReferenceOnGroupEntity() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.groupEntity?.references['links'].attributes['weight'] > 0 " +
					"&& $reference.groupEntity?.references['links'].attributes['score'] > 5"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroupAndExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(1, triggers.size());
			final FacetExpressionTrigger trigger = triggers.get(0);
			assertEquals(
				DependencyType.GROUP_ENTITY_REFERENCE_ATTRIBUTE, trigger.getDependencyType()
			);
			assertEquals("links", trigger.getDependentReferenceName());
			assertEquals(Set.of("weight", "score"), trigger.getDependentAttributes());
		}

		@Test
		@DisplayName("Should build separate triggers for different reference names on group entity")
		void shouldBuildSeparateTriggersForDifferentReferenceNamesOnGroupEntity() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.groupEntity?.references['links'].attributes['weight'] > 0 " +
					"&& $reference.groupEntity?.references['metrics'].attributes['score'] > 5"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroupAndExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(2, triggers.size());

			final FacetExpressionTrigger linksTrigger = triggers.stream()
				.filter(t -> "links".equals(t.getDependentReferenceName()))
				.findFirst().orElseThrow();
			final FacetExpressionTrigger metricsTrigger = triggers.stream()
				.filter(t -> "metrics".equals(t.getDependentReferenceName()))
				.findFirst().orElseThrow();

			assertEquals(Set.of("weight"), linksTrigger.getDependentAttributes());
			assertEquals(Set.of("score"), metricsTrigger.getDependentAttributes());
		}

	}

	@Nested
	@DisplayName("Dual and mixed dependency triggers")
	class DualAndMixedDependencyTest {

		@Test
		@DisplayName("Should build two triggers for expression with both referencedEntity and groupEntity paths")
		void shouldBuildTwoTriggersForDualDependency() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.attributes['code'] == 'A' " +
					"&& $reference.groupEntity?.attributes['status'] == 'VISIBLE'"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithGroupAndExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(2, triggers.size());

			// find each trigger by dependency type
			final FacetExpressionTrigger refTrigger = triggers.stream()
				.filter(t -> t.getDependencyType() == DependencyType.REFERENCED_ENTITY_ATTRIBUTE)
				.findFirst().orElseThrow();
			final FacetExpressionTrigger groupTrigger = triggers.stream()
				.filter(t -> t.getDependencyType() == DependencyType.GROUP_ENTITY_ATTRIBUTE)
				.findFirst().orElseThrow();

			assertEquals(Set.of("code"), refTrigger.getDependentAttributes());
			assertEquals(Set.of("status"), groupTrigger.getDependentAttributes());
			// both share the same FilterBy (same expression translated once)
			assertNotNull(refTrigger.getFilterByConstraint());
			assertNotNull(groupTrigger.getFilterByConstraint());
		}

		@Test
		@DisplayName("Should build triggers for mixed entity attribute and reference attribute paths")
		void shouldBuildTriggersForMixedEntityAndReferenceAttributePaths() {
			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.attributes['code'] == 'A' " +
					"&& $reference.referencedEntity.references['tags'].attributes['visible'] == true"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(2, triggers.size());

			final FacetExpressionTrigger entityAttrTrigger = triggers.stream()
				.filter(t -> t.getDependencyType() == DependencyType.REFERENCED_ENTITY_ATTRIBUTE)
				.findFirst().orElseThrow();
			final FacetExpressionTrigger refAttrTrigger = triggers.stream()
				.filter(
					t -> t.getDependencyType() == DependencyType.REFERENCED_ENTITY_REFERENCE_ATTRIBUTE
				)
				.findFirst().orElseThrow();

			assertEquals(Set.of("code"), entityAttrTrigger.getDependentAttributes());
			assertNull(entityAttrTrigger.getDependentReferenceName());
			assertEquals(Set.of("visible"), refAttrTrigger.getDependentAttributes());
			assertEquals("tags", refAttrTrigger.getDependentReferenceName());
		}

		@Test
		@DisplayName("Should produce cross-entity trigger for mixed local and cross-entity expression")
		void shouldProduceCrossEntityTriggerForMixedExpression() {
			final Expression expression = ExpressionFactory.parse(
				"$entity.attributes['status'] == 'ACTIVE' " +
					"&& $reference.referencedEntity.attributes['code'] == 'A'"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			// mixed expression produces only cross-entity trigger(s), not local-only
			assertEquals(1, triggers.size());
			assertEquals(
				DependencyType.REFERENCED_ENTITY_ATTRIBUTE, triggers.get(0).getDependencyType()
			);
			assertEquals(Set.of("code"), triggers.get(0).getDependentAttributes());
		}

	}

	@Nested
	@DisplayName("Multiple scopes")
	class MultipleScopesTest {

		@Test
		@DisplayName("Should build triggers for multiple scopes independently")
		void shouldBuildTriggersForMultipleScopes() {
			final Expression livExpr =
				ExpressionFactory.parse("$entity.attributes['status'] == 'ACTIVE'");
			final Expression arcExpr =
				ExpressionFactory.parse(
					"$reference.referencedEntity.attributes['archived'] == true"
				);

			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.facetedPartiallyInScope(Scope.LIVE, livExpr)
						.facetedPartiallyInScope(Scope.ARCHIVED, arcExpr)
				)
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertEquals(2, triggers.size());

			final FacetExpressionTrigger liveTrigger = triggers.stream()
				.filter(t -> t.getScope() == Scope.LIVE).findFirst().orElseThrow();
			final FacetExpressionTrigger archivedTrigger = triggers.stream()
				.filter(t -> t.getScope() == Scope.ARCHIVED).findFirst().orElseThrow();

			// LIVE expression is local-only
			assertNull(liveTrigger.getDependencyType());
			// ARCHIVED expression is cross-entity
			assertEquals(
				DependencyType.REFERENCED_ENTITY_ATTRIBUTE, archivedTrigger.getDependencyType()
			);
			assertEquals(Set.of("archived"), archivedTrigger.getDependentAttributes());
		}

	}

	@Nested
	@DisplayName("Immutability and error handling")
	class ImmutabilityAndErrorTest {

		@Test
		@DisplayName("Should return unmodifiable trigger list")
		void shouldReturnUnmodifiableTriggerList() {
			final Expression expression =
				ExpressionFactory.parse("$entity.attributes['status'] == 'ACTIVE'");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			final List<FacetExpressionTrigger> triggers =
				FacetExpressionTriggerFactory.buildTriggersForReference(ENTITY_TYPE, refSchema);

			assertThrows(UnsupportedOperationException.class, () -> triggers.add(null));
		}

		@Test
		@DisplayName("Should propagate NonTranslatableExpressionException for non-translatable cross-entity expression")
		void shouldPropagateNonTranslatableException() {
			// Arithmetic in cross-entity expression is not translatable to FilterBy
			final Expression expression = ExpressionFactory.parse(
				"$reference.referencedEntity.attributes['price'] + 5 > 10"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithExpression(
				Scope.LIVE, expression
			);

			assertThrows(
				NonTranslatableExpressionException.class,
				() -> FacetExpressionTriggerFactory.buildTriggersForReference(
					ENTITY_TYPE, refSchema
				)
			);
		}

	}

	// --- Helper methods ---

	/**
	 * Builds a reference schema with a single `facetedPartially` expression using the schema builder.
	 *
	 * @param scope      the scope to set the expression in
	 * @param expression the parsed expression
	 * @return the reference schema contract
	 */
	@Nonnull
	private static ReferenceSchemaContract buildReferenceSchemaWithExpression(
		@Nonnull Scope scope,
		@Nonnull Expression expression
	) {
		return buildReferenceSchema(builder ->
			builder.withReferenceTo(
				REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.facetedPartiallyInScope(scope, expression)
			)
		);
	}

	/**
	 * Builds a reference schema with a group entity type and a single `facetedPartially` expression.
	 *
	 * @param scope      the scope to set the expression in
	 * @param expression the parsed expression
	 * @return the reference schema contract
	 */
	@Nonnull
	private static ReferenceSchemaContract buildReferenceSchemaWithGroupAndExpression(
		@Nonnull Scope scope,
		@Nonnull Expression expression
	) {
		return buildReferenceSchema(builder ->
			builder.withReferenceTo(
				REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.withGroupType(GROUP_ENTITY_TYPE)
					.facetedPartiallyInScope(scope, expression)
			)
		);
	}

	/**
	 * Builds a reference schema by applying the given builder customizer and extracting
	 * the reference by name from the resulting entity schema.
	 *
	 * @param schemaCustomizer consumer that configures the entity schema builder
	 * @return the reference schema contract
	 */
	@Nonnull
	private static ReferenceSchemaContract buildReferenceSchema(
		@Nonnull Consumer<InternalEntitySchemaBuilder> schemaCustomizer
	) {
		final InternalEntitySchemaBuilder builder = new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
		);
		schemaCustomizer.accept(builder);
		return builder.toInstance().getReferenceOrThrowException(REFERENCE_NAME);
	}

}
