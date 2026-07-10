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
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;

/**
 * This formula has almost identical implementation as {@link OrFormula} but it accepts only set of
 * {@link Formula} as a child and allows containing even single child (on the contrary to the {@link OrFormula}).
 * The formula envelops both AND joined facet formulas and OR joined facet formulas and allows to distinguish between
 * them and so that newly added formulas for clone can target the proper container in this formula.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CombinedFacetFormula extends AbstractFormula implements NonCacheableFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
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

	public Formula getAndFormula() {
		return getInnerFormulas()[0];
	}

	public Formula getOrFormula() {
		return getInnerFormulas()[1];
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
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
