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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RepresentativeAttributeDefinition}.
 */
@DisplayName("RepresentativeAttributeDefinition")
class RepresentativeAttributeDefinitionTest {

	/**
	 * Standard fixture: 3 attributes ("gamma" non-representative, "beta" and "alpha" representative).
	 */
	@Nonnull
	private static Map<String, AttributeSchema> prepareAttributes() {
		final Map<String, AttributeSchema> attributes = new LinkedHashMap<>();
		attributes.put("gamma", AttributeSchema._internalBuild(
			"gamma", null, null, null,
			false, false, false, String.class, "G"
		));
		attributes.put("beta", AttributeSchema._internalBuild(
			"beta", null, null, null,
			false, false, true, String.class, "B"
		));
		attributes.put("alpha", AttributeSchema._internalBuild(
			"alpha", null, null, null,
			false, false, true, String.class, "A"
		));
		return attributes;
	}

	/**
	 * Single representative attribute with default "X".
	 */
	@Nonnull
	private static Map<String, AttributeSchema> prepareSingleRepresentativeAttribute() {
		final Map<String, AttributeSchema> attributes = new LinkedHashMap<>();
		attributes.put("only", AttributeSchema._internalBuild(
			"only", null, null, null,
			false, false, true, String.class, "X"
		));
		return attributes;
	}

	/**
	 * All attributes are representative.
	 */
	@Nonnull
	private static Map<String, AttributeSchema> prepareAllRepresentativeAttributes() {
		final Map<String, AttributeSchema> attributes = new LinkedHashMap<>();
		attributes.put("beta", AttributeSchema._internalBuild(
			"beta", null, null, null,
			false, false, true, String.class, "B"
		));
		attributes.put("alpha", AttributeSchema._internalBuild(
			"alpha", null, null, null,
			false, false, true, String.class, "A"
		));
		return attributes;
	}

	/**
	 * Map with no representative attributes.
	 */
	@Nonnull
	private static Map<String, AttributeSchema> prepareNoRepresentativeAttributes() {
		final Map<String, AttributeSchema> attributes = new LinkedHashMap<>();
		attributes.put("gamma", AttributeSchema._internalBuild(
			"gamma", null, null, null,
			false, false, false, String.class, "G"
		));
		attributes.put("delta", AttributeSchema._internalBuild(
			"delta", null, null, null,
			false, false, false, String.class, "D"
		));
		return attributes;
	}

	/**
	 * Representative attribute with null default value.
	 */
	@Nonnull
	private static Map<String, AttributeSchema> prepareAttributesWithNullDefault() {
		final Map<String, AttributeSchema> attributes = new LinkedHashMap<>();
		attributes.put("name", AttributeSchema._internalBuild(
			"name", null, null, null,
			false, false, true, String.class, null
		));
		return attributes;
	}

	@Nonnull
	private static ReferenceContract refWithValues(@Nonnull Map<String, AttributeValue> values) {
		final ReferenceContract ref = Mockito.mock(ReferenceContract.class);
		Mockito.when(ref.getAttributeValue(ArgumentMatchers.anyString())).thenAnswer(invocation -> {
			final String attributeName = invocation.getArgument(0, String.class);
			final AttributeValue val = values.get(attributeName);
			return Optional.ofNullable(val);
		});
		return ref;
	}

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should collect and sort representative attributes")
		void shouldCollectAndSortRepresentativeAttributes() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final List<String> names = rad.getAttributeNames();
			assertEquals(List.of("alpha", "beta"), names);

			final Serializable[] defaults = rad.getDefaultValues();
			assertArrayEquals(new Serializable[]{"A", "B"}, defaults);
		}

		@Test
		@DisplayName("should handle empty attribute map")
		void shouldHandleEmptyAttributeMap() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(Collections.emptyMap());

			assertTrue(rad.getAttributeNames().isEmpty());
			assertEquals(0, rad.getDefaultValues().length);
			assertTrue(rad.getAttributeNameIndex("anything").isEmpty());
		}

		@Test
		@DisplayName("should handle map where no attributes are representative")
		void shouldHandleMapWhereNoAttributesAreRepresentative() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(prepareNoRepresentativeAttributes());

			assertTrue(rad.getAttributeNames().isEmpty());
			assertEquals(0, rad.getDefaultValues().length);
		}

		@Test
		@DisplayName("should handle single representative attribute")
		void shouldHandleSingleRepresentativeAttribute() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(prepareSingleRepresentativeAttribute());

			assertEquals(List.of("only"), rad.getAttributeNames());
			assertArrayEquals(new Serializable[]{"X"}, rad.getDefaultValues());
		}

		@Test
		@DisplayName("should handle all attributes being representative")
		void shouldHandleAllAttributesBeingRepresentative() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(prepareAllRepresentativeAttributes());

			assertEquals(List.of("alpha", "beta"), rad.getAttributeNames());
			assertArrayEquals(new Serializable[]{"A", "B"}, rad.getDefaultValues());
		}

		@Test
		@DisplayName("should handle null default values")
		void shouldHandleNullDefaultValues() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(prepareAttributesWithNullDefault());

			assertEquals(List.of("name"), rad.getAttributeNames());
			assertArrayEquals(new Serializable[]{null}, rad.getDefaultValues());
		}
	}

	@Nested
	@DisplayName("Default values")
	class DefaultValues {

		@Test
		@DisplayName("should provide defensive copy of default values")
		void shouldProvideDefensiveCopyOfDefaultValues() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final Serializable[] first = rad.getDefaultValues();
			final Serializable[] second = rad.getDefaultValues();
			assertNotSame(first, second);
			assertNotSame(first, rad.defaultValues);

			first[0] = "X";
			assertArrayEquals(new Serializable[]{"A", "B"}, second);
		}

		@Test
		@DisplayName("should return shared empty array instance when no representative attributes")
		void shouldReturnSharedEmptyArrayInstanceWhenNoRepresentativeAttributes() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(Collections.emptyMap());

			final Serializable[] first = rad.getDefaultValues();
			final Serializable[] second = rad.getDefaultValues();
			assertSame(first, second, "Empty case should return shared ArrayUtils.EMPTY_SERIALIZABLE_ARRAY");
			assertSame(ArrayUtils.EMPTY_SERIALIZABLE_ARRAY, first);
		}
	}

	@Nested
	@DisplayName("Attribute name index")
	class AttributeNameIndex {

		@Test
		@DisplayName("should return correct index for known attributes")
		void shouldReturnCorrectIndexForKnownAttributes() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final OptionalInt idxAlpha = rad.getAttributeNameIndex("alpha");
			assertTrue(idxAlpha.isPresent());
			assertEquals(0, idxAlpha.getAsInt());

			final OptionalInt idxBeta = rad.getAttributeNameIndex("beta");
			assertTrue(idxBeta.isPresent());
			assertEquals(1, idxBeta.getAsInt());
		}

		@Test
		@DisplayName("should return empty index for unknown attribute")
		void shouldReturnEmptyIndexForUnknownAttribute() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			assertTrue(rad.getAttributeNameIndex("does_not_exist").isEmpty());
		}

		@Test
		@DisplayName("should return empty index for non-representative attribute")
		void shouldReturnEmptyIndexForNonRepresentativeAttribute() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			assertTrue(rad.getAttributeNameIndex("gamma").isEmpty(),
				"gamma exists in source map but is not representative");
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal when constructed from same attributes")
		void shouldBeEqualWhenConstructedFromSameAttributes() {
			final RepresentativeAttributeDefinition a = new RepresentativeAttributeDefinition(prepareAttributes());
			final RepresentativeAttributeDefinition b = new RepresentativeAttributeDefinition(prepareAttributes());

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal when default values differ")
		void shouldNotBeEqualWhenDefaultValuesDiffer() {
			final Map<String, AttributeSchema> different = new LinkedHashMap<>(prepareAttributes());
			different.put("beta", AttributeSchema._internalBuild(
				"beta", null, null, null, false, false, true, String.class, "B2"
			));

			final RepresentativeAttributeDefinition a = new RepresentativeAttributeDefinition(prepareAttributes());
			final RepresentativeAttributeDefinition c = new RepresentativeAttributeDefinition(different);
			assertNotEquals(a, c);
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());
			assertEquals(rad, rad);
		}

		@Test
		@DisplayName("should return false when compared to null")
		void shouldReturnFalseWhenComparedToNull() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());
			assertNotEquals(null, rad);
		}

		@Test
		@DisplayName("should return false when compared to different type")
		void shouldReturnFalseWhenComparedToDifferentType() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());
			assertNotEquals("a string", rad);
		}

		@Test
		@DisplayName("should not be equal when attribute names differ")
		void shouldNotBeEqualWhenAttributeNamesDiffer() {
			final Map<String, AttributeSchema> other = new LinkedHashMap<>();
			other.put("x", AttributeSchema._internalBuild(
				"x", null, null, null, false, false, true, String.class, "A"
			));
			other.put("y", AttributeSchema._internalBuild(
				"y", null, null, null, false, false, true, String.class, "B"
			));

			final RepresentativeAttributeDefinition a = new RepresentativeAttributeDefinition(prepareAttributes());
			final RepresentativeAttributeDefinition b = new RepresentativeAttributeDefinition(other);
			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should be equal for two empty definitions")
		void shouldBeEqualForTwoEmptyDefinitions() {
			final RepresentativeAttributeDefinition a =
				new RepresentativeAttributeDefinition(Collections.emptyMap());
			final RepresentativeAttributeDefinition b =
				new RepresentativeAttributeDefinition(Collections.emptyMap());

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should produce consistent hashCode on repeated calls")
		void shouldProduceConsistentHashCodeOnRepeatedCalls() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final int hash1 = rad.hashCode();
			final int hash2 = rad.hashCode();
			final int hash3 = rad.hashCode();
			assertEquals(hash1, hash2);
			assertEquals(hash2, hash3);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTests {

		@Test
		@DisplayName("should format as name=value pairs")
		void shouldFormatAsNameValuePairs() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());
			assertEquals("alpha=A, beta=B", rad.toString());
		}

		@Test
		@DisplayName("should return empty string for empty definition")
		void shouldReturnEmptyStringForEmptyDefinition() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(Collections.emptyMap());
			assertEquals("", rad.toString());
		}

		@Test
		@DisplayName("should format single attribute without trailing comma")
		void shouldFormatSingleAttributeWithoutTrailingComma() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(prepareSingleRepresentativeAttribute());
			assertEquals("only=X", rad.toString());
		}

		@Test
		@DisplayName("should format null default as name=null")
		void shouldFormatNullDefaultAsNameNull() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(prepareAttributesWithNullDefault());
			assertEquals("name=null", rad.toString());
		}
	}

	@Nested
	@DisplayName("Representative values")
	class RepresentativeValues {

		@Test
		@DisplayName("should return default values when reference is null")
		void shouldReturnDefaultValuesWhenReferenceIsNull() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());
			assertArrayEquals(new Serializable[]{"A", "B"}, rad.getRepresentativeValues(null));
		}

		@Test
		@DisplayName("should override defaults with reference values when present")
		void shouldOverrideDefaultsWithReferenceValuesWhenPresent() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final Map<String, AttributeValue> vals = new LinkedHashMap<>();
			vals.put("alpha", new AttributeValue(new AttributeKey("alpha"), "X"));

			final Serializable[] result = rad.getRepresentativeValues(refWithValues(vals));
			assertArrayEquals(new Serializable[]{"X", "B"}, result);
		}

		@Test
		@DisplayName("should not override defaults when attribute value is dropped")
		void shouldNotOverrideDefaultsWhenAttributeValueIsDropped() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final Map<String, AttributeValue> vals = new LinkedHashMap<>();
			vals.put("alpha", new AttributeValue(1, new AttributeKey("alpha"), "X", true));

			final Serializable[] result = rad.getRepresentativeValues(refWithValues(vals));
			assertArrayEquals(new Serializable[]{"A", "B"}, result);
		}

		@Test
		@DisplayName("should ignore non-representative attributes from reference")
		void shouldIgnoreNonRepresentativeAttributesFromReference() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final Map<String, AttributeValue> vals = new LinkedHashMap<>();
			vals.put("gamma", new AttributeValue(new AttributeKey("gamma"), "G2"));
			vals.put("beta", new AttributeValue(new AttributeKey("beta"), "BB"));

			final Serializable[] result = rad.getRepresentativeValues(refWithValues(vals));
			assertArrayEquals(new Serializable[]{"A", "BB"}, result);
		}

		@Test
		@DisplayName("should override all defaults when reference provides all values")
		void shouldOverrideAllDefaultsWhenReferenceProvidesAll() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final Map<String, AttributeValue> vals = new LinkedHashMap<>();
			vals.put("alpha", new AttributeValue(new AttributeKey("alpha"), "AA"));
			vals.put("beta", new AttributeValue(new AttributeKey("beta"), "BB"));

			final Serializable[] result = rad.getRepresentativeValues(refWithValues(vals));
			assertArrayEquals(new Serializable[]{"AA", "BB"}, result);
		}

		@Test
		@DisplayName("should keep all defaults when reference returns empty for all")
		void shouldKeepAllDefaultsWhenReferenceReturnsEmptyForAll() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final ReferenceContract ref = refWithValues(Collections.emptyMap());

			final Serializable[] result = rad.getRepresentativeValues(ref);
			assertArrayEquals(new Serializable[]{"A", "B"}, result);
		}

		@Test
		@DisplayName("should return empty array for empty definition with non-null reference")
		void shouldReturnEmptyArrayForEmptyDefinitionWithReference() {
			final RepresentativeAttributeDefinition rad =
				new RepresentativeAttributeDefinition(Collections.emptyMap());

			final Map<String, AttributeValue> vals = new LinkedHashMap<>();
			vals.put("anything", new AttributeValue(new AttributeKey("anything"), "V"));

			final Serializable[] result = rad.getRepresentativeValues(refWithValues(vals));
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("should return independent array from getRepresentativeValues")
		void shouldReturnIndependentArrayFromGetRepresentativeValues() {
			final RepresentativeAttributeDefinition rad = new RepresentativeAttributeDefinition(prepareAttributes());

			final Serializable[] first = rad.getRepresentativeValues(null);
			first[0] = "MUTATED";

			final Serializable[] second = rad.getRepresentativeValues(null);
			assertArrayEquals(new Serializable[]{"A", "B"}, second,
				"Mutating returned array should not affect subsequent calls");
		}
	}
}
