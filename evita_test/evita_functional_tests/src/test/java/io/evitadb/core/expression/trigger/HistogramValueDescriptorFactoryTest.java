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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.EXPRESSION;
import static io.evitadb.test.TestTags.HISTOGRAM;

/**
 * Tests for {@link HistogramValueDescriptorFactory} — validates expression classification,
 * attribute schema validation, numeric type enforcement, and default value extraction.
 * Uses real schema objects built via {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HistogramValueDescriptorFactory")
@Tag(ENGINE)
@Tag(EXPRESSION)
@Tag(HISTOGRAM)
class HistogramValueDescriptorFactoryTest {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "parameter";
	private static final String REFERENCED_ENTITY_TYPE = "parameterType";
	private static final String HISTOGRAM_NAME = "priceHistogram";
	private static final String ATTR_VALUE = "value";
	private static final String ATTR_WEIGHT = "weight";
	private static final String ATTR_STATUS = "status";

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		"testCatalog",
		NamingConvention.generate("testCatalog"),
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * Tests for reference-level attribute expressions of the form `$reference.attributes['x']`.
	 */
	@Nested
	@DisplayName("Reference attribute expressions ($reference.attributes['x'])")
	class ReferenceAttributeTest {

		@Test
		@DisplayName("should resolve BigDecimal reference attribute")
		void shouldResolveBigDecimalReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['value']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCE_ATTRIBUTE, result.source());
			assertNull(result.sourceEntityType());
			assertEquals(ATTR_VALUE, result.sourceAttributeName());
			assertEquals(BigDecimal.class, result.plainType());
			assertFalse(result.arrayType());
			assertFalse(result.localized());
			assertNull(result.defaultValue());
		}

		@Test
		@DisplayName("should resolve Integer reference attribute")
		void shouldResolveIntegerReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['weight']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_WEIGHT, Integer.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCE_ATTRIBUTE, result.source());
			assertEquals(ATTR_WEIGHT, result.sourceAttributeName());
			assertEquals(Integer.class, result.plainType());
			assertFalse(result.localized());
		}

		@Test
		@DisplayName("should resolve Long reference attribute")
		void shouldResolveLongReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['weight']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_WEIGHT, Long.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCE_ATTRIBUTE, result.source());
			assertEquals(Long.class, result.plainType());
			assertFalse(result.localized());
		}

		@Test
		@DisplayName("should resolve Short reference attribute")
		void shouldResolveShortReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['weight']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_WEIGHT, Short.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCE_ATTRIBUTE, result.source());
			assertEquals(Short.class, result.plainType());
			assertFalse(result.localized());
		}

		@Test
		@DisplayName("should resolve Byte reference attribute")
		void shouldResolveByteReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['weight']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_WEIGHT, Byte.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCE_ATTRIBUTE, result.source());
			assertEquals(Byte.class, result.plainType());
			assertFalse(result.localized());
		}

		@Test
		@DisplayName("should detect array-typed reference attribute")
		void shouldDetectArrayTypedReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['value']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal[].class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertTrue(result.arrayType());
			assertFalse(result.localized());
		}
	}

	/**
	 * Tests for referenced entity attribute expressions of the form
	 * `$reference.referencedEntity?.attributes['x']`.
	 */
	@Nested
	@DisplayName("Referenced entity attribute expressions ($reference.referencedEntity?.attributes['x'])")
	class ReferencedEntityAttributeTest {

		@Test
		@DisplayName("should resolve referenced entity attribute with BigDecimal type")
		void shouldResolveReferencedEntityAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.attributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);
			final EntitySchemaContract referencedEntitySchema = buildEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, ATTR_VALUE, BigDecimal.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema,
				entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, result.source());
			assertEquals(REFERENCED_ENTITY_TYPE, result.sourceEntityType());
			assertEquals(ATTR_VALUE, result.sourceAttributeName());
			assertEquals(BigDecimal.class, result.plainType());
			assertFalse(result.arrayType());
			assertFalse(result.localized());
			assertNull(result.defaultValue());
		}

		@Test
		@DisplayName("should resolve referenced entity attribute with Integer type")
		void shouldResolveReferencedEntityIntegerAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.attributes['weight']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);
			final EntitySchemaContract referencedEntitySchema = buildEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, ATTR_WEIGHT, Integer.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema,
				entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, result.source());
			assertEquals(ATTR_WEIGHT, result.sourceAttributeName());
			assertEquals(Integer.class, result.plainType());
			assertFalse(result.localized());
		}
	}

	/**
	 * Tests for default value extraction via the null-coalesce (`??`) operator.
	 */
	@Nested
	@DisplayName("Default value extraction via ?? operator")
	class DefaultValueExtractionTest {

		@Test
		@DisplayName("should extract integer default value from reference attribute expression")
		void shouldExtractIntegerDefaultValue() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['value'] ?? 0");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertNotNull(result.defaultValue());
			assertEquals(BigDecimal.class, result.plainType());
			assertFalse(result.localized());
			// default value is converted to the attribute's plain type (BigDecimal)
			assertTrue(result.defaultValue() instanceof BigDecimal);
			assertEquals(0, ((BigDecimal) result.defaultValue()).compareTo(BigDecimal.ZERO));
		}

		@Test
		@DisplayName("should convert default value to Integer when attribute type is Integer")
		void shouldConvertDefaultValueToInteger() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['weight'] ?? 42");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_WEIGHT, Integer.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(Integer.class, result.plainType());
			assertFalse(result.localized());
			assertNotNull(result.defaultValue());
			assertTrue(result.defaultValue() instanceof Integer);
			assertEquals(42, result.defaultValue().intValue());
		}

		@Test
		@DisplayName("should convert default value to Short when attribute type is Short")
		void shouldConvertDefaultValueToShort() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['weight'] ?? 7");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_WEIGHT, Short.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(Short.class, result.plainType());
			assertFalse(result.localized());
			assertNotNull(result.defaultValue());
			assertTrue(result.defaultValue() instanceof Short);
			assertEquals((short) 7, result.defaultValue().shortValue());
		}

		@Test
		@DisplayName("should extract decimal default value from referenced entity attribute expression")
		void shouldExtractDecimalDefaultValue() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.attributes['value'] ?? 99.5"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);
			final EntitySchemaContract referencedEntitySchema = buildEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, ATTR_VALUE, BigDecimal.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema,
				entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
			);

			assertNotNull(result);
			assertFalse(result.localized());
			assertNotNull(result.defaultValue());
			assertTrue(result.defaultValue() instanceof BigDecimal);
			assertEquals(0, ((BigDecimal) result.defaultValue()).compareTo(new BigDecimal("99.5")));
		}

		@Test
		@DisplayName("should return null default value when no ?? operator is present")
		void shouldReturnNullDefaultValueWhenNoCoalesceOperator() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['value']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertFalse(result.localized());
			assertNull(result.defaultValue());
		}
	}

	/**
	 * Tests for validation error paths — unsupported expression forms, non-existent attributes,
	 * non-numeric types, and non-filterable attributes.
	 */
	@Nested
	@DisplayName("Validation errors")
	class ValidationErrorTest {

		@Test
		@DisplayName("should reject non-existent reference attribute")
		void shouldRejectNonExistentReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['nonExistent']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains("nonExistent"));
			assertTrue(ex.getMessage().contains("does not exist"));
		}

		@Test
		@DisplayName("should reject non-existent referenced entity attribute")
		void shouldRejectNonExistentReferencedEntityAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.attributes['nonExistent']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);
			final EntitySchemaContract referencedEntitySchema = buildEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, ATTR_VALUE, BigDecimal.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema,
					entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
				)
			);
			assertTrue(ex.getMessage().contains("nonExistent"));
			assertTrue(ex.getMessage().contains("does not exist"));
		}

		@Test
		@DisplayName("should reject non-existent referenced entity type")
		void shouldRejectNonExistentReferencedEntityType() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.attributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains(REFERENCED_ENTITY_TYPE));
			assertTrue(ex.getMessage().contains("does not exist"));
		}

		@Test
		@DisplayName("should reject non-filterable reference attribute")
		void shouldRejectNonFilterableReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['value']");
			// build a reference with a non-filterable attribute
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFiltering()
						.withAttribute(ATTR_VALUE, BigDecimal.class)
				)
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains("not filterable"));
		}

		@Test
		@DisplayName("should reject non-numeric reference attribute (String)")
		void shouldRejectNonNumericReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse("$reference.attributes['status']");
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_STATUS, String.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains("non-numeric"));
			assertTrue(ex.getMessage().contains("String"));
		}

		@Test
		@DisplayName("should reject non-numeric referenced entity attribute (Boolean)")
		void shouldRejectNonNumericReferencedEntityAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.attributes['status']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);
			final EntitySchemaContract referencedEntitySchema = buildEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, ATTR_STATUS, Boolean.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema,
					entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
				)
			);
			assertTrue(ex.getMessage().contains("non-numeric"));
		}

		@Test
		@DisplayName("should reject entity-level attribute ($entity.attributes['x'])")
		void shouldRejectEntityLevelAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$entity.attributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
		}

		@Test
		@DisplayName("should reject parent entity attribute ($entity.parentEntity)")
		void shouldRejectParentEntityAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$entity.parentEntity.attributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains("parent entity"));
		}

		@Test
		@DisplayName("should reject group entity attribute ($reference.groupEntity)")
		void shouldRejectGroupEntityAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.groupEntity?.attributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains("unsupported expression form"));
		}

		@Test
		@DisplayName("should reject localized accessor on non-localized reference attribute")
		void shouldRejectLocalizedAccessorOnNonLocalizedAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.localizedAttributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains("localizedAttributes"));
			assertTrue(ex.getMessage().contains("not localized"));
		}

		@Test
		@DisplayName("should reject non-localized accessor on localized reference attribute")
		void shouldRejectNonLocalizedAccessorOnLocalizedAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.attributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithLocalizedRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains("attributes accessor"));
			assertTrue(ex.getMessage().contains("localized"));
		}

		@Test
		@DisplayName("should reject localized accessor on non-localized referenced entity attribute")
		void shouldRejectLocalizedAccessorOnNonLocalizedReferencedEntityAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.localizedAttributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);
			final EntitySchemaContract referencedEntitySchema = buildEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, ATTR_VALUE, BigDecimal.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema,
					entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
				)
			);
			assertTrue(ex.getMessage().contains("localizedAttributes"));
			assertTrue(ex.getMessage().contains("not localized"));
		}

		@Test
		@DisplayName("should reject non-localized accessor on localized referenced entity attribute")
		void shouldRejectNonLocalizedAccessorOnLocalizedReferencedEntityAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.attributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);
			final EntitySchemaContract referencedEntitySchema = buildLocalizedEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, ATTR_VALUE, BigDecimal.class
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema,
					entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
				)
			);
			assertTrue(ex.getMessage().contains("attributes accessor"));
			assertTrue(ex.getMessage().contains("localized"));
		}

		@Test
		@DisplayName("should reject expression referencing multiple attributes")
		void shouldRejectMultipleAttributes() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.attributes['value'] + $reference.attributes['weight']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFiltering()
						.withAttribute(ATTR_VALUE, BigDecimal.class, AttributeSchemaEditor::filterable)
						.withAttribute(ATTR_WEIGHT, Integer.class, AttributeSchemaEditor::filterable)
				)
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> HistogramValueDescriptorFactory.build(
					expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
				)
			);
			assertTrue(ex.getMessage().contains("multiple attributes"));
		}
	}

	/**
	 * Tests for localized attribute expressions — both reference-level
	 * (`$reference.localizedAttributes['x']`) and referenced entity-level
	 * (`$reference.referencedEntity?.localizedAttributes['x']`).
	 */
	@Nested
	@DisplayName("Localized attribute expressions")
	class LocalizedAttributeTest {

		@Test
		@DisplayName("should build descriptor for localized reference attribute")
		void shouldBuildDescriptorForLocalizedReferenceAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.localizedAttributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithLocalizedRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCE_ATTRIBUTE, result.source());
			assertNull(result.sourceEntityType());
			assertEquals(ATTR_VALUE, result.sourceAttributeName());
			assertEquals(BigDecimal.class, result.plainType());
			assertFalse(result.arrayType());
			assertTrue(result.localized());
			assertNull(result.defaultValue());
		}

		@Test
		@DisplayName("should build descriptor for localized referenced entity attribute")
		void shouldBuildDescriptorForLocalizedReferencedEntityAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.referencedEntity?.localizedAttributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchema(builder ->
				builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				                        ReferenceSchemaEditor::indexedForFiltering
				)
			);
			final EntitySchemaContract referencedEntitySchema = buildLocalizedEntitySchemaWithAttribute(
				REFERENCED_ENTITY_TYPE, ATTR_VALUE, BigDecimal.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema,
				entityResolver(REFERENCED_ENTITY_TYPE, referencedEntitySchema)
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, result.source());
			assertEquals(REFERENCED_ENTITY_TYPE, result.sourceEntityType());
			assertEquals(ATTR_VALUE, result.sourceAttributeName());
			assertEquals(BigDecimal.class, result.plainType());
			assertFalse(result.arrayType());
			assertTrue(result.localized());
			assertNull(result.defaultValue());
		}

		@Test
		@DisplayName("should build descriptor for localized reference attribute with default value")
		void shouldBuildDescriptorForLocalizedReferenceAttributeWithDefault() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.localizedAttributes['value'] ?? 0"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithLocalizedRefAttribute(
				ATTR_VALUE, BigDecimal.class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertEquals(HistogramValueSource.REFERENCE_ATTRIBUTE, result.source());
			assertEquals(ATTR_VALUE, result.sourceAttributeName());
			assertEquals(BigDecimal.class, result.plainType());
			assertTrue(result.localized());
			assertNotNull(result.defaultValue());
			assertTrue(result.defaultValue() instanceof BigDecimal);
			assertEquals(0, ((BigDecimal) result.defaultValue()).compareTo(BigDecimal.ZERO));
		}

		@Test
		@DisplayName("should detect array type for localized attribute")
		void shouldDetectArrayTypeForLocalizedAttribute() {
			final Expression expr = ExpressionFactory.parse(
				"$reference.localizedAttributes['value']"
			);
			final ReferenceSchemaContract refSchema = buildReferenceSchemaWithLocalizedRefAttribute(
				ATTR_VALUE, BigDecimal[].class
			);

			final HistogramValueDescriptor result = HistogramValueDescriptorFactory.build(
				expr, REFERENCE_NAME, HISTOGRAM_NAME, Scope.LIVE, refSchema, noEntityResolver()
			);

			assertNotNull(result);
			assertTrue(result.arrayType());
			assertTrue(result.localized());
		}
	}

	// --- Helper methods ---

	/**
	 * Builds a reference schema with a single filterable attribute on the reference itself.
	 *
	 * @param attributeName the attribute name
	 * @param attributeType the attribute type (e.g. BigDecimal.class, Integer.class)
	 * @return the reference schema contract
	 */
	@Nonnull
	private static ReferenceSchemaContract buildReferenceSchemaWithRefAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> attributeType
	) {
		return buildReferenceSchema(builder ->
			builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(attributeName, attributeType, AttributeSchemaEditor::filterable)
			)
		);
	}

	/**
	 * Builds a reference schema with a single localized, filterable attribute on the reference itself.
	 *
	 * @param attributeName the attribute name
	 * @param attributeType the attribute type (e.g. BigDecimal.class, Integer.class)
	 * @return the reference schema contract
	 */
	@Nonnull
	private static ReferenceSchemaContract buildReferenceSchemaWithLocalizedRefAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> attributeType
	) {
		return buildReferenceSchema(builder ->
			builder.withReferenceTo(REFERENCE_NAME, REFERENCED_ENTITY_TYPE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(attributeName, attributeType, thatIs -> thatIs.localized().filterable())
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
		@Nonnull Class<? extends Serializable> attributeType
	) {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(entityType)
		)
			.withAttribute(attributeName, attributeType, AttributeSchemaEditor::filterable)
			.toInstance();
	}

	/**
	 * Builds an entity schema for a referenced entity type with a single localized, filterable attribute.
	 *
	 * @param entityType    the entity type name
	 * @param attributeName the attribute name
	 * @param attributeType the attribute type
	 * @return the entity schema contract
	 */
	@Nonnull
	private static EntitySchemaContract buildLocalizedEntitySchemaWithAttribute(
		@Nonnull String entityType,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> attributeType
	) {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, EntitySchema._internalBuild(entityType)
		)
			.withAttribute(attributeName, attributeType, thatIs -> thatIs.localized().filterable())
			.toInstance();
	}

	/**
	 * Returns a schema resolver that always returns null — used when the expression only
	 * references reference-level attributes and no entity resolution is needed.
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
	 * @return schema resolver for a single entity type
	 */
	@Nonnull
	private static Function<String, EntitySchemaContract> entityResolver(
		@Nonnull String entityType,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final Map<String, EntitySchemaContract> schemas = Map.of(entityType, entitySchema);
		return schemas::get;
	}

}
