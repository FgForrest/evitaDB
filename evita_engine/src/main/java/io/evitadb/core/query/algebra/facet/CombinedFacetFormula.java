/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.algebra.facet;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;

/**
 * This formula has almost identical implementation as {@link OrFormula} but it accepts only set of
 * {@link Formula} as a child and allows containing even single child (on the contrary to the {@link OrFormula}).
 * The formula envelops the AND joined facet formulas and the OR joined facet formulas - the conjunctive and the
 * disjunctive facet groups of the facet selection of one reference - and computes their union.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CombinedFacetFormula extends AbstractFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} as the identity discriminator.
	 */
	private static final long CLASS_ID = 523840934350100709L;

	public CombinedFacetFormula(@Nonnull Formula andFormula, @Nonnull Formula orFormula) {
		this.initFields(andFormula, orFormula);
	}

	private CombinedFacetFormula(@Nonnull Formula... innerFormulas) {
		this.initFields(innerFormulas);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(
			innerFormulas.length == 2,
			"CombinedFacetFormula requires exactly 2 inner formulas (AND + OR), but got " + innerFormulas.length + "."
		);
		return new CombinedFacetFormula(innerFormulas);
	}

	@Override
	public long getOperationCost() {
		return 11;
	}

	@Override
	public int getEstimatedCardinality() {
		int sum = 0;
		for (Formula innerFormula : this.innerFormulas) {
			sum += innerFormula.getEstimatedCardinality();
		}
		return sum;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Formula[] formulas = getInnerFormulas();
		final PersistentRoaringBitmap[] roaringBitmaps = new PersistentRoaringBitmap[formulas.length];
		for (int i = 0; i < formulas.length; i++) {
			roaringBitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(formulas[i].compute());
		}
		return new BaseBitmap(PersistentRoaringBitmap.or(roaringBitmaps));
	}

	@Override
	public String toString() {
		return "COMBINED AND+OR";
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	/** Captures this formula's own computation inputs. */
	@Nonnull
	@Override
	protected IdentityRecord identity() {
		return new Identity();
	}

	/**
	 * Detached identity of the formula, whose inputs are its children.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity() implements IdentityRecord {
		/** Returns the owning formula identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** The formula has no inputs outside its children. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
		}
	}
}
