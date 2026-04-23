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

package io.evitadb.core.query.algebra.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BetweenAttributeFormula} pinning its role as a **tagged subclass of
 * {@link AttributeFormula}** used to mark `attributeBetween(...)` selections with the
 * {@link AttributeRangeCarrierFormula} marker. The subclass must:
 *
 * - behave identically to its parent at runtime (compute, cost delegation, attribute-key routing);
 * - produce a hash distinct from a plain {@link AttributeFormula} built from the same arguments
 *   (so cache entries and dedup tables never conflate the two — the marker is load-bearing);
 * - preserve the {@link AttributeRangeCarrierFormula} marker and the optional requested-bucket predicate
 *   through {@link Formula#getCloneWithInnerFormulas(Formula...)}.
 *
 * These invariants matter because the attribute-histogram baseline relaxer locates carriers **by type**
 * — mis-classifying `BetweenAttributeFormula` as plain `AttributeFormula` would cause `attributeBetween`
 * sliders to contract their own histogram span.
 *
 * @author evitaDB
 */
@DisplayName("BetweenAttributeFormula tagged attribute subclass")
class BetweenAttributeFormulaTest {

	/**
	 * Shared attribute key used across tests. A locale-agnostic key is sufficient — the locale contribution
	 * to hashing is already exercised by {@link AttributeFormula}'s own tests.
	 */
	@Nonnull
	private static final AttributeKey ATTRIBUTE_KEY = new AttributeKey("price");

	/**
	 * Shared inner bitmap with three primary keys. Re-used so tests can assert bitmap identity across
	 * parent/subclass instances and compute calls.
	 */
	@Nonnull
	private static final Bitmap REFERENCE_BITMAP = new ArrayBitmap(10, 20, 30);

	/**
	 * Builds a fresh inner formula around {@link #REFERENCE_BITMAP}. Fresh instances let us test hash
	 * stability without sharing memoised state.
	 *
	 * @return a {@link ConstantFormula} wrapping the shared reference bitmap
	 */
	@Nonnull
	private static Formula newInnerFormula() {
		return new ConstantFormula(REFERENCE_BITMAP);
	}

	@Nested
	@DisplayName("Compute delegation")
	class ComputeTest {

		@Test
		@DisplayName("should return inner bitmap instance identity without copy")
		void shouldReturnInnerBitmapInstanceIdentityWithoutCopy() {
			// the subclass inherits AttributeFormula.computeInternal which forwards to the single inner
			// formula — any divergence would mean the marker changed behaviour, not just classification
			final Formula inner = newInnerFormula();
			final BetweenAttributeFormula formula = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, inner);

			assertSame(inner.compute(), formula.compute());
		}
	}

	@Nested
	@DisplayName("State preservation")
	class StatePreservationTest {

		@Test
		@DisplayName("should expose targetsGlobalAttribute flag for both true and false ctor inputs")
		void shouldExposeTargetsGlobalAttributeFlagUnchanged() {
			// the subclass must not override the getter — callers (histogram, prefetch) rely on the
			// flag to route between the global and entity-scoped index; both polarities are pinned
			final BetweenAttributeFormula global = new BetweenAttributeFormula(true, ATTRIBUTE_KEY, newInnerFormula());
			final BetweenAttributeFormula local = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());

			assertTrue(global.isTargetsGlobalAttribute());
			assertFalse(local.isTargetsGlobalAttribute());
		}

		@Test
		@DisplayName("should expose attribute key unchanged")
		void shouldExposeAttributeKeyUnchanged() {
			final BetweenAttributeFormula formula = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());

			assertSame(ATTRIBUTE_KEY, formula.getAttributeKey());
		}

		@Test
		@DisplayName("should expose requested-bucket predicate unchanged")
		void shouldExposeRequestedBucketPredicateUnchanged() {
			// the predicate flags which histogram bucket the user's current slider falls into — losing it
			// through the subclass would silently mislabel bucket marking on every attribute histogram
			final Predicate<BigDecimal> predicate = value -> value.compareTo(BigDecimal.ONE) > 0;
			final BetweenAttributeFormula formula = new BetweenAttributeFormula(
				false, ATTRIBUTE_KEY, newInnerFormula(), predicate
			);

			assertSame(predicate, formula.getRequestedPredicate());
		}
	}

	@Nested
	@DisplayName("Clone with new inner formulas")
	class CloneTest {

		@Test
		@DisplayName("should re-wrap provided single inner formula as BetweenAttributeFormula")
		void shouldReWrapProvidedSingleInnerFormulaAsBetweenAttributeFormula() {
			// the clone must itself be a BetweenAttributeFormula — returning a plain AttributeFormula
			// would silently drop the attribute-range carrier tag during any formula-tree rewrite
			final BetweenAttributeFormula original = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());
			final Formula replacement = new ConstantFormula(new ArrayBitmap(1, 2));

			final Formula clone = original.getCloneWithInnerFormulas(replacement);

			final BetweenAttributeFormula cloned = assertInstanceOf(BetweenAttributeFormula.class, clone);
			assertNotSame(original, cloned);
			assertEquals(2, cloned.compute().size());
		}

		@Test
		@DisplayName("should preserve AttributeRangeCarrierFormula marker on clone")
		void shouldPreserveAttributeRangeCarrierMarkerOnClone() {
			// marker loss would break histogram baseline relaxation for the sliced `attributeBetween` flow
			final Formula clone = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula())
				.getCloneWithInnerFormulas(new ConstantFormula(new ArrayBitmap(5)));

			assertInstanceOf(AttributeRangeCarrierFormula.class, clone);
		}

		@Test
		@DisplayName("should preserve targetsGlobalAttribute and attribute key on clone")
		void shouldPreserveTargetsGlobalAttributeAndAttributeKeyOnClone() {
			final BetweenAttributeFormula original = new BetweenAttributeFormula(true, ATTRIBUTE_KEY, newInnerFormula());

			final BetweenAttributeFormula clone = assertInstanceOf(
				BetweenAttributeFormula.class,
				original.getCloneWithInnerFormulas(new ConstantFormula(new ArrayBitmap(7)))
			);
			assertTrue(clone.isTargetsGlobalAttribute());
			assertSame(ATTRIBUTE_KEY, clone.getAttributeKey());
		}

		@Test
		@DisplayName("should preserve requested-bucket predicate on clone")
		void shouldPreserveRequestedBucketPredicateOnClone() {
			final Predicate<BigDecimal> predicate = value -> true;
			final BetweenAttributeFormula original = new BetweenAttributeFormula(
				false, ATTRIBUTE_KEY, newInnerFormula(), predicate
			);

			final BetweenAttributeFormula clone = assertInstanceOf(
				BetweenAttributeFormula.class,
				original.getCloneWithInnerFormulas(new ConstantFormula(new ArrayBitmap(9)))
			);
			assertSame(predicate, clone.getRequestedPredicate());
		}

		@Test
		@DisplayName("should throw when zero inner formulas are supplied")
		void shouldThrowWhenZeroInnerFormulasAreSupplied() {
			// "exactly one inner formula" is a hard invariant — Assert.isTrue maps to
			// EvitaInvalidUsageException with a message pinning the constraint
			final BetweenAttributeFormula original = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());

			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				original::getCloneWithInnerFormulas
			);
			assertTrue(
				ex.getMessage().contains("Exactly one"),
				"unexpected message: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("should throw when more than one inner formula is supplied")
		void shouldThrowWhenMoreThanOneInnerFormulaIsSupplied() {
			final BetweenAttributeFormula original = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());
			final Formula a = new ConstantFormula(new ArrayBitmap(1));
			final Formula b = new ConstantFormula(new ArrayBitmap(2));

			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.getCloneWithInnerFormulas(a, b)
			);
			assertTrue(
				ex.getMessage().contains("Exactly one"),
				"unexpected message: " + ex.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Type markers")
	class TypeMarkerTest {

		@Test
		@DisplayName("should be instance of AttributeRangeCarrierFormula carrier marker")
		void shouldBeInstanceOfAttributeRangeCarrierFormula() {
			// pins carrier membership for the relaxer's `carrierType.isInstance(node)` lookup
			final BetweenAttributeFormula formula = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());

			assertInstanceOf(AttributeRangeCarrierFormula.class, formula);
		}

		@Test
		@DisplayName("should be instance of AttributeFormula parent")
		void shouldBeInstanceOfAttributeFormulaParent() {
			// the subclass must remain assignable to its parent so every `AttributeFormula`-based consumer
			// (requirement producer, prefetch planner, histogram translator) treats it uniformly
			final BetweenAttributeFormula formula = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());

			assertInstanceOf(AttributeFormula.class, formula);
		}
	}

	@Nested
	@DisplayName("Hash stability")
	class HashStabilityTest {

		@Test
		@DisplayName("should produce identical hash for two subclass instances around identical children")
		void shouldProduceIdenticalHashForTwoSubclassInstancesAroundIdenticalChildren() {
			// stable hashing is what lets the formula cache recognise equivalent slider-range selections
			final BetweenAttributeFormula a = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());
			final BetweenAttributeFormula b = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());

			assertEquals(a.getHash(), b.getHash());
		}

		@Test
		@DisplayName("should produce different hash when inner formula differs")
		void shouldProduceDifferentHashWhenInnerFormulaDiffers() {
			final BetweenAttributeFormula a = new BetweenAttributeFormula(
				false, ATTRIBUTE_KEY, new ConstantFormula(new ArrayBitmap(1))
			);
			final BetweenAttributeFormula b = new BetweenAttributeFormula(
				false, ATTRIBUTE_KEY, new ConstantFormula(new ArrayBitmap(2))
			);

			assertNotEquals(a.getHash(), b.getHash());
		}

		@Test
		@DisplayName("should produce different hash when attribute key differs")
		void shouldProduceDifferentHashWhenAttributeKeyDiffers() {
			// inherited includeAdditionalHash from AttributeFormula covers the attribute-name contribution;
			// if the subclass ever re-introduces an overriding implementation that drops it, two
			// BetweenAttributeFormula instances targeting *different* attributes would collide in cache
			final BetweenAttributeFormula a = new BetweenAttributeFormula(
				false, new AttributeKey("price"), newInnerFormula()
			);
			final BetweenAttributeFormula b = new BetweenAttributeFormula(
				false, new AttributeKey("weight"), newInnerFormula()
			);

			assertNotEquals(a.getHash(), b.getHash());
		}

		@Test
		@DisplayName("should produce different hash when attribute locale differs")
		void shouldProduceDifferentHashWhenAttributeLocaleDiffers() {
			// locale is part of the AttributeKey hash contribution — pins that the parent's locale-aware
			// hashing still reaches the subclass
			final BetweenAttributeFormula a = new BetweenAttributeFormula(
				false, new AttributeKey("name", Locale.ENGLISH), newInnerFormula()
			);
			final BetweenAttributeFormula b = new BetweenAttributeFormula(
				false, new AttributeKey("name", Locale.GERMAN), newInnerFormula()
			);

			assertNotEquals(a.getHash(), b.getHash());
		}

		@Test
		@DisplayName("should produce hash distinct from plain AttributeFormula around the same subtree")
		void shouldProduceHashDistinctFromPlainAttributeFormulaAroundTheSameSubtree() {
			// this is the central tagging invariant — plain AttributeFormula and BetweenAttributeFormula
			// must not collide; without a distinct class ID, `attributeEquals` and `attributeBetween` would
			// cache-hit each other and the relaxer would see false carriers
			final AttributeFormula plain = new AttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());
			final BetweenAttributeFormula tagged = new BetweenAttributeFormula(false, ATTRIBUTE_KEY, newInnerFormula());

			assertNotEquals(plain.getHash(), tagged.getHash());
		}
	}
}
