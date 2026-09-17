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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Records which entity index produced the formula it wraps, so that a body evaluated across a whole index family
 * can afterwards be rebuilt one index at a time.
 *
 * Inside a `referenceHaving` body every leaf is evaluated once per reduced entity index and the results are ORed
 * together, which loses track of which row each contribution came from. That is what lets sibling constraints
 * combine across *different* rows of the same owner. Rebuilding the tree per index restores the row scope, but the
 * rebuild needs to know which child belongs to which index - and by the time it runs, position no longer says:
 * empty children are dropped, single children are collapsed, and nested `OrFormula`s are flattened. The tag
 * survives all of that because it travels with the child rather than beside it.
 *
 * **The tag is an internal artifact of the reference body.** It is attached while the body is translated and
 * removed again by the rebuild, so no tagged node is ever handed to the wider formula tree. Computation is a
 * straight delegation, so a tagged formula that somehow escaped would still compute the right answer - but its
 * presence downstream would mean the rebuild missed a branch.
 *
 * The tag also participates in the formula hash, which is not optional: two per-index formulas that differ only
 * in a captured value are indistinguishable to the hash function, and the engine would silently treat them as one
 * computation and drop all but the first.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class IndexTaggedFormula extends AbstractFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 7_620_431_885_902_117_403L;
	/**
	 * Error message thrown when {@link #getCloneWithInnerFormulas(Formula...)} receives more than one inner formula.
	 */
	private static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * {@link io.evitadb.index.EntityIndex#getPrimaryKey()} of the index that produced the wrapped formula.
	 */
	@Getter private final int indexPrimaryKey;

	public IndexTaggedFormula(int indexPrimaryKey, @Nonnull Formula delegate) {
		this.indexPrimaryKey = indexPrimaryKey;
		this.initFields(delegate);
	}

	/**
	 * Returns the formula this tag wraps.
	 *
	 * @return the wrapped formula
	 */
	@Nonnull
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new IndexTaggedFormula(this.indexPrimaryKey, innerFormulas[0]);
	}

	@Override
	public int getEstimatedCardinality() {
		return this.innerFormulas[0].getEstimatedCardinality();
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public String toString() {
		return "TAGGED BY INDEX " + this.indexPrimaryKey;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return this.indexPrimaryKey;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return this.innerFormulas[0].compute();
	}

}
