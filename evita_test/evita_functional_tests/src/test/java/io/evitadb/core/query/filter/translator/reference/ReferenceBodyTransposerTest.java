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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.reference.IndexTaggedFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins when {@link ReferenceBodyTransposer#transpose(Formula, Supplier)} returns a `referenceHaving` body as it
 * stands instead of rebuilding it one index at a time.
 *
 * The rebuild walks the whole body once per index while the body holds one contribution per index, so its cost is
 * quadratic in the size of the index family. Skipping it for a body whose contributions meet only under `or` is
 * therefore what keeps a single attribute leaf cheap on a reference with a six-figure partition count - and every
 * attribute leaf arrives wrapped in an {@link AttributeFormula}, so the skip has to see through that wrapper or it
 * never applies to one at all.
 *
 * Which path was taken is read off the shape of the result: the fast path keeps the wrapper at the root around the
 * union, while the rebuild unions one wrapper per index. The row-scoped answers are asserted alongside, because a
 * shape alone could hide a wrong result.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferenceBodyTransposer - fast path through an attribute wrapper")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferenceBodyTransposerTest {
	/**
	 * Stands in for the index supplier on bodies without a negation, where the transposer must never consult it.
	 */
	private static final Supplier<List<EntityIndex>> NO_INDEXES_EXPECTED = () -> {
		throw new AssertionError("The index supplier must not be consulted for a body without a negation!");
	};

	@DisplayName("Should return a wrapped union of per-index leaves as it stands")
	@Test
	void shouldReturnAWrappedUnionOfPerIndexLeavesAsItStands() {
		final int indexCount = 64;
		final Formula[] leaves = new Formula[indexCount];
		final int[] expected = new int[indexCount];
		for (int i = 0; i < indexCount; i++) {
			leaves[i] = new IndexTaggedFormula(i + 1, constant(i + 1));
			expected[i] = i + 1;
		}
		final Formula body = wrap(new OrFormula(leaves));

		final Formula result = ReferenceBodyTransposer.transpose(body, NO_INDEXES_EXPECTED);

		// the rebuild would have produced an `or` of 64 wrappers - the wrapper at the root is the fast path
		final AttributeFormula wrapper = assertInstanceOf(AttributeFormula.class, result);
		final OrFormula union = assertInstanceOf(OrFormula.class, wrapper.getInnerFormulas()[0]);
		assertEquals(indexCount, union.getInnerFormulas().length);
		for (Formula child : union.getInnerFormulas()) {
			assertFalse(child instanceof IndexTaggedFormula, "The tags must not survive the transpose!");
		}
		assertArrayEquals(expected, result.compute().getArray());
	}

	@DisplayName("Should keep the histogram predicate of a wrapper it returns as it stands")
	@Test
	void shouldKeepTheHistogramPredicateOfAWrapperItReturnsAsItStands() {
		final Predicate<BigDecimal> requestedPredicate = value -> value.signum() > 0;
		final Formula body = new AttributeFormula(
			false, new AttributeKey("a"),
			new OrFormula(new IndexTaggedFormula(1, constant(1)), new IndexTaggedFormula(2, constant(2))),
			requestedPredicate
		);

		final Formula result = ReferenceBodyTransposer.transpose(body, NO_INDEXES_EXPECTED);

		assertSame(requestedPredicate, assertInstanceOf(AttributeFormula.class, result).getRequestedPredicate());
	}

	@DisplayName("Should still rebuild a conjunction held under the wrapper")
	@Test
	void shouldStillRebuildAConjunctionHeldUnderTheWrapper() {
		// index 1 holds a row matching both sides (record 2); index 2 matches the first side with record 3 and the
		// second with record 1 - two different rows, so only record 2 answers the row-scoped question, while the
		// union read across indexes would answer {1, 2}
		final Formula body = wrap(
			new AndFormula(
				new OrFormula(new IndexTaggedFormula(1, constant(1, 2)), new IndexTaggedFormula(2, constant(3))),
				new OrFormula(new IndexTaggedFormula(1, constant(2)), new IndexTaggedFormula(2, constant(1)))
			)
		);

		assertArrayEquals(
			new int[]{2}, ReferenceBodyTransposer.transpose(body, NO_INDEXES_EXPECTED).compute().getArray()
		);
	}

	@DisplayName("Should still rebuild a conjunction of two wrapped unions")
	@Test
	void shouldStillRebuildAConjunctionOfTwoWrappedUnions() {
		final Formula body = new AndFormula(
			wrap(new OrFormula(new IndexTaggedFormula(1, constant(1, 2)), new IndexTaggedFormula(2, constant(3)))),
			wrap(new OrFormula(new IndexTaggedFormula(1, constant(2)), new IndexTaggedFormula(2, constant(1))))
		);

		final Formula result = ReferenceBodyTransposer.transpose(body, NO_INDEXES_EXPECTED);

		assertFalse(result instanceof AndFormula, "A conjunction across indexes must be rebuilt per index!");
		assertArrayEquals(new int[]{2}, result.compute().getArray());
	}

	/**
	 * Wraps the formula the way every attribute translator does.
	 *
	 * @param formula the formula to wrap
	 * @return the wrapper
	 */
	@Nonnull
	private static AttributeFormula wrap(@Nonnull Formula formula) {
		return new AttributeFormula(false, new AttributeKey("a"), formula);
	}

	/**
	 * Creates a constant formula over the passed record ids.
	 *
	 * @param values record ids in ascending order
	 * @return the constant formula
	 */
	@Nonnull
	private static ConstantFormula constant(@Nonnull int... values) {
		return new ConstantFormula(new ArrayBitmap(new CompositeIntArray(values)));
	}

}
