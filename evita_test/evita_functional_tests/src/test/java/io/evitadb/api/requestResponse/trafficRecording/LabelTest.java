/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.trafficRecording;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link Label} record.
 *
 * @author Claude
 */
@DisplayName("Label")
class LabelTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should create label with name and value")
		void shouldCreateLabelWithNameAndValue() {
			final Label label = new Label("key", "value");
			assertEquals("key", label.name());
			assertEquals("value", label.value());
		}

		@Test
		@DisplayName("should create label with null value")
		void shouldCreateLabelWithNullValue() {
			final Label label = new Label("key", null);
			assertEquals("key", label.name());
			assertNull(label.value());
		}

		@Test
		@DisplayName("should have EMPTY_LABELS constant as empty array")
		void shouldHaveEmptyLabelsConstant() {
			assertNotNull(Label.EMPTY_LABELS);
			assertEquals(0, Label.EMPTY_LABELS.length);
		}

		@Test
		@DisplayName("should create label with integer value")
		void shouldCreateLabelWithIntegerValue() {
			final Label label = new Label("count", 42);
			assertEquals("count", label.name());
			assertEquals(42, label.value());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final Label label = new Label("key", "value");
			assertEquals(label, label);
		}

		@Test
		@DisplayName("should be symmetric")
		void shouldBeSymmetric() {
			final Label label1 = new Label("key", "value");
			final Label label2 = new Label("key", "value");
			assertEquals(label1, label2);
			assertEquals(label2, label1);
		}

		@Test
		@DisplayName("should handle null safely")
		void shouldHandleNullSafely() {
			final Label label = new Label("key", "value");
			assertNotEquals(null, label);
		}

		@Test
		@DisplayName("should not be equal for different names")
		void shouldNotBeEqualForDifferentNames() {
			final Label label1 = new Label("key1", "value");
			final Label label2 = new Label("key2", "value");
			assertNotEquals(label1, label2);
		}

		@Test
		@DisplayName("should not be equal for different values")
		void shouldNotBeEqualForDifferentValues() {
			final Label label1 = new Label("key", "value1");
			final Label label2 = new Label("key", "value2");
			assertNotEquals(label1, label2);
		}

		@Test
		@DisplayName("should be equal when both values are null")
		void shouldBeEqualWhenBothValuesAreNull() {
			final Label label1 = new Label("key", null);
			final Label label2 = new Label("key", null);
			assertEquals(label1, label2);
		}

		@Test
		@DisplayName("should not be equal when one value is null")
		void shouldNotBeEqualWhenOneValueIsNull() {
			final Label label1 = new Label("key", "value");
			final Label label2 = new Label("key", null);
			assertNotEquals(label1, label2);
			assertNotEquals(label2, label1);
		}

		@Test
		@DisplayName("should have consistent hashCode for equal labels")
		void shouldHaveConsistentHashCodeForEqualLabels() {
			final Label label1 = new Label("key", "value");
			final Label label2 = new Label("key", "value");
			assertEquals(label1.hashCode(), label2.hashCode());
		}

		@Test
		@DisplayName("should have consistent hashCode for null values")
		void shouldHaveConsistentHashCodeForNullValues() {
			final Label label1 = new Label("key", null);
			final Label label2 = new Label("key", null);
			assertEquals(label1.hashCode(), label2.hashCode());
		}

		@Test
		@DisplayName("should not be equal to non-Label object")
		void shouldNotBeEqualToNonLabelObject() {
			final Label label = new Label("key", "value");
			assertNotEquals("not a label", label);
		}
	}

	@Nested
	@DisplayName("Comparable")
	class ComparableTests {

		@Test
		@DisplayName("should compare by name first")
		void shouldCompareByNameFirst() {
			final Label labelA = new Label("alpha", "value");
			final Label labelB = new Label("beta", "value");
			assertTrue(labelA.compareTo(labelB) < 0);
			assertTrue(labelB.compareTo(labelA) > 0);
		}

		@Test
		@DisplayName("should compare by Comparable value when names are equal")
		void shouldCompareByComparableValueWhenNamesAreEqual() {
			final Label label1 = new Label("key", "aaa");
			final Label label2 = new Label("key", "bbb");
			assertTrue(label1.compareTo(label2) < 0);
			assertTrue(label2.compareTo(label1) > 0);
		}

		@Test
		@DisplayName("should compare by Comparable integer value")
		void shouldCompareByComparableIntegerValue() {
			final Label label1 = new Label("key", 1);
			final Label label2 = new Label("key", 2);
			assertTrue(label1.compareTo(label2) < 0);
		}

		@Test
		@DisplayName("should fallback to formatValue for incompatible types")
		void shouldFallbackToFormatValueForIncompatibleTypes() {
			final Label label1 = new Label("key", "text");
			final Label label2 = new Label("key", 42);
			// should not throw, just compare formatted values
			final int result = label1.compareTo(label2);
			assertNotEquals(0, result);
		}

		@Test
		@DisplayName("should sort non-null before null value")
		void shouldSortNonNullBeforeNullValue() {
			final Label labelWithValue = new Label("key", "value");
			final Label labelWithNull = new Label("key", null);
			assertTrue(labelWithValue.compareTo(labelWithNull) > 0);
			assertTrue(labelWithNull.compareTo(labelWithValue) < 0);
		}

		@Test
		@DisplayName("should be equal when both values are null")
		void shouldBeEqualWhenBothValuesAreNull() {
			final Label label1 = new Label("key", null);
			final Label label2 = new Label("key", null);
			assertEquals(0, label1.compareTo(label2));
		}

		@Test
		@DisplayName("should be anti-symmetric")
		void shouldBeAntiSymmetric() {
			final Label label1 = new Label("a", "x");
			final Label label2 = new Label("b", "y");
			assertTrue(label1.compareTo(label2) < 0);
			assertTrue(label2.compareTo(label1) > 0);
		}

		@Test
		@DisplayName("should be transitive")
		void shouldBeTransitive() {
			final Label labelA = new Label("a", null);
			final Label labelB = new Label("b", null);
			final Label labelC = new Label("c", null);
			assertTrue(labelA.compareTo(labelB) < 0);
			assertTrue(labelB.compareTo(labelC) < 0);
			assertTrue(labelA.compareTo(labelC) < 0);
		}

		@Test
		@DisplayName("should work with Arrays.sort")
		void shouldWorkWithArraysSort() {
			final Label[] labels = {
				new Label("gamma", "3"),
				new Label("alpha", "1"),
				new Label("beta", "2")
			};
			Arrays.sort(labels);
			assertEquals("alpha", labels[0].name());
			assertEquals("beta", labels[1].name());
			assertEquals("gamma", labels[2].name());
		}

		@Test
		@DisplayName("should return zero for equal labels")
		void shouldReturnZeroForEqualLabels() {
			final Label label1 = new Label("key", "value");
			final Label label2 = new Label("key", "value");
			assertEquals(0, label1.compareTo(label2));
		}
	}
}
