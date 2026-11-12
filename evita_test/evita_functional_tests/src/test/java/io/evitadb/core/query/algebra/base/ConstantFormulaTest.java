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

package io.evitadb.core.query.algebra.base;

import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.TokenRole;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.algebra.locale.LocaleFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConstantFormula} - the leaf formula that hands its delegate bitmap straight back as the result.
 *
 * The class carries a memoized cardinality, and what makes that memo safe is an invariant nothing else states:
 * {@code estimatedCost} is derived from the very same {@code delegate.size()} and is already frozen at construction
 * by {@code AbstractFormula#initFields}, so the memo introduces no new commitment - it freezes the same number, and
 * freezes it later. The tests below pin exactly that, plus the two properties that make the {@code -1} sentinel
 * unreachable as a legitimate cardinality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ConstantFormula — delegate bitmap leaf")
@Tag(ENGINE)
@Tag(QUERY)
class ConstantFormulaTest {

	@Nested
	@DisplayName("Cardinality memoization")
	class CardinalityMemoization {

		@Test
		@DisplayName("should report the delegate size as the estimated cardinality")
		void shouldReportDelegateSizeAsEstimatedCardinality() {
			assertEquals(5, new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getEstimatedCardinality());
		}

		@Test
		@DisplayName("should report the same cardinality on repeated calls")
		void shouldReportTheSameCardinalityOnRepeatedCalls() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));

			final int first = formula.getEstimatedCardinality();

			assertEquals(first, formula.getEstimatedCardinality());
			assertEquals(first, formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should agree with the cost frozen at construction time")
		void shouldAgreeWithTheCostFrozenAtConstruction() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));

			// this is the invariant the memo rests on: `getEstimatedCostInternal()` returns the same
			// `delegate.size()` and the constructor freezes it, so memoizing the cardinality commits to nothing the
			// instance had not already committed to
			assertEquals(formula.getEstimatedCost(), formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should keep cardinality and cost in step after the memory is cleared")
		void shouldKeepCardinalityAndCostInStepAfterClearMemory() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));
			formula.compute();

			// `clearMemory` drops the computed result and the costs derived from it, but deliberately not
			// `estimatedCost` - leaving the cardinality memo alone is what keeps the pair consistent
			formula.clearMemory();

			assertEquals(formula.getEstimatedCost(), formula.getEstimatedCardinality());
		}

		@Test
		@DisplayName("should not depend on the order cost and cardinality are read in")
		void shouldNotDependOnTheOrderCostAndCardinalityAreRead() {
			final ConstantFormula cardinalityFirst = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));
			final ConstantFormula costFirst = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));

			final int cardinality = cardinalityFirst.getEstimatedCardinality();
			final long cost = costFirst.getEstimatedCost();

			assertEquals(cost, cardinality);
			assertEquals(cardinalityFirst.getEstimatedCost(), costFirst.getEstimatedCardinality());
		}
	}

	@Nested
	@DisplayName("Empty delegate")
	class EmptyDelegate {

		@Test
		@DisplayName("should reject an empty delegate bitmap")
		void shouldRejectAnEmptyDelegate() {
			assertThrows(GenericEvitaInternalError.class, () -> new ConstantFormula(new BaseBitmap()));
		}

		/** Verifies the attesting constructor guards the same nonempty premise as the deriving one. */
		@Test
		@DisplayName("should reject an empty delegate bitmap with a supplied identity")
		void shouldRejectAnEmptyDelegateWithSuppliedIdentity() {
			final IdentityToken token = IdentityToken.derived(1, TokenRole.FILTER_INDEX_ALL_RECORDS);

			assertThrows(GenericEvitaInternalError.class, () -> new ConstantFormula(new BaseBitmap(), token));
		}

		@Test
		@DisplayName("should never report the sentinel value as a cardinality")
		void shouldNeverReportTheSentinelValueAsCardinality() {
			// together with the rejection above this is what makes the `-1` memo sentinel unreachable as a real
			// value: a delegate always holds at least one key, so a duplicated resolution recomputes the identical
			// number rather than mistaking a legitimate cardinality for "not resolved yet"
			assertTrue(new ConstantFormula(new BaseBitmap(7)).getEstimatedCardinality() >= 1);
		}
	}

	@Nested
	@DisplayName("Result and identity")
	class ResultAndIdentity {

		@Test
		@DisplayName("should return the very delegate bitmap instance as the result")
		void shouldReturnTheDelegateBitmapInstance() {
			final BaseBitmap delegate = new BaseBitmap(1, 3, 4, 5, 8);

			assertSame(delegate, new ConstantFormula(delegate).compute());
		}

		@Test
		@DisplayName("should refuse to be cloned with inner formulas")
		void shouldRejectInnerFormulas() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8));

			assertThrows(UnsupportedOperationException.class, formula::getCloneWithInnerFormulas);
		}

		/** Verifies supplied owner identity stays exact even when bitmap content exceeds the content limit. */
		@Test
		@DisplayName("should retain the supplied token and return the attested bitmap")
		void shouldRetainSuppliedIdentityForLargeBitmap() {
			final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
			for (int i = 0; i < values.length; i++) {
				values[i] = i;
			}
			final BaseBitmap bitmap = new BaseBitmap(values);
			final IdentityToken token = IdentityToken.derived(1, TokenRole.FILTER_INDEX_ALL_RECORDS);
			final ConstantFormula formula = new ConstantFormula(bitmap, token);
			assertSame(bitmap, formula.compute());
			assertSame(token, ((ConstantFormula.Identity) formula.identity()).delegateIdentity());
			assertFalse(formula.getKey().isOpaque());
			assertFalse(formula.getKey().isQueryLocal());
			assertEquals(new ConstantFormula(bitmap).getHash(), formula.getHash());
			assertArrayEquals(new ConstantFormula(bitmap).gatherTransactionalIds(), formula.gatherTransactionalIds());
		}

		/** Verifies ordinary bitmap identity is recomputed rather than retained by the formula. */
		@Test
		@DisplayName("should derive ordinary bitmap identity on demand without caching its token")
		void shouldDeriveBitmapIdentityWithoutCachingToken() {
			final ConstantFormula formula = new ConstantFormula(new BaseBitmap(1, 2));
			final IdentityToken first = ((ConstantFormula.Identity) formula.identity()).delegateIdentity();
			final IdentityToken second = ((ConstantFormula.Identity) formula.identity()).delegateIdentity();
			assertEquals(first, second);
			assertNotSame(first, second);
		}

		/** Verifies a plain bitmap contributes detached exact content rather than an object identity. */
		@Test
		@DisplayName("should represent a bounded plain bitmap by copied content")
		void shouldRepresentPlainBitmapByCopiedContent() {
			final ConstantFormula.Identity identity = (ConstantFormula.Identity)
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).identity();
			assertInstanceOf(IdentityToken.Exact.class, identity.delegateIdentity());
			assertEquals(identity,
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).identity());
			assertNotEquals(identity,
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 9)).identity());
		}

		/** Verifies bounded delegate content determines value identity. */
		@Test
		@DisplayName("should key equal contents alike and differing contents apart")
		void shouldKeyByDelegateContents() {
			// A plain bitmap has no producer id, so bounded copied content determines its identity.
			assertEquals(
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getKey(),
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getKey()
			);
			assertNotEquals(
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getKey(),
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 9)).getKey()
			);
		}

		/** Pins the legacy structural hash, which the formula deduplicator still keys formulas on. */
		@Test
		@DisplayName("should give equal contents the same legacy hash and differing contents different ones")
		void shouldHashByDelegateContents() {
			// a plain bitmap has no transactional id, so its contents are the legacy hash's only discriminator
			assertEquals(
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getHash(),
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getHash()
			);
			assertNotEquals(
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 8)).getHash(),
				new ConstantFormula(new BaseBitmap(1, 3, 4, 5, 9)).getHash()
			);
		}

		/** Verifies an attested token keys the delegate even when the bitmap is a producer with an id of its own. */
		@Test
		@DisplayName("should prefer the supplied identity over the producer id of the delegate")
		void shouldPreferSuppliedIdentityOverProducerId() {
			final TransactionalBitmap first = new TransactionalBitmap(new BaseBitmap(1));
			final TransactionalBitmap second = new TransactionalBitmap(new BaseBitmap(1));
			final IdentityToken token = IdentityToken.derived(1, TokenRole.FILTER_INDEX_ALL_RECORDS);

			final FormulaKey attestedFirst = new ConstantFormula(first, token).getKey();
			final FormulaKey attestedSecond = new ConstantFormula(second, token).getKey();
			final FormulaKey derivedFirst = new ConstantFormula(first).getKey();
			final FormulaKey derivedSecond = new ConstantFormula(second).getKey();

			assertEquals(attestedFirst, attestedSecond);
			assertNotEquals(derivedFirst, derivedSecond, "distinct producers must key apart without attestation");
		}

		/** Pins the boundary between bounded value content and instance-bound opaque content. */
		@Test
		@DisplayName("should bind large unowned content to its holder and propagate query locality through children")
		void shouldBindLargeContentToItsHolder() {
			final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
			for (int i = 0; i < values.length; i++) {
				values[i] = i;
			}
			final BaseBitmap bitmap = new BaseBitmap(values);
			final ConstantFormula first = new ConstantFormula(bitmap);
			final ConstantFormula second = new ConstantFormula(bitmap);
			assertTrue(first.getKey().isOpaque());
			assertTrue(first.getKey().isQueryLocal());
			assertSame(first.getKey(), first.getKey());
			assertNotEquals(first.getKey(), second.getKey());
			final AndFormula parent = new AndFormula(first, new ConstantFormula(new BaseBitmap(1)));
			assertFalse(parent.getKey().isOpaque());
			assertTrue(parent.getKey().isQueryLocal());
			assertEquals(parent.getKey(),
				new AndFormula(first, new ConstantFormula(new BaseBitmap(1))).getKey());
		}

		/** Pins the semantic class discriminator used to recognize locale formulas. */
		@Test
		@DisplayName("should distinguish constant, locale, empty and skip formula identities")
		void shouldDistinguishFormulaClasses() {
			final FormulaKey constant = new ConstantFormula(new BaseBitmap(1)).getKey();
			final FormulaKey locale = new LocaleFormula(new BaseBitmap(1)).getKey();
			final FormulaKey differentLocale = new LocaleFormula(new BaseBitmap(2)).getKey();

			assertNotEquals(constant, locale);
			assertNotEquals(locale, differentLocale);
			assertNotEquals(locale.getHash64(), differentLocale.getHash64());
			assertNotEquals(EmptyFormula.INSTANCE.getKey(), SkipFormula.INSTANCE.getKey());
		}
	}

}
