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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RepresentativeAttributeDefinition")
class RepresentativeAttributeDefinitionTest {

	@Nonnull
	private static Map<String, AttributeSchema> prepareAttributes() {
		final Map<String, AttributeSchema> attributes = new LinkedHashMap<>();
		// non-representative should be ignored
		attributes.put(
			"gamma",
			AttributeSchema._internalBuild(
				"gamma", null, null, null, false, false, false,
				String.class, "G"
			)
		);
		// representative attributes out of order to test sorting
		attributes.put(
			"beta",
			AttributeSchema._internalBuild(
				"beta", null, null, null, false, false, true,
				String.class, "B"
			)
		);
		attributes.put(
			"alpha",
			AttributeSchema._internalBuild(
				"alpha", null, null, null, false, false, true,
				String.class, "A"
			)
		);
		return attributes;
	}

	@Test
	@DisplayName("should collect and sort representative attributes when constructed")
	void shouldCollectAndSortRepresentativeAttributesWhenConstructed() {
		final RepresentativeAttributeDefinition rad =
			new RepresentativeAttributeDefinition(prepareAttributes());

		final List<String> names = rad.getAttributeNames();
		assertEquals(List.of("alpha", "beta"), names);

		final Serializable[] defaults = rad.getDefaultValues();
		assertArrayEquals(new Serializable[]{"A", "B"}, defaults);
	}

	@Test
	@DisplayName("should return empty index when attribute unknown")
	void shouldReturnEmptyIndexWhenAttributeUnknown() {
		final RepresentativeAttributeDefinition rad =
			new RepresentativeAttributeDefinition(prepareAttributes());

		final OptionalInt idxKnown = rad.getAttributeNameIndex("alpha");
		assertTrue(idxKnown.isPresent());
		assertEquals(0, idxKnown.getAsInt());

		final OptionalInt idxUnknown = rad.getAttributeNameIndex("does_not_exist");
		assertTrue(idxUnknown.isEmpty());
	}

	@Test
	@DisplayName("should provide defensive copy of default values")
	void shouldProvideDefensiveCopyOfDefaultValues() {
		final RepresentativeAttributeDefinition rad =
			new RepresentativeAttributeDefinition(prepareAttributes());

		final Serializable[] first = rad.getDefaultValues();
		final Serializable[] second = rad.getDefaultValues();
		assertNotSame(first, second);
		// the instance stores values internally; make sure the returned array is not the same reference
		assertNotSame(first, rad.defaultValues);

		first[0] = "X";
		assertArrayEquals(new Serializable[]{"A", "B"}, second);
	}

	@Test
	@DisplayName("should implement equals and hashCode when constructed from same attributes")
	void shouldImplementEqualsAndHashCodeWhenConstructedFromSameAttributes() {
		final RepresentativeAttributeDefinition a =
			new RepresentativeAttributeDefinition(prepareAttributes());
		final RepresentativeAttributeDefinition b =
			new RepresentativeAttributeDefinition(prepareAttributes());

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());

		final Map<String, AttributeSchema> different = new LinkedHashMap<>(prepareAttributes());
		// change default value to make it different
		different.put(
			"beta",
			AttributeSchema._internalBuild(
				"beta", null, null, null, false, false, true,
				String.class, "B2"
			)
		);
		final RepresentativeAttributeDefinition c =
			new RepresentativeAttributeDefinition(different);
		assertNotEquals(a, c);
	}

	@Test
	@DisplayName("should format toString when called")
	void shouldFormatToStringWhenCalled() {
		final RepresentativeAttributeDefinition rad =
			new RepresentativeAttributeDefinition(prepareAttributes());

		assertEquals("alpha=A, beta=B", rad.toString());
	}

	@Test
	@DisplayName("should return default values when reference is null")
	void shouldReturnDefaultValuesWhenReferenceIsNull() {
		final RepresentativeAttributeDefinition rad =
			new RepresentativeAttributeDefinition(prepareAttributes());

		final Serializable[] result = rad.getRepresentativeValues(null);
		assertArrayEquals(new Serializable[]{"A", "B"}, result);
	}

	@Test
	@DisplayName("should override defaults with reference values when present")
	void shouldOverrideDefaultsWithReferenceValuesWhenPresent() {
		final RepresentativeAttributeDefinition rad =
			new RepresentativeAttributeDefinition(prepareAttributes());

		final Map<String, AttributeValue> vals = new LinkedHashMap<>();
		vals.put("alpha", new AttributeValue(new AttributeKey("alpha"), "X"));
		final ReferenceContract ref = refWithValues(vals);

		final Serializable[] result = rad.getRepresentativeValues(ref);
		assertArrayEquals(new Serializable[]{"X", "B"}, result);
	}

	@Test
	@DisplayName("should not override defaults when attribute value is dropped")
	void shouldNotOverrideDefaultsWhenAttributeValueIsDropped() {
		final RepresentativeAttributeDefinition rad =
			new RepresentativeAttributeDefinition(prepareAttributes());

		final Map<String, AttributeValue> vals = new LinkedHashMap<>();
		vals.put("alpha", new AttributeValue(1, new AttributeKey("alpha"), "X", true));
		final ReferenceContract ref = refWithValues(vals);

		final Serializable[] result = rad.getRepresentativeValues(ref);
		assertArrayEquals(new Serializable[]{"A", "B"}, result);
	}

	@Test
	@DisplayName("should ignore non-representative attributes when getting values")
	void shouldIgnoreNonRepresentativeAttributesWhenGettingValues() {
		final RepresentativeAttributeDefinition rad =
			new RepresentativeAttributeDefinition(prepareAttributes());

		final Map<String, AttributeValue> vals = new LinkedHashMap<>();
		vals.put("gamma", new AttributeValue(new AttributeKey("gamma"), "G2"));
		vals.put("beta", new AttributeValue(new AttributeKey("beta"), "BB"));
		final ReferenceContract ref = refWithValues(vals);

		final Serializable[] result = rad.getRepresentativeValues(ref);
		assertArrayEquals(new Serializable[]{"A", "BB"}, result);
	}

	@Nonnull
	private static ReferenceContract refWithValues(@Nonnull Map<String, AttributeValue> values) {
		final ReferenceContract ref = Mockito.mock(ReferenceContract.class);
		Mockito.when(ref.getAttributeValue(Mockito.anyString())).thenAnswer(invocation -> {
			final String attributeName = invocation.getArgument(0, String.class);
			final AttributeValue val = values.get(attributeName);
			return Optional.ofNullable(val);
		});
		return ref;
	}
}
