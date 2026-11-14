/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.utils.NamingConvention;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * Tests for {@link AssociatedDataSchema}.
 */
@DisplayName("AssociatedDataSchema")
@Tag(CONTRACT)
@Tag(SCHEMA)
class AssociatedDataSchemaTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should build minimal schema with name and type")
		void shouldBuildMinimalSchema() {
			final AssociatedDataSchema schema =
				(AssociatedDataSchema) AssociatedDataSchema._internalBuild("labels", String.class, ConflictResolutionOverride.INHERITED);

			assertEquals("labels", schema.getName());
			assertSame(String.class, schema.getType());
			assertSame(String.class, schema.getPlainType());
			assertFalse(schema.isLocalized());
			assertFalse(schema.isNullable());
			assertNull(schema.getDescription());
			assertNull(schema.getDeprecationNotice());
		}

		@Test
		@DisplayName("should build full schema with all parameters")
		void shouldBuildFullSchema() {
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"description",
				"Product description",
				"Use richDescription instead",
				String.class,
				true, true,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertEquals("description", schema.getName());
			assertEquals("Product description", schema.getDescription());
			assertEquals("Use richDescription instead", schema.getDeprecationNotice());
			assertTrue(schema.isLocalized());
			assertTrue(schema.isNullable());
		}

		@Test
		@DisplayName("should accept custom name variants")
		void shouldAcceptCustomNameVariants() {
			final Map<NamingConvention, String> variants = NamingConvention.generate("productData");
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"productData", variants,
				null, null,
				String.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertEquals(
				variants.get(NamingConvention.CAMEL_CASE),
				schema.getNameVariant(NamingConvention.CAMEL_CASE)
			);
		}

		@Test
		@DisplayName("should wrap primitive types to wrapper types")
		void shouldWrapPrimitiveTypes() {
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"count", null, null, int.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertSame(Integer.class, schema.getType());
		}

		@Test
		@DisplayName("should resolve plain type from array type")
		void shouldResolvePlainTypeFromArray() {
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"tags", null, null, String[].class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertSame(String[].class, schema.getType());
			assertSame(String.class, schema.getPlainType());
		}

		@Test
		@DisplayName("should use ComplexDataObject for unsupported types")
		void shouldUseComplexDataObjectForUnsupportedTypes() {
			final AssociatedDataSchema schema = AssociatedDataSchema._internalBuild(
				"metadata", null, null, java.util.HashMap.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertSame(ComplexDataObject.class, schema.getType());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for same parameters")
		void shouldBeEqual() {
			final AssociatedDataSchema a = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);
			final AssociatedDataSchema b = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when names differ")
		void shouldNotBeEqualWhenNamesDiffer() {
			final AssociatedDataSchema a = AssociatedDataSchema._internalBuild(
				"data1", null, null, String.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);
			final AssociatedDataSchema b = AssociatedDataSchema._internalBuild(
				"data2", null, null, String.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when localized differs")
		void shouldNotBeEqualWhenLocalizedDiffers() {
			final AssociatedDataSchema a = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, true, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);
			final AssociatedDataSchema b = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when conflict resolution override differs")
		void shouldNotBeEqualWhenConflictResolutionOverrideDiffers() {
			final AssociatedDataSchema a = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, false, false,
				null,
				null,
				ConflictResolutionOverride.INHERITED,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);
			final AssociatedDataSchema b = AssociatedDataSchema._internalBuild(
				"data", null, null, String.class, false, false,
				null,
				null,
				ConflictResolutionOverride.GRANULAR,
				FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
				AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
			);

			assertNotEquals(a, b);
			assertNotEquals(a.hashCode(), b.hashCode());
		}
	}

	/** Ranking weight participates in the value contract, with a canonical representation for zero. */
	@Test
	void shouldIncludeSearchWeightInValueSemantics() {
		final AssociatedDataSchema zero = schemaWithSearchWeight(0.0f);
		final AssociatedDataSchema weighted = schemaWithSearchWeight(2.5f);
		assertNotEquals(zero, weighted);
		assertNotEquals(zero.hashCode(), weighted.hashCode());
		assertTrue(weighted.toString().contains("searchWeight=2.5"));
		final AssociatedDataSchema negativeZero = schemaWithSearchWeight(-0.0f);
		assertEquals(Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(negativeZero.getSearchWeight()));
		assertEquals(zero, negativeZero);
		assertEquals(zero.hashCode(), negativeZero.hashCode());
	}

	/** Builds a DTO whose only variable setting is the ranking weight. */
	@Nonnull
	private static AssociatedDataSchema schemaWithSearchWeight(float weight) {
		return AssociatedDataSchema._internalBuild(
			"labels", null, null, String.class, false, false, null, null,
			ConflictResolutionOverride.INHERITED, FulltextAnalyzerSchemaContract.TEXT,
			AttributeSchemaContract.DEFAULT_LENGTH_PIVOT, weight
		);
	}
}
