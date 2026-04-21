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
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.function.Predicate;

/**
 * Tagged {@link AttributeFormula} subclass emitted by `AttributeBetweenTranslator` whenever it translates an
 * `attributeBetween(...)` constraint into a filter index lookup. The subclass behaves identically to its parent
 * at runtime — all algebraic methods (`compute()`, `getOperationCost()`, hashing, cloning) are inherited — and
 * exists solely to tag these formulas with {@link AttributeRangeCarrierFormula}.
 *
 * The tag is consumed by the histogram baseline relaxer: when an attribute-family histogram computes its own
 * `[min, max]` span over the user's current selection, the relaxer peels every {@link AttributeRangeCarrierFormula}
 * carrier inside `userFilter` — including instances of this class — so the user's current `attributeBetween` slider
 * pick does not contract the span of that same slider. Plain `AttributeFormula` produced by `attributeEquals`,
 * `attributeInSet`, etc. stays untagged and is never peeled.
 *
 * Clones made via {@link #getCloneWithInnerFormulas(Formula...)} preserve the tagged subclass so the
 * {@link AttributeRangeCarrierFormula} marker propagates through any formula-tree rewriting.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class BetweenAttributeFormula extends AttributeFormula implements AttributeRangeCarrierFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 1781480529956715061L;

	/**
	 * Creates a new {@link BetweenAttributeFormula} without a requested-bucket predicate.
	 *
	 * @param targetsGlobalAttribute whether the attribute targets the global attribute schema / index
	 * @param attributeKey           the key of the attribute being filtered
	 * @param innerFormula           the inner filter-index formula produced by the attribute filter
	 */
	public BetweenAttributeFormula(
		boolean targetsGlobalAttribute,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Formula innerFormula
	) {
		super(targetsGlobalAttribute, attributeKey, innerFormula);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	/**
	 * Creates a new {@link BetweenAttributeFormula} with an optional requested-bucket predicate. The predicate is
	 * used by attribute histograms to flag which bucket the user's current slider pick falls into.
	 *
	 * @param targetsGlobalAttribute whether the attribute targets the global attribute schema / index
	 * @param attributeKey           the key of the attribute being filtered
	 * @param innerFormula           the inner filter-index formula produced by the attribute filter
	 * @param requestedPredicate     optional predicate marking the bucket(s) that match the user's current range
	 */
	public BetweenAttributeFormula(
		boolean targetsGlobalAttribute,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Formula innerFormula,
		@Nullable Predicate<BigDecimal> requestedPredicate
	) {
		super(targetsGlobalAttribute, attributeKey, innerFormula, requestedPredicate);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new BetweenAttributeFormula(
			isTargetsGlobalAttribute(),
			getAttributeKey(),
			innerFormulas[0],
			getRequestedPredicate()
		);
	}
}
