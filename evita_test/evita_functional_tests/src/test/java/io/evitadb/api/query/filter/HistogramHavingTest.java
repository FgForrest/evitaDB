/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.histogramHaving;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HistogramHaving} pinning its public API contract — the four factory arities, the
 * normalisation of an empty `histogramName` to null, the bound-validation invariants (at-least-one-bound
 * rule, ordered-bounds-when-same-type rule), the `EntityHaving`-only child whitelist enforced via
 * `getCopyWithNewChildren`, and the standard applicability / necessity / equality / toString contract
 * inherited from `AbstractFilterConstraintContainer`.
 *
 * Each test is written to break when the corresponding invariant is weakened — weakening any assert
 * here would hide a regression that impacts how `userFilter(histogramHaving(...))` is parsed, validated
 * or cloned during EvitaQL evaluation.
 *
 * @author evitaDB
 */
@DisplayName("HistogramHaving constraint")
class HistogramHavingTest {

	/**
	 * Builds a fully qualified `entityHaving(attributeEquals("code", value))` selector used as the
	 * optional group-selector child. Centralising construction keeps the value of `code` consistent and
	 * lets tests assert by equality without repeating the nested builder.
	 *
	 * @param code the `code` attribute value that selects a single group entity
	 * @return a single-child `entityHaving` filter constraint
	 */
	@Nonnull
	private static FilterConstraint groupSelector(@Nonnull String code) {
		return entityHaving(attributeEquals("code", code));
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should build via 3-arg factory with classifier and bounds only")
		void shouldBuildVia3ArgFactoryWithClassifierAndBoundsOnly() {
			final HistogramHaving constraint = histogramHaving("price-range", 50, 120);

			assertEquals("price-range", constraint.getReferenceName());
			assertNull(constraint.getHistogramName());
			assertEquals(50, (Integer) constraint.getFrom());
			assertEquals(120, (Integer) constraint.getTo());
			assertNull(constraint.getGroupSelector());
			assertEquals(0, constraint.getChildren().length);
		}

		@Test
		@DisplayName("should build via 4-arg factory with classifier, histogram name and bounds")
		void shouldBuildVia4ArgFactoryWithClassifierHistogramNameAndBounds() {
			final HistogramHaving constraint = histogramHaving(
				"parameterValues", "basicUnitValue", 50, 120
			);

			assertEquals("parameterValues", constraint.getReferenceName());
			assertEquals("basicUnitValue", constraint.getHistogramName());
			assertEquals(50, (Integer) constraint.getFrom());
			assertEquals(120, (Integer) constraint.getTo());
			assertNull(constraint.getGroupSelector());
		}

		@Test
		@DisplayName("should build via 4-arg factory with classifier, bounds and group selector")
		void shouldBuildVia4ArgFactoryWithClassifierBoundsAndGroupSelector() {
			final FilterConstraint selector = groupSelector("height");
			final HistogramHaving constraint = histogramHaving(
				"parameterValues", 50, 120, selector
			);

			assertEquals("parameterValues", constraint.getReferenceName());
			assertNull(constraint.getHistogramName());
			assertEquals(50, (Integer) constraint.getFrom());
			assertEquals(120, (Integer) constraint.getTo());
			assertSame(selector, constraint.getGroupSelector());
			assertEquals(1, constraint.getChildren().length);
		}

		@Test
		@DisplayName("should build via 5-arg factory with all fields populated")
		void shouldBuildVia5ArgFactoryWithAllFieldsPopulated() {
			final FilterConstraint selector = groupSelector("weight");
			final HistogramHaving constraint = histogramHaving(
				"parameterValues", "basicUnitValue", 50, 120, selector
			);

			assertEquals("parameterValues", constraint.getReferenceName());
			assertEquals("basicUnitValue", constraint.getHistogramName());
			assertEquals(50, (Integer) constraint.getFrom());
			assertEquals(120, (Integer) constraint.getTo());
			assertSame(selector, constraint.getGroupSelector());
		}

		@Test
		@DisplayName("should return null when reference name is null across all factory arities")
		void shouldReturnNullWhenReferenceNameIsNullAcrossAllFactoryArities() {
			// factory null-guard is what lets call sites pass through conditional filter assembly;
			// exercise all four arities with a concrete bound type so overload resolution is unambiguous
			assertNull(histogramHaving(null, 50, 120));
			assertNull(histogramHaving(null, "name", 50, 120));
			assertNull(histogramHaving(null, 50, 120, groupSelector("height")));
			assertNull(histogramHaving(null, "name", 50, 120, groupSelector("height")));
		}

		@Test
		@DisplayName("should return null from 3-arg factory when both bounds are null")
		void shouldReturnNullFrom3ArgFactoryWhenBothBoundsAreNull() {
			// Integer cast forces the 3-arg overload without ambiguity with the 4-arg variants
			assertNull(histogramHaving("price-range", (Integer) null, null));
		}

		@Test
		@DisplayName("should return null from 4-arg factory with histogramName when both bounds are null")
		void shouldReturnNullFrom4ArgFactoryWithHistogramNameWhenBothBoundsAreNull() {
			// cast arg-3 to Integer — matches the (String,String,T,T) overload uniquely
			assertNull(histogramHaving("price-range", "name", (Integer) null, (Integer) null));
		}

		@Test
		@DisplayName("should return null from 4-arg factory with groupSelector when both bounds are null")
		void shouldReturnNullFrom4ArgFactoryWithGroupSelectorWhenBothBoundsAreNull() {
			// cast bound types to Integer — matches the (String,T,T,FilterConstraint) overload uniquely
			assertNull(histogramHaving(
				"price-range", (Integer) null, (Integer) null, groupSelector("height")
			));
		}

		@Test
		@DisplayName("should return null from 5-arg factory when both bounds are null")
		void shouldReturnNullFrom5ArgFactoryWhenBothBoundsAreNull() {
			// cast bound types to Integer — matches the full-arity (String,String,T,T,FilterConstraint)
			assertNull(histogramHaving(
				"price-range", "name", (Integer) null, (Integer) null, groupSelector("height")
			));
		}

		@Test
		@DisplayName("should build with only the lower bound set")
		void shouldBuildWithOnlyTheLowerBoundSet() {
			final HistogramHaving constraint = histogramHaving("price-range", 50, null);

			assertEquals(50, (Integer) constraint.getFrom());
			assertNull(constraint.getTo());
			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should build with only the upper bound set")
		void shouldBuildWithOnlyTheUpperBoundSet() {
			final HistogramHaving constraint = histogramHaving("price-range", null, 120);

			assertNull(constraint.getFrom());
			assertEquals(120, (Integer) constraint.getTo());
			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should allow null group selector without rejecting construction")
		void shouldAllowNullGroupSelectorWithoutRejectingConstruction() {
			final HistogramHaving constraint = new HistogramHaving(
				"parameterValues", "basicUnitValue", 50, 120, null
			);

			// null group selector is the non-grouped slot — children must be empty, not a single-null slot
			assertNull(constraint.getGroupSelector());
			assertEquals(0, constraint.getChildren().length);
		}

	}

	@Nested
	@DisplayName("Histogram-name normalisation")
	class HistogramNameNormalisationTest {

		@Test
		@DisplayName("should normalise empty histogram name to null")
		void shouldNormaliseEmptyHistogramNameToNull() {
			// empty string must be normalised so downstream consumers only ever see null-or-populated
			final HistogramHaving viaFactory = histogramHaving("parameterValues", "", 50, 120);
			final HistogramHaving viaConstructor = new HistogramHaving(
				"parameterValues", "", 50, 120, null
			);

			assertNull(viaFactory.getHistogramName());
			assertNull(viaConstructor.getHistogramName());
		}

		@Test
		@DisplayName("should keep non-empty histogram name verbatim")
		void shouldKeepNonEmptyHistogramNameVerbatim() {
			// non-empty is load-bearing; only "" is treated as "not supplied"
			final HistogramHaving constraint = new HistogramHaving(
				"parameterValues", "basicUnitValue", 50, 120, null
			);

			assertEquals("basicUnitValue", constraint.getHistogramName());
		}
	}

	@Nested
	@DisplayName("Bound validation")
	class BoundValidationTest {

		@Test
		@DisplayName("should throw when both bounds are null via constructor")
		void shouldThrowWhenBothBoundsAreNullViaConstructor() {
			// constructor bypasses the factory null-guard — the validator must still reject (from,to)=(null,null)
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new HistogramHaving("price-range", null, null, null, null)
			);
			assertTrue(
				ex.getMessage().contains("at least one of"),
				"unexpected message: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("should throw when from > to for same Comparable type")
		void shouldThrowWhenFromGreaterThanToForSameComparableType() {
			// inverted range would silently yield an empty result set — reject early so users see the typo
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new HistogramHaving("price-range", null, 120, 50, null)
			);
			assertTrue(
				ex.getMessage().contains("less than or equal"),
				"unexpected message: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("should allow from equal to to for same Comparable type")
		void shouldAllowFromEqualToToForSameComparableType() {
			// equal bounds are the degenerate-but-valid single-point slider — must be accepted
			final HistogramHaving constraint = new HistogramHaving(
				"price-range", null, 42, 42, null
			);

			assertEquals(42, (Integer) constraint.getFrom());
			assertEquals(42, (Integer) constraint.getTo());
		}

		@Test
		@DisplayName("should throw when from and to differ in plain type")
		void shouldThrowWhenFromAndToDifferInPlainType() {
			// mixed plain types are always a user mistake — a `from` of type Long paired with a `to` of type
			// BigDecimal (or Integer paired with String) cannot be ordered against each other and always
			// produces a runtime surprise downstream. Reject at construction with an actionable message that
			// names both simple class names so the user can locate the offending pair in their query.
			final EvitaInvalidUsageException mixedNumeric = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new HistogramHaving("price-range", null, 10L, java.math.BigDecimal.valueOf(5), null)
			);
			assertTrue(
				mixedNumeric.getMessage().contains("same plain type"),
				"unexpected message: " + mixedNumeric.getMessage()
			);
			assertTrue(
				mixedNumeric.getMessage().contains("Long") && mixedNumeric.getMessage().contains("BigDecimal"),
				"exception should name both mismatched simple class names: " + mixedNumeric.getMessage()
			);

			// coverage for the Integer / String pair too — any cross-type pair must fall through the same
			// rejection path so the validator never silently lets a type mismatch slip past construction
			final EvitaInvalidUsageException mixedIntString = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new HistogramHaving("price-range", null, 50, "120", null)
			);
			assertTrue(
				mixedIntString.getMessage().contains("same plain type"),
				"unexpected message: " + mixedIntString.getMessage()
			);
			assertTrue(
				mixedIntString.getMessage().contains("Integer") && mixedIntString.getMessage().contains("String"),
				"exception should name both mismatched simple class names: " + mixedIntString.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when at least the lower bound is set")
		void shouldBeApplicableWhenAtLeastTheLowerBoundIsSet() {
			assertTrue(histogramHaving("price-range", 50, null).isApplicable());
		}

		@Test
		@DisplayName("should be applicable when at least the upper bound is set")
		void shouldBeApplicableWhenAtLeastTheUpperBoundIsSet() {
			assertTrue(histogramHaving("price-range", null, 120).isApplicable());
		}

		@Test
		@DisplayName("should be applicable when both bounds are set")
		void shouldBeApplicableWhenBothBoundsAreSet() {
			assertTrue(histogramHaving("price-range", 50, 120).isApplicable());
		}

		@Test
		@DisplayName("should report necessity consistently with applicability")
		void shouldReportNecessityConsistentlyWithApplicability() {
			// necessity delegates to applicability — the two must move together or the planner breaks
			final HistogramHaving populated = histogramHaving("price-range", 50, 120);
			assertEquals(populated.isApplicable(), populated.isNecessary());
		}

		@Test
		@DisplayName("should be applicable with histogramName and only one bound set")
		void shouldBeApplicableWithHistogramNameAndOnlyOneBoundSet() {
			// applicability depends on from/to only — supplying histogramName must not flip the flag;
			// cast null to Integer to uniquely pick the (String,String,T,T) overload
			assertTrue(histogramHaving("parameterValues", "basicUnitValue", 50, (Integer) null).isApplicable());
			assertTrue(histogramHaving("parameterValues", "basicUnitValue", null, 120).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with groupSelector and only one bound set")
		void shouldBeApplicableWithGroupSelectorAndOnlyOneBoundSet() {
			// applicability depends on from/to only — supplying groupSelector must not flip the flag;
			// cast null to Integer to uniquely pick the (String,T,T,FilterConstraint) overload
			assertTrue(histogramHaving("parameterValues", 50, null, groupSelector("height")).isApplicable());
			assertTrue(histogramHaving("parameterValues", (Integer) null, 120, groupSelector("height")).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with full arity and only one bound set")
		void shouldBeApplicableWithFullArityAndOnlyOneBoundSet() {
			// full-arity factory with histogramName + groupSelector must still honour the single-bound rule
			assertTrue(histogramHaving(
				"parameterValues", "basicUnitValue", 50, null, groupSelector("height")
			).isApplicable());
			assertTrue(histogramHaving(
				"parameterValues", "basicUnitValue", null, 120, groupSelector("height")
			).isApplicable());
		}
	}

	@Nested
	@DisplayName("Copy with new children")
	class CopyWithNewChildrenTest {

		@Test
		@DisplayName("should re-wrap when children length is zero")
		void shouldReWrapWhenChildrenLengthIsZero() {
			// zero children is the "drop the group selector" path used by the cloner
			final HistogramHaving original = histogramHaving(
				"parameterValues", "basicUnitValue", 50, 120, groupSelector("height")
			);

			final FilterConstraint copy = original.getCopyWithNewChildren(
				FilterConstraint.EMPTY_ARRAY, new Constraint<?>[0]
			);

			final HistogramHaving copied = assertInstanceOf(HistogramHaving.class, copy);
			assertNull(copied.getGroupSelector());
			assertEquals("parameterValues", copied.getReferenceName());
			assertEquals("basicUnitValue", copied.getHistogramName());
		}

		@Test
		@DisplayName("should re-wrap with provided single child preserving arguments")
		void shouldReWrapWithProvidedSingleChildPreservingArguments() {
			final HistogramHaving original = histogramHaving(
				"parameterValues", "basicUnitValue", 50, 120, groupSelector("height")
			);
			final FilterConstraint newSelector = groupSelector("weight");

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[] { newSelector }, new Constraint<?>[0]
			);

			final HistogramHaving copied = assertInstanceOf(HistogramHaving.class, copy);
			assertSame(newSelector, copied.getGroupSelector());
			// arguments must be carried verbatim — arguments array is reused, not rebuilt
			assertEquals("parameterValues", copied.getReferenceName());
			assertEquals("basicUnitValue", copied.getHistogramName());
			assertEquals(50, (Integer) copied.getFrom());
			assertEquals(120, (Integer) copied.getTo());
		}

		@Test
		@DisplayName("should throw when more than one child is supplied")
		void shouldThrowWhenMoreThanOneChildIsSupplied() {
			// @Child declares maxCount=1 semantically; premise assertion enforces it at clone time
			final HistogramHaving original = histogramHaving(
				"parameterValues", 50, 120, groupSelector("height")
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[] {
						groupSelector("height"),
						groupSelector("weight")
					},
					new Constraint<?>[0]
				)
			);
		}

		@Test
		@DisplayName("should throw when additional children array is non-empty")
		void shouldThrowWhenAdditionalChildrenArrayIsNonEmpty() {
			// HistogramHaving accepts no order/require siblings — additional children are a structural bug
			final HistogramHaving original = histogramHaving("price-range", 50, 120);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCopyWithNewChildren(
					FilterConstraint.EMPTY_ARRAY,
					new Constraint<?>[] { entityPrimaryKeyInSet(1) }
				)
			);
		}

		@Test
		@DisplayName("should accept a non-EntityHaving child structurally without failing at clone time")
		void shouldAcceptNonEntityHavingChildStructurallyWithoutFailingAtCloneTime() {
			// structural clone does not re-enforce the @Child(allowed=...) whitelist — that is the query
			// validator's job; this test pins the boundary between structural and semantic validation
			final HistogramHaving original = histogramHaving("price-range", 50, 120);

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[] { entityPrimaryKeyInSet(1) },
				new Constraint<?>[0]
			);

			final HistogramHaving copied = assertInstanceOf(HistogramHaving.class, copy);
			assertEquals(1, copied.getChildren().length);
		}
	}

	@Nested
	@DisplayName("Clone with new arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should clone preserving children when arguments change")
		void shouldClonePreservingChildrenWhenArgumentsChange() {
			final FilterConstraint selector = groupSelector("height");
			final HistogramHaving original = histogramHaving(
				"parameterValues", "basicUnitValue", 50, 120, selector
			);

			final FilterConstraint cloned = original.cloneWithArguments(
				new Serializable[] { "otherReference", "otherHistogram", 10, 20 }
			);

			final HistogramHaving copy = assertInstanceOf(HistogramHaving.class, cloned);
			assertNotSame(original, copy);
			assertEquals("otherReference", copy.getReferenceName());
			assertEquals("otherHistogram", copy.getHistogramName());
			assertEquals(10, (Integer) copy.getFrom());
			assertEquals(20, (Integer) copy.getTo());
			// children (the group selector) must pass through untouched — arguments and children are disjoint
			assertSame(selector, copy.getGroupSelector());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should equal instance with identical arguments and children")
		void shouldEqualInstanceWithIdenticalArgumentsAndChildren() {
			final HistogramHaving a = histogramHaving(
				"parameterValues", "basicUnitValue", 50, 120, groupSelector("height")
			);
			final HistogramHaving b = histogramHaving(
				"parameterValues", "basicUnitValue", 50, 120, groupSelector("height")
			);

			assertNotSame(a, b);
			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not equal instance with different reference name")
		void shouldNotEqualInstanceWithDifferentReferenceName() {
			assertNotEquals(
				histogramHaving("alpha", 50, 120),
				histogramHaving("beta", 50, 120)
			);
		}

		@Test
		@DisplayName("should not equal instance with different histogram name")
		void shouldNotEqualInstanceWithDifferentHistogramName() {
			assertNotEquals(
				histogramHaving("parameterValues", "basicUnitValue", 50, 120),
				histogramHaving("parameterValues", "physicalUnitValue", 50, 120)
			);
		}

		@Test
		@DisplayName("should not equal instance with different from bound")
		void shouldNotEqualInstanceWithDifferentFromBound() {
			assertNotEquals(
				histogramHaving("price-range", 50, 120),
				histogramHaving("price-range", 60, 120)
			);
		}

		@Test
		@DisplayName("should not equal instance with different to bound")
		void shouldNotEqualInstanceWithDifferentToBound() {
			assertNotEquals(
				histogramHaving("price-range", 50, 120),
				histogramHaving("price-range", 50, 130)
			);
		}

		@Test
		@DisplayName("should not equal instance with different group selector")
		void shouldNotEqualInstanceWithDifferentGroupSelector() {
			assertNotEquals(
				histogramHaving("parameterValues", 50, 120, groupSelector("height")),
				histogramHaving("parameterValues", 50, 120, groupSelector("weight"))
			);
		}

		@Test
		@DisplayName("should not equal instance with vs. without group selector")
		void shouldNotEqualInstanceWithVsWithoutGroupSelector() {
			// group-selector presence changes the targeted slot — must be part of identity
			assertNotEquals(
				histogramHaving("parameterValues", 50, 120, groupSelector("height")),
				histogramHaving("parameterValues", 50, 120)
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format with reference, histogram name and bounds")
		void shouldFormatWithReferenceHistogramNameAndBounds() {
			final String text = histogramHaving(
				"parameterValues", "basicUnitValue", 50, 120
			).toString();

			// toString is the EvitaQL round-trip — pin the exact literal so any format drift (quoting,
			// comma spacing, ordering, missing argument) fails immediately instead of passing on the
			// weaker substring-contains check
			assertEquals("histogramHaving('parameterValues','basicUnitValue',50,120)", text);
		}

		@Test
		@DisplayName("should include group selector in string when present")
		void shouldIncludeGroupSelectorInStringWhenPresent() {
			final String text = histogramHaving(
				"parameterValues", 50, 120, groupSelector("height")
			).toString();

			// group selector must be rendered as a child inside the constraint payload — pin the exact
			// nested entityHaving(attributeEquals(...)) shape so a structural drift surfaces here;
			// when the histogramName is omitted the 3-arg factory leaves that slot null and the
			// generic argument-renderer emits the `<NULL>` placeholder, which must also stay stable
			assertEquals(
				"histogramHaving('parameterValues',<NULL>,50,120,entityHaving(attributeEquals('code','height')))",
				text
			);
		}
	}
}
