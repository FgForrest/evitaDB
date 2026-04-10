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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RepresentativeReferenceKey} verifying construction, delegation, equality,
 * hashing, natural ordering, generic comparator, and string formatting.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("RepresentativeReferenceKey")
class RepresentativeReferenceKeyTest {

	/**
	 * Creates a {@link RepresentativeReferenceKey} from an unknown (IPK=0)
	 * {@link ReferenceKey} and the given attribute values.
	 *
	 * @param name  the reference name
	 * @param pk    the primary key
	 * @param attrs the representative attribute values
	 * @return a new instance
	 */
	@Nonnull
	private static RepresentativeReferenceKey rrk(
		@Nonnull String name, int pk, @Nonnull Serializable... attrs
	) {
		final ReferenceKey rk = new ReferenceKey(name, pk);
		return new RepresentativeReferenceKey(rk, attrs);
	}

	/**
	 * Creates a {@link RepresentativeReferenceKey} from a {@link ReferenceKey} with explicit
	 * internal primary key and the given attribute values.
	 *
	 * @param name       the reference name
	 * @param pk         the primary key
	 * @param internalPk the internal primary key
	 * @param attrs      the representative attribute values
	 * @return a new instance
	 */
	@Nonnull
	private static RepresentativeReferenceKey rrkWithInternalPk(
		@Nonnull String name, int pk, int internalPk, @Nonnull Serializable... attrs
	) {
		final ReferenceKey rk = new ReferenceKey(name, pk, internalPk);
		return new RepresentativeReferenceKey(rk, attrs);
	}

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should normalize non-unknown reference by stripping internal primary key")
		void shouldNormalizeNonUnknownReferenceByStrippingInternalPrimaryKey() {
			final ReferenceKey original = new ReferenceKey("ref", 5, 42);
			final RepresentativeReferenceKey key = new RepresentativeReferenceKey(original);

			// constructor should replace with IPK=0
			final ReferenceKey stored = key.referenceKey();
			assertEquals("ref", stored.referenceName());
			assertEquals(5, stored.primaryKey());
			assertEquals(0, stored.internalPrimaryKey(), "Non-unknown IPK should be normalized to 0");
			assertNotSame(original, stored, "A new ReferenceKey instance should be created");
		}

		@Test
		@DisplayName("should preserve unknown reference as-is")
		void shouldPreserveUnknownReferenceAsIs() {
			// IPK=0 means unknown, kept as-is
			final ReferenceKey unknown = new ReferenceKey("ref", 5);
			final RepresentativeReferenceKey key = new RepresentativeReferenceKey(unknown);

			assertSame(unknown, key.referenceKey(), "Unknown reference should be preserved");
		}

		@Test
		@DisplayName("should normalize new reference with negative internal primary key")
		void shouldNormalizeNewReferenceWithNegativeInternalPk() {
			final ReferenceKey newRef = new ReferenceKey("ref", 5, -3);
			final RepresentativeReferenceKey key = new RepresentativeReferenceKey(newRef);

			final ReferenceKey stored = key.referenceKey();
			assertEquals(0, stored.internalPrimaryKey(), "Negative IPK should be normalized to 0");
		}

		@Test
		@DisplayName("should use empty attribute array with single-arg constructor")
		void shouldCreateEmptyAttributeArrayWithSingleArgConstructor() {
			final ReferenceKey rk = new ReferenceKey("ref", 1);
			final RepresentativeReferenceKey key = new RepresentativeReferenceKey(rk);

			assertSame(
				ArrayUtils.EMPTY_SERIALIZABLE_ARRAY,
				key.representativeAttributeValues(),
				"Single-arg constructor should use shared empty array"
			);
		}

		@Test
		@DisplayName("should preserve provided attribute values")
		void shouldPreserveProvidedAttributeValues() {
			final RepresentativeReferenceKey key = rrk("ref", 1, "alpha", 42);

			assertArrayEquals(new Serializable[]{"alpha", 42}, key.representativeAttributeValues());
		}
	}

	@Nested
	@DisplayName("Delegation")
	class Delegation {

		@Test
		@DisplayName("should delegate reference name")
		void shouldDelegateReferenceNameToReferenceKey() {
			final RepresentativeReferenceKey key = rrk("brand", 7);

			assertEquals("brand", key.referenceName());
		}

		@Test
		@DisplayName("should delegate primary key")
		void shouldDelegatePrimaryKeyToReferenceKey() {
			final RepresentativeReferenceKey key = rrk("brand", 7);

			assertEquals(7, key.primaryKey());
		}
	}

	@Nested
	@DisplayName("withPrimaryKey")
	class WithPrimaryKey {

		@Test
		@DisplayName("should create new instance with substituted primary key")
		void shouldCreateNewInstanceWithSubstitutedPrimaryKey() {
			final RepresentativeReferenceKey original = rrk("brand", 5, "x");
			final RepresentativeReferenceKey derived = original.withPrimaryKey(99);

			assertEquals("brand", derived.referenceName());
			assertEquals(99, derived.primaryKey());
			assertNotSame(original, derived);
		}

		@Test
		@DisplayName("should preserve attribute values after primary key substitution")
		void shouldPreserveAttributeValuesAfterPrimaryKeySubstitution() {
			final RepresentativeReferenceKey original = rrk("brand", 5, "a", "b");
			final RepresentativeReferenceKey derived = original.withPrimaryKey(10);

			assertArrayEquals(new Serializable[]{"a", "b"}, derived.representativeAttributeValues());
		}

		@Test
		@DisplayName("should share attribute array instance with original")
		void shouldShareAttributeArrayInstanceWithOriginal() {
			final RepresentativeReferenceKey original = rrk("brand", 5, "x", "y");
			final RepresentativeReferenceKey derived = original.withPrimaryKey(10);

			// source passes the array reference directly
			assertSame(
				original.representativeAttributeValues(),
				derived.representativeAttributeValues(),
				"Array instance should be shared, not copied"
			);
		}

		@Test
		@DisplayName("should not be equal to original when primary key differs")
		void shouldNotBeEqualToOriginalWhenPrimaryKeyDiffers() {
			final RepresentativeReferenceKey original = rrk("brand", 5, "x");
			final RepresentativeReferenceKey derived = original.withPrimaryKey(99);

			assertNotEquals(original, derived);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal when same name, pk and attributes")
		void shouldBeEqualWhenSameNamePkAndAttributes() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x", 2);
			final RepresentativeReferenceKey b = rrk("ref", 1, "x", 2);

			assertEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when attributes differ")
		void shouldNotBeEqualWhenAttributesDiffer() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x");
			final RepresentativeReferenceKey b = rrk("ref", 1, "y");

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when reference names differ")
		void shouldNotBeEqualWhenReferenceNamesDiffer() {
			final RepresentativeReferenceKey a = rrk("alpha", 1, "x");
			final RepresentativeReferenceKey b = rrk("beta", 1, "x");

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when primary keys differ")
		void shouldNotBeEqualWhenPrimaryKeysDiffer() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x");
			final RepresentativeReferenceKey b = rrk("ref", 2, "x");

			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x");

			assertEquals(a, a);
		}

		@Test
		@DisplayName("should be symmetric")
		void shouldBeSymmetric() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x");
			final RepresentativeReferenceKey b = rrk("ref", 1, "x");

			assertEquals(a, b);
			assertEquals(b, a);
		}

		@Test
		@DisplayName("should be transitive")
		void shouldBeTransitive() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x");
			final RepresentativeReferenceKey b = rrk("ref", 1, "x");
			final RepresentativeReferenceKey c = rrk("ref", 1, "x");

			assertEquals(a, b);
			assertEquals(b, c);
			assertEquals(a, c);
		}

		@Test
		@DisplayName("should return false when compared to null")
		void shouldReturnFalseWhenComparedToNull() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x");

			assertNotEquals(null, a);
		}

		@Test
		@DisplayName("should return false when compared to different type")
		void shouldReturnFalseWhenComparedToDifferentType() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x");

			//noinspection AssertBetweenInconvertibleTypes
			assertFalse(a.equals("string"));
		}

		@Test
		@DisplayName("should be equal when internal primary keys differ but name and pk match")
		void shouldBeEqualWhenInternalPrimaryKeysDifferButNameAndPkMatch() {
			// equals uses equalsInGeneral which ignores IPK
			final RepresentativeReferenceKey a = rrkWithInternalPk("ref", 5, 10, "x");
			final RepresentativeReferenceKey b = rrkWithInternalPk("ref", 5, 99, "x");

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode(), "Equal objects must have same hash code");
		}

		@Test
		@DisplayName("should produce consistent hash code on repeated calls")
		void shouldProduceConsistentHashCodeOnRepeatedCalls() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x", 42);

			final int hash1 = a.hashCode();
			final int hash2 = a.hashCode();

			assertEquals(hash1, hash2);
		}

		@Test
		@DisplayName("should produce same hash code for equal objects")
		void shouldProduceSameHashCodeForEqualObjects() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x", 42);
			final RepresentativeReferenceKey b = rrk("ref", 1, "x", 42);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should be equal with empty attribute arrays")
		void shouldBeEqualWithEmptyAttributeArrays() {
			final RepresentativeReferenceKey a = rrk("ref", 1);
			final RepresentativeReferenceKey b = rrk("ref", 1);

			assertEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal when attribute array lengths differ")
		void shouldNotBeEqualWhenAttributeArrayLengthsDiffer() {
			final RepresentativeReferenceKey a = rrk("ref", 1, "x");
			final RepresentativeReferenceKey b = rrk("ref", 1, "x", "y");

			assertNotEquals(a, b);
		}
	}

	@Nested
	@DisplayName("Natural ordering (compareTo)")
	class NaturalOrdering {

		@Test
		@DisplayName("should order by reference name")
		void shouldOrderByReferenceNameWhenDifferent() {
			final RepresentativeReferenceKey a = rrk("A", 1);
			final RepresentativeReferenceKey b = rrk("B", 1);

			final int result = a.compareTo(b);

			assertTrue(result < 0, "Expected 'A' to be before 'B'");
		}

		@Test
		@DisplayName("should order by primary key when reference name equal")
		void shouldOrderByPrimaryKeyWhenReferenceNameEqual() {
			final RepresentativeReferenceKey a = rrk("A", 1);
			final RepresentativeReferenceKey b = rrk("A", 2);

			final int result = a.compareTo(b);

			assertTrue(result < 0, "Expected PK 1 before PK 2 when names equal");
		}

		@Test
		@DisplayName("should order by representative attributes when name and pk equal")
		void shouldOrderByRepresentativeAttributesWhenNameAndPkEqual() {
			final RepresentativeReferenceKey first = rrk("A", 1, 1, "x");
			final RepresentativeReferenceKey higherFirstAttr = rrk("A", 1, 2, "x");
			final RepresentativeReferenceKey higherSecondAttr = rrk("A", 1, 1, "y");

			final int c1 = first.compareTo(higherFirstAttr);
			assertTrue(c1 < 0, "Expected lower first attr to come first");

			final int c2 = first.compareTo(higherSecondAttr);
			assertTrue(c2 < 0, "Expected lower second attr to come first");
		}

		@Test
		@DisplayName("should return zero when all components equal")
		void shouldReturnZeroWhenAllComponentsEqual() {
			final RepresentativeReferenceKey a = rrk("A", 1, 1, "x");
			final RepresentativeReferenceKey b = rrk("A", 1, 1, "x");

			final int result = a.compareTo(b);

			assertEquals(0, result, "Equal keys should compare as zero");
		}

		@Test
		@DisplayName("should throw exception when attribute lengths differ")
		void shouldThrowExceptionWhenRepresentativeAttributeLengthsDiffer() {
			final RepresentativeReferenceKey a = rrk("A", 1, 1);
			final RepresentativeReferenceKey b = rrk("A", 1, 1, 2);

			assertThrows(GenericEvitaInternalError.class, () -> a.compareTo(b));
		}

		@Test
		@DisplayName("should return zero when both have empty attributes")
		void shouldReturnZeroWhenBothHaveEmptyAttributes() {
			final RepresentativeReferenceKey a = rrk("A", 1);
			final RepresentativeReferenceKey b = rrk("A", 1);

			final int result = a.compareTo(b);

			assertEquals(0, result, "Both empty attribute arrays should compare as zero");
		}

		@Test
		@DisplayName("should handle null attribute value by sorting it before non-null")
		void shouldHandleNullAttributeValueBySortingBeforeNonNull() {
			final RepresentativeReferenceKey withNull = rrk("A", 1, new Serializable[]{null});
			final RepresentativeReferenceKey withValue = rrk("A", 1, "x");

			// null should sort before non-null without throwing
			final int result = withNull.compareTo(withValue);

			assertTrue(result < 0, "Null attribute should sort before non-null attribute");
		}

		@Test
		@DisplayName("should return zero when both attributes are null")
		void shouldReturnZeroWhenBothAttributesAreNull() {
			final RepresentativeReferenceKey a = rrk("A", 1, new Serializable[]{null});
			final RepresentativeReferenceKey b = rrk("A", 1, new Serializable[]{null});

			final int result = a.compareTo(b);

			assertEquals(0, result, "Both null attributes should compare as zero");
		}

		@Test
		@DisplayName("should sort non-null after null when non-null compared to null")
		void shouldSortNonNullAfterNull() {
			final RepresentativeReferenceKey withValue = rrk("A", 1, "x");
			final RepresentativeReferenceKey withNull = rrk("A", 1, new Serializable[]{null});

			final int result = withValue.compareTo(withNull);

			assertTrue(result > 0, "Non-null attribute should sort after null attribute");
		}
	}

	@Nested
	@DisplayName("GENERIC_COMPARATOR")
	class GenericComparatorTests {

		@Test
		@DisplayName("should order by reference name, then pk, then attributes")
		void shouldOrderByReferenceNameThenPkThenAttributes() {
			final RepresentativeReferenceKey a = rrk("A", 1, "x");
			final RepresentativeReferenceKey b = rrk("B", 1, "x");

			final int result = RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(a, b);

			assertTrue(result < 0, "Expected 'A' before 'B'");
		}

		@Test
		@DisplayName("should tolerate different attribute array lengths")
		void shouldTolerateDifferentAttributeArrayLengths() {
			// shorter < longer when prefix matches
			final RepresentativeReferenceKey shorter = rrk("A", 1, "a");
			final RepresentativeReferenceKey longer = rrk("A", 1, "a", "b");

			final int result = RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(shorter, longer);

			assertTrue(result < 0, "Shorter array should come before longer when prefix matches");
		}

		@Test
		@DisplayName("should return zero for equal keys with same attributes")
		void shouldReturnZeroForEqualKeysWithSameAttributes() {
			final RepresentativeReferenceKey a = rrk("A", 1, "x", "y");
			final RepresentativeReferenceKey b = rrk("A", 1, "x", "y");

			final int result = RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(a, b);

			assertEquals(0, result);
		}

		@Test
		@DisplayName("should compare by attribute value before array length")
		void shouldCompareByAttributeValueBeforeArrayLength() {
			// "z" > "a" regardless of array length
			final RepresentativeReferenceKey oneHighAttr = rrk("A", 1, "z");
			final RepresentativeReferenceKey twoLowAttrs = rrk("A", 1, "a", "b");

			final int result =
				RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(oneHighAttr, twoLowAttrs);

			assertTrue(result > 0, "Higher attribute value should win over shorter array");
		}

		@Test
		@DisplayName("should handle empty vs non-empty attribute arrays")
		void shouldHandleEmptyVsNonEmptyAttributeArrays() {
			final RepresentativeReferenceKey empty = rrk("A", 1);
			final RepresentativeReferenceKey nonEmpty = rrk("A", 1, "x");

			final int result = RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(empty, nonEmpty);

			assertTrue(result < 0, "Empty array should come before non-empty");
		}

		@Test
		@DisplayName("should handle both empty attribute arrays")
		void shouldHandleBothEmptyAttributeArrays() {
			final RepresentativeReferenceKey a = rrk("A", 1);
			final RepresentativeReferenceKey b = rrk("A", 1);

			final int result = RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(a, b);

			assertEquals(0, result);
		}

		@Test
		@DisplayName("should handle null attribute values without throwing")
		void shouldHandleNullAttributeValuesWithoutThrowing() {
			final RepresentativeReferenceKey withNull = rrk("A", 1, new Serializable[]{null});
			final RepresentativeReferenceKey withValue = rrk("A", 1, "x");

			final int result =
				RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(withNull, withValue);

			assertTrue(result < 0, "Null attribute should sort before non-null in generic comparator");
		}

		@Test
		@DisplayName("should be usable for sorting heterogeneous array lengths")
		void shouldBeUsableForSortingHeterogeneousArrayLengths() {
			final RepresentativeReferenceKey[] keys = {
				rrk("B", 2, "y"),
				rrk("A", 1, "a", "b"),
				rrk("A", 1, "a"),
				rrk("A", 2),
				rrk("A", 1)
			};

			Arrays.sort(keys, RepresentativeReferenceKey.GENERIC_COMPARATOR);

			assertEquals("A", keys[0].referenceName());
			assertEquals(1, keys[0].primaryKey());
			assertEquals(0, keys[0].representativeAttributeValues().length,
				"Empty attributes should be first for A/1");

			assertEquals("A", keys[1].referenceName());
			assertEquals(1, keys[1].primaryKey());
			assertArrayEquals(new Serializable[]{"a"}, keys[1].representativeAttributeValues());

			assertEquals("A", keys[2].referenceName());
			assertEquals(1, keys[2].primaryKey());
			assertArrayEquals(new Serializable[]{"a", "b"}, keys[2].representativeAttributeValues());

			assertEquals("A", keys[3].referenceName());
			assertEquals(2, keys[3].primaryKey());

			assertEquals("B", keys[4].referenceName());
			assertEquals(2, keys[4].primaryKey());
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringFormat {

		@Test
		@DisplayName("should format with attribute values")
		void shouldFormatWithAttributeValues() {
			final RepresentativeReferenceKey key = rrk("brand", 5, "alpha", 42);

			// ReferenceKey(IPK=0) → "brand: 5 (generic)"
			assertEquals("brand: 5 (generic): [alpha, 42]", key.toString());
		}

		@Test
		@DisplayName("should format with empty attribute array")
		void shouldFormatWithEmptyAttributeArray() {
			final RepresentativeReferenceKey key = rrk("brand", 5);

			assertEquals("brand: 5 (generic): []", key.toString());
		}

		@Test
		@DisplayName("should format with single attribute")
		void shouldFormatWithSingleAttribute() {
			final RepresentativeReferenceKey key = rrk("brand", 5, "only");

			assertEquals("brand: 5 (generic): [only]", key.toString());
		}
	}
}
