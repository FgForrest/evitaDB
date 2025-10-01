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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.filter.translator.reference.EntityHavingTranslator;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.IntFunction;

/**
 * This formula is specific to {@link EntityHavingTranslator} allowing to lazy translate matching reference entity
 * primary keys to the primary keys of entity the query targets. This couldn't be computed immediately because we would
 * have to pay the price for computation of the matching reference entity primary keys in order to be able to derive
 * entity primary keys we look for.
 *
 * This formula is similar to {@link DeferredFormula} in the sense that it postpones the heavy-weight computations from
 * query preparation / analysis to the evaluation phase.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceOwnerTranslatingFormula extends AbstractFormula implements ChildrenDependentFormula {
	private static final long CLASS_ID = 6841111737856593641L;
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * Contains the transactional id of the {@link GlobalEntityIndex} of the referenced entity. Because we need to be
	 * able to declare {@link #includeAdditionalHash(LongHashFunction)} before the {@link #computeInternal()} happens
	 * we cannot use only the relevant transactional ids because we just don't know which will be relevant until we
	 * compute the result of the delegate formula. That's why we use "catch-all" transactional id that changes everytime
	 * anything in target entity type index changes.
	 */
	private final long referencedEntityTypeTransactionalId;
	/**
	 * Contains the information about referenced entity type cardinality. The consideration is the same as for
	 * {@link #referencedEntityTypeTransactionalId} - we have to provide the {@link Formula#getEstimatedCardinality()} before
	 * the real calculation occurs, so we have to consider the worst possible cardinality here.
	 */
	private final int worstCardinality;
	/**
	 * The function that lazily computes the relevant primary keys, for each found referenced entity primary key.
	 * The input is referenced entity primary key, output is a bitmap of all entity primary keys that refer to it.
	 */
	private final IntFunction<Bitmap> primaryKeyExpander;

	ReferenceOwnerTranslatingFormula(
		long referencedEntityTypeTransactionalId,
		int worstCardinality,
		@Nonnull Formula innerFormula,
		@Nonnull IntFunction<Bitmap> primaryKeyExpander
	) {
		this.primaryKeyExpander = primaryKeyExpander;
		this.referencedEntityTypeTransactionalId = referencedEntityTypeTransactionalId;
		this.worstCardinality = worstCardinality;
		this.initFields(innerFormula);
	}

	public ReferenceOwnerTranslatingFormula(
		@Nonnull GlobalEntityIndex referencedEntityGlobalIndex,
		@Nonnull Formula innerFormula,
		@Nonnull IntFunction<Bitmap> primaryKeyExpander
	) {
		this.primaryKeyExpander = primaryKeyExpander;
		this.referencedEntityTypeTransactionalId = referencedEntityGlobalIndex.getId();
		this.worstCardinality = referencedEntityGlobalIndex.getSize();
		this.initFields(innerFormula);
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return this.referencedEntityTypeTransactionalId;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap referencedEntityIds = this.innerFormulas[0].compute();
		final int cnt = referencedEntityIds.size();
		if (cnt == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (cnt == 1) {
			return this.primaryKeyExpander.apply(referencedEntityIds.getFirst());
		} else {
			final RoaringBitmap[] theBitmaps = new RoaringBitmap[cnt];
			final OfInt it = referencedEntityIds.iterator();
			for (int i = 0; i < cnt; i++) {
				theBitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(
					this.primaryKeyExpander.apply(it.next())
				);
			}
			return new BaseBitmap(RoaringBitmap.or(theBitmaps));
		}
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new ReferenceOwnerTranslatingFormula(
			this.referencedEntityTypeTransactionalId, this.worstCardinality, innerFormulas[0], this.primaryKeyExpander
		);
	}

	@Override
	public int getEstimatedCardinality() {
		return this.worstCardinality;
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public String toString() {
		return "TRANSLATE TO ENTITY PRIMARY KEYS";
	}

}
