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
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPRESSION;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HistogramExpressionTriggerFactory} verifying the AND-combine between the
 * reference-level `bucketedPartially` eligibility gate and the per-histogram `assignedWhen`
 * partition selector carried on {@link io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition}.
 *
 * The four corner cases of the combine matrix are covered:
 *
 * - reference-level gate only (per-histogram partition selector null)
 * - per-histogram partition selector only (reference-level gate null)
 * - both non-null (resulting trigger must reflect attributes from both)
 * - both null (resulting trigger has no condition / no filter)
 *
 * Uses real schema objects built via {@link InternalEntitySchemaBuilder} so the test exercises
 * the production schema-construction path end-to-end.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HistogramExpressionTriggerFactory")
@Tag(ENGINE)
@Tag(HISTOGRAM)
@Tag(EXPRESSION)
class HistogramExpressionTriggerFactoryTest {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "parameter";
	private static final String REFERENCED_ENTITY_TYPE = "parameterType";
	private static final String HISTOGRAM_NAME = "priceHistogram";
	private static final String ATTR_VALUE = "value";
	private static final String ATTR_STATUS = "status";
	private static final String ATTR_VISIBLE = "visible";
	private static final String ATTR_ORDER = "order";

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		"testCatalog",
		NamingConvention.generate("testCatalog"),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	@Nested
	@DisplayName("Reference-level only (per-histogram assignedWhen is null)")
	class ReferenceLevelOnlyTest {

		@Test
		@DisplayName("Should build trigger carrying reference-level condition when per-histogram is null")
		void shouldBuildTriggerWithReferenceLevelConditionWhenPerHistogramIsNull() {
			final Expression referenceCondition =
				ExpressionFactory.parse("$entity.attributes['status'] == 'ACTIVE'");
			final Expression valueExpression =
				ExpressionFactory.parse("$reference.attributes['value']");

			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFiltering()
						.withAttribute(ATTR_VALUE, BigDecimal.class, AttributeSchemaEditor::filterable)
						.bucketedPartiallyInScope(Scope.LIVE, referenceCondition)
						.bucketedInScope(Scope.LIVE, HISTOGRAM_NAME, valueExpression, null)
				)
			);

			final List<HistogramExpressionTrigger> triggers =
				HistogramExpressionTriggerFactory.buildTriggersForReference(
					ENTITY_TYPE, refSchema, noEntityResolver()
				);

			assertEquals(1, triggers.size());
			final HistogramExpressionTrigger trigger = triggers.get(0);
			// reference-level condition reads entity.attributes['status'] -- local-only trigger
			assertFalse(trigger.hasFilterByConstraint());
			assertEquals(Set.of(ATTR_STATUS), trigger.getLocalEntityAttributes());
			assertTrue(trigger.getLocalReferenceAttributes().isEmpty());
			assertEquals(HISTOGRAM_NAME, trigger.getHistogramIndexName());
		}

	}

	@Nested
	@DisplayName("Per-histogram only (reference-level bucketedPartially gate is null)")
	class PerHistogramOnlyTest {

		@Test
		@DisplayName("Should build trigger carrying per-histogram condition when reference-level is null")
		void shouldBuildTriggerWithPerHistogramConditionWhenReferenceLevelIsNull() {
			final Expression perHistogramCondition =
				ExpressionFactory.parse("$reference.attributes['visible'] == true");
			final Expression valueExpression =
				ExpressionFactory.parse("$reference.attributes['value']");

			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFiltering()
						.withAttribute(ATTR_VALUE, BigDecimal.class, AttributeSchemaEditor::filterable)
						.withAttribute(ATTR_VISIBLE, Boolean.class, AttributeSchemaEditor::filterable)
						.bucketedInScope(Scope.LIVE, HISTOGRAM_NAME, valueExpression, perHistogramCondition)
				)
			);

			final List<HistogramExpressionTrigger> triggers =
				HistogramExpressionTriggerFactory.buildTriggersForReference(
					ENTITY_TYPE, refSchema, noEntityResolver()
				);

			assertEquals(1, triggers.size());
			final HistogramExpressionTrigger trigger = triggers.get(0);
			// per-histogram condition reads $reference.attributes['visible'] -- local-only trigger
			assertFalse(trigger.hasFilterByConstraint());
			assertEquals(Set.of(ATTR_VISIBLE), trigger.getLocalReferenceAttributes());
			assertTrue(trigger.getLocalEntityAttributes().isEmpty());
			assertEquals(HISTOGRAM_NAME, trigger.getHistogramIndexName());
		}

	}

	@Nested
	@DisplayName("Both reference-level bucketedPartially gate and per-histogram assignedWhen present")
	class BothPresentTest {

		@Test
		@DisplayName("Should AND-combine both expressions when both are present")
		void shouldAndCombineBothExpressionsWhenBothPresent() {
			// reference-level reads entity-level attribute; per-histogram reads reference-level attribute
			// -- the AND-combined expression must surface BOTH attribute groups on the resulting trigger
			final Expression referenceCondition =
				ExpressionFactory.parse("$entity.attributes['status'] == 'ACTIVE'");
			final Expression perHistogramCondition =
				ExpressionFactory.parse("$reference.attributes['visible'] == true");
			final Expression valueExpression =
				ExpressionFactory.parse("$reference.attributes['value']");

			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFiltering()
						.withAttribute(ATTR_VALUE, BigDecimal.class, AttributeSchemaEditor::filterable)
						.withAttribute(ATTR_VISIBLE, Boolean.class, AttributeSchemaEditor::filterable)
						.bucketedPartiallyInScope(Scope.LIVE, referenceCondition)
						.bucketedInScope(
							Scope.LIVE, HISTOGRAM_NAME, valueExpression, perHistogramCondition
						)
				)
			);

			final List<HistogramExpressionTrigger> triggers =
				HistogramExpressionTriggerFactory.buildTriggersForReference(
					ENTITY_TYPE, refSchema, noEntityResolver()
				);

			assertEquals(1, triggers.size());
			final HistogramExpressionTrigger trigger = triggers.get(0);
			assertFalse(trigger.hasFilterByConstraint());
			// AND-combined: trigger sees BOTH entity-level and reference-level attributes
			assertEquals(Set.of(ATTR_STATUS), trigger.getLocalEntityAttributes());
			assertEquals(Set.of(ATTR_VISIBLE), trigger.getLocalReferenceAttributes());
			assertEquals(HISTOGRAM_NAME, trigger.getHistogramIndexName());
		}

		@Test
		@DisplayName("Should expose translated FilterBy when AND-combined expression is cross-entity")
		void shouldExposeTranslatedFilterByWhenCombinedExpressionIsCrossEntity() {
			// reference-level reads referencedEntity attribute (cross-entity)
			// per-histogram reads local reference attribute -- the combined expression is cross-entity
			// so the resulting trigger must carry a translated FilterBy
			final Expression referenceCondition = ExpressionFactory.parse(
				"$reference.referencedEntity.attributes['code'] == 'A'"
			);
			final Expression perHistogramCondition =
				ExpressionFactory.parse("$reference.attributes['order'] > 0");
			final Expression valueExpression =
				ExpressionFactory.parse("$reference.attributes['value']");

			final EntitySchemaContract referencedEntitySchema = buildEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, "code", String.class
			);

			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFiltering()
						.withAttribute(ATTR_VALUE, BigDecimal.class, AttributeSchemaEditor::filterable)
						.withAttribute(ATTR_ORDER, Integer.class, AttributeSchemaEditor::filterable)
						.bucketedPartiallyInScope(Scope.LIVE, referenceCondition)
						.bucketedInScope(
							Scope.LIVE, HISTOGRAM_NAME, valueExpression, perHistogramCondition
						)
				)
			);

			final List<HistogramExpressionTrigger> triggers =
				HistogramExpressionTriggerFactory.buildTriggersForReference(
					ENTITY_TYPE, refSchema,
					entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
				);

			assertEquals(1, triggers.size());
			final HistogramExpressionTrigger trigger = triggers.get(0);
			// cross-entity AND-combined expression -- FilterBy must exist
			assertTrue(trigger.hasFilterByConstraint());
			assertNotNull(trigger.getFilterByConstraint());
			// local deps from the per-histogram side ($reference.attributes['order']) are
			// preserved even on the cross-entity trigger
			assertEquals(Set.of(ATTR_ORDER), trigger.getLocalReferenceAttributes());
			assertEquals(HISTOGRAM_NAME, trigger.getHistogramIndexName());
		}

	}

	@Nested
	@DisplayName("Both reference-level bucketedPartially gate and per-histogram assignedWhen null")
	class NeitherPresentTest {

		@Test
		@DisplayName("Should build unconditional trigger when both expressions are null")
		void shouldBuildUnconditionalTriggerWhenBothExpressionsNull() {
			final Expression valueExpression =
				ExpressionFactory.parse("$reference.attributes['value']");

			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(
					REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFiltering()
						.withAttribute(ATTR_VALUE, BigDecimal.class, AttributeSchemaEditor::filterable)
						.bucketedInScope(Scope.LIVE, HISTOGRAM_NAME, valueExpression, null)
				)
			);

			final List<HistogramExpressionTrigger> triggers =
				HistogramExpressionTriggerFactory.buildTriggersForReference(
					ENTITY_TYPE, refSchema, noEntityResolver()
				);

			assertEquals(1, triggers.size());
			final HistogramExpressionTrigger trigger = triggers.get(0);
			assertFalse(trigger.hasFilterByConstraint());
			assertTrue(trigger.getLocalEntityAttributes().isEmpty());
			assertTrue(trigger.getLocalReferenceAttributes().isEmpty());
			assertTrue(trigger.getLocalAssociatedData().isEmpty());
			assertFalse(trigger.usesParent());
			assertEquals(HISTOGRAM_NAME, trigger.getHistogramIndexName());
		}

	}

	// --- Helper methods ---

	/**
	 * Builds a reference schema by applying the given builder customizer and extracting the
	 * reference by name from the resulting entity schema.
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

	/**
	 * Builds an entity schema for a referenced entity type with a single filterable attribute.
	 *
	 * @param entityType    the entity type name
	 * @param attributeName the attribute name
	 * @param attributeType the attribute type
	 * @return the entity schema contract
	 */
	@Nonnull
	private static EntitySchemaContract buildEntitySchemaWithAttribute(
		@Nonnull String entityType,
		@Nonnull String attributeName,
		@Nonnull Class<? extends java.io.Serializable> attributeType
	) {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(entityType)
		)
			.withAttribute(attributeName, attributeType, AttributeSchemaEditor::filterable)
			.toInstance();
	}

	/**
	 * Returns a schema resolver that always returns null — used when no cross-entity attribute
	 * lookup is needed.
	 *
	 * @return schema resolver returning null for any entity type
	 */
	@Nonnull
	private static Function<String, EntitySchemaContract> noEntityResolver() {
		return name -> null;
	}

	/**
	 * Returns a schema resolver that resolves a single entity type to the given schema.
	 *
	 * @param entityType   the entity type to resolve
	 * @param entitySchema the schema to return
	 * @return schema resolver for the single entity type
	 */
	@Nonnull
	private static Function<String, EntitySchemaContract> entityResolver(
		@Nonnull String entityType,
		@Nonnull EntitySchemaContract entitySchema
	) {
		return name -> entityType.equals(name) ? entitySchema : null;
	}

}
