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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for {@link PriceBetweenFormula} pinning its role as a **pass-through wrapper** around a single
 * inner price-filter formula. The wrapper must:
 *
 * - return the inner formula's bitmap verbatim from `compute()` (bit-for-bit, no copy);
 * - delegate every cost-model method (`getOperationCost`, `getEstimatedCardinality`, `getEstimatedCost`)
 *   to the inner formula so the planner sees no overhead;
 * - re-wrap correctly through `getCloneWithInnerFormulas(...)` as a `PriceBetweenFormula` (so the
 *   PRICE_HISTOGRAM relaxer's `PriceBetweenFormula.class.isInstance(...)` check keeps matching);
 * - produce a deterministic hash that depends only on the inner formula's hash plus the wrapper's own
 *   class identity (so two wrappers around structurally identical subtrees cache-hit).
 *
 * These invariants matter because the price-histogram baseline relaxer locates price carriers **by type**
 * — any semantic change would either hide the wrapper from the relaxer or alter the planner's cost
 * estimate.
 *
 * @author evitaDB
 */
@DisplayName("PriceBetweenFormula pass-through wrapper")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(PRICE)
class PriceBetweenFormulaTest {

	/**
	 * Shared inner formula producing three entity primary keys. Re-used across tests so we can assert
	 * identity (`assertSame`) against the exact bitmap instance owned by the inner formula.
	 */
	@Nonnull
	private static final Bitmap REFERENCE_BITMAP = new ArrayBitmap(10, 20, 30);

	/**
	 * Builds a fresh inner formula around {@link #REFERENCE_BITMAP}. Each test that asserts hash equality
	 * or independence uses two freshly constructed wrappers so we avoid sharing memoised state.
	 *
	 * @return a {@link ConstantFormula} wrapping the shared reference bitmap
	 */
	@Nonnull
	private static Formula newInnerFormula() {
		return new ConstantFormula(REFERENCE_BITMAP);
	}

	@Nested
	@DisplayName("Compute pass-through")
	class ComputeTest {

		@Test
		@DisplayName("should return inner bitmap instance identity without copy")
		void shouldReturnInnerBitmapInstanceIdentityWithoutCopy() {
			// the wrapper must forward compute() verbatim — any copy would add allocations and break
			// the no-overhead guarantee documented in the class-level comment
			final Formula inner = newInnerFormula();
			final PriceBetweenFormula formula = new PriceBetweenFormula(inner);

			final Bitmap result = formula.compute();

			assertSame(inner.compute(), result);
		}

		@Test
		@DisplayName("should forward EmptyBitmap.INSTANCE verbatim when inner formula is empty")
		void shouldReturnEmptyBitmapWhenInnerFormulaIsEmpty() {
			// empty inner formula should propagate as EmptyBitmap.INSTANCE — confirms the wrapper
			// doesn't accidentally wrap empty into a fresh BaseBitmap, which would break the
			// downstream `bitmap == EmptyBitmap.INSTANCE` fast-path used by AND/OR combinators
			final PriceBetweenFormula formula = new PriceBetweenFormula(EmptyFormula.INSTANCE);

			assertSame(EmptyBitmap.INSTANCE, formula.compute());
		}
	}

	@Nested
	@DisplayName("Cost-model delegation")
	class CostModelDelegationTest {

		@Test
		@DisplayName("should forward operation cost to inner formula")
		void shouldForwardOperationCostToInnerFormula() {
			// wrapper must add zero per-element cost — otherwise inserting it would change plan choice
			final Formula inner = newInnerFormula();
			final PriceBetweenFormula formula = new PriceBetweenFormula(inner);

			assertEquals(inner.getOperationCost(), formula.getOperationCost());
		}

		@Test
		@DisplayName("should forward estimated cardinality to inner formula")
		void shouldForwardEstimatedCardinalityToInnerFormula() {
			// cardinality drives the multiplier in getEstimatedCost — delegating keeps the cost model
			// transparent to the wrapper
			final Formula inner = newInnerFormula();
			final PriceBetweenFormula formula = new PriceBetweenFormula(inner);

			assertEquals(inner.getEstimatedCardinality(), formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should produce estimated cost equal to inner formula's estimated cost")
		void shouldProduceEstimatedCostEqualToInnerFormulasEstimatedCost() {
			// getEstimatedCostInternal must return the child's estimate untouched — no base-cost term
			// and no additional per-wrapper contribution
			final Formula inner = newInnerFormula();
			final PriceBetweenFormula formula = new PriceBetweenFormula(inner);

			assertEquals(inner.getEstimatedCost(), formula.getEstimatedCost());
		}
	}

	@Nested
	@DisplayName("Clone with new inner formulas")
	class CloneTest {

		@Test
		@DisplayName("should re-wrap provided single inner formula")
		void shouldReWrapProvidedSingleInnerFormula() {
			final PriceBetweenFormula original = new PriceBetweenFormula(newInnerFormula());
			final Formula replacement = new ConstantFormula(new ArrayBitmap(1, 2));

			final Formula clone = original.getCloneWithInnerFormulas(replacement);

			final PriceBetweenFormula cloned = assertInstanceOf(PriceBetweenFormula.class, clone);
			assertNotSame(original, cloned);
			// the clone must route compute() through the new inner formula
			assertEquals(2, cloned.compute().size());
		}

		@Test
		@DisplayName("should preserve PriceBetweenFormula type on clone")
		void shouldPreservePriceBetweenFormulaTypeOnClone() {
			// the concrete class is how the price-histogram relaxer finds price carriers — returning a
			// different type on clone would make every refold invisible to the relaxer
			final Formula clone = new PriceBetweenFormula(newInnerFormula())
				.getCloneWithInnerFormulas(new ConstantFormula(new ArrayBitmap(5)));

			assertInstanceOf(PriceBetweenFormula.class, clone);
		}

		@Test
		@DisplayName("should throw when zero inner formulas are supplied")
		void shouldThrowWhenZeroInnerFormulasAreSupplied() {
			// "exactly one inner formula" is a hard invariant — Assert.isTrue maps to
			// EvitaInvalidUsageException; the message identifies the constraint so a failing refold is
			// traceable in logs
			final PriceBetweenFormula original = new PriceBetweenFormula(newInnerFormula());

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
			// same invariant — clone cannot introduce multiple children where the wrapper expects one
			final PriceBetweenFormula original = new PriceBetweenFormula(newInnerFormula());
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
	@DisplayName("Hash stability")
	class HashStabilityTest {

		@Test
		@DisplayName("should produce identical hash for two wrappers around identical children")
		void shouldProduceIdenticalHashForTwoWrappersAroundIdenticalChildren() {
			// stable hashing is what lets the formula cache recognise "two priceBetween wrappers around
			// the same subtree" as equivalent — a drift here silently misses cache hits
			final PriceBetweenFormula a = new PriceBetweenFormula(newInnerFormula());
			final PriceBetweenFormula b = new PriceBetweenFormula(newInnerFormula());

			assertEquals(a.getHash(), b.getHash());
		}

		@Test
		@DisplayName("should produce different hash for wrappers around different children")
		void shouldProduceDifferentHashForWrappersAroundDifferentChildren() {
			// hash must change when the child changes — otherwise sibling price-range picks would collide
			final PriceBetweenFormula a = new PriceBetweenFormula(new ConstantFormula(new ArrayBitmap(1)));
			final PriceBetweenFormula b = new PriceBetweenFormula(new ConstantFormula(new ArrayBitmap(2)));

			assertNotEquals(a.getHash(), b.getHash());
		}

		@Test
		@DisplayName("should produce a hash distinct from the bare inner formula")
		void shouldProduceHashDistinctFromBareInnerFormula() {
			// the wrapper's own class ID must enter the hash — otherwise `priceBetween(x)` and `x` would
			// cache-collide and the relaxer's type-based lookup would see a false carrier
			final Formula inner = newInnerFormula();
			final PriceBetweenFormula wrapper = new PriceBetweenFormula(inner);

			assertNotEquals(inner.getHash(), wrapper.getHash());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should render PRICE BETWEEN as short label identifying the wrapper")
		void shouldRenderPriceBetweenAsShortLabelIdentifyingTheWrapper() {
			// the short toString is used in plan dumps — must be stable and exactly "PRICE BETWEEN"
			final PriceBetweenFormula formula = new PriceBetweenFormula(newInnerFormula());

			assertEquals("PRICE BETWEEN", formula.toString());
		}

		@Test
		@DisplayName("should render verbose label including inner formula verbose string")
		void shouldRenderVerboseLabelIncludingInnerFormulaVerboseString() {
			// toStringVerbose is used by debug tooling — must embed the child's verbose rendering so
			// a plan dump can be read top-to-bottom without descending manually
			final Formula inner = newInnerFormula();
			final PriceBetweenFormula formula = new PriceBetweenFormula(inner);

			final String verbose = formula.toStringVerbose();
			assertNotNull(verbose);
			assertEquals(
				"PRICE BETWEEN (" + inner.toStringVerbose() + ")",
				verbose
			);
		}
	}
}
