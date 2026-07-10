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

import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * This formula has almost identical implementation as {@link OrFormula} but it accepts only set of
 * {@link Formula} as a children and allows containing even single child (on the contrary to the {@link OrFormula}).
 * The formula envelopes "facet filtering" part of the formula so that it could be easily located during
 * {@link ReferenceSummary} computation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetGroupOrFormula extends AbstractFormula implements FacetGroupFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 2720865649065325701L;

	/**
	 * Contains {@link FacetHaving#getReferenceName()} of the facet that is targeted by this formula.
	 */
	@Getter private final String referenceName;
	/**
	 * Contains requested facet group id that is shared among all {@link #facetIds} of this formula.
	 */
	@Getter private final Integer facetGroupId;
	/**
	 * Contains array of requested facet ids from {@link FacetHaving} filtering query.
	 */
	@Getter private final Bitmap facetIds;
	/**
	 * Contains array of bitmaps that represents the entity primary keys that match {@link #facetIds}.
	 */
	@Getter private final Bitmap[] bitmaps;

	public FacetGroupOrFormula(@Nonnull String referenceName, @Nullable Integer facetGroupId, @Nonnull Bitmap facetIds, @Nonnull Bitmap... bitmaps) {
		this.referenceName = referenceName;
		this.facetGroupId = facetGroupId;
		this.facetIds = facetIds;
		this.bitmaps = bitmaps;
		this.initFields();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 0, "This query doesn't allow inner formulas!");
		return this;
	}

	@Override
	public long getOperationCost() {
		return 11;
	}

	@Nonnull
	@Override
	public FacetGroupFormula mergeWith(@Nonnull FacetGroupFormula anotherFormula) {
		return FacetGroupFormula.mergeWith(
			this, anotherFormula,
			(collectedFacetIds, collectedBitmaps) -> new FacetGroupOrFormula(
				this.referenceName, this.facetGroupId, collectedFacetIds, collectedBitmaps
			)
		);
	}

	@Override
	public String toString() {
		return FacetGroupFormula.toStringRepresentation("OR", this.referenceName, this.facetGroupId, this.facetIds, this.bitmaps);
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return FacetGroupFormula.toStringVerboseRepresentation("OR", this.referenceName, this.facetGroupId, this.facetIds, this.bitmaps);
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		if (this.bitmaps.length == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (this.bitmaps.length == 1) {
			return this.bitmaps[0];
		} else {
			final PersistentRoaringBitmap[] roaringBitmaps = new PersistentRoaringBitmap[this.bitmaps.length];
			for (int i = 0; i < this.bitmaps.length; i++) {
				roaringBitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(this.bitmaps[i]);
			}
			return new BaseBitmap(PersistentRoaringBitmap.or(roaringBitmaps));
		}
	}

	@Override
	protected long getEstimatedBaseCost() {
		long sum = 0L;
		for (Bitmap bitmap : this.bitmaps) {
			sum += bitmap.size();
		}
		return sum;
	}

	@Override
	public int getEstimatedCardinality() {
		int sum = 0;
		for (Bitmap bitmap : this.bitmaps) {
			sum += bitmap.size();
		}
		return sum;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		// count transactional bitmaps for pre-sizing
		int transactionalCount = 0;
		for (Bitmap bitmap : this.bitmaps) {
			if (bitmap instanceof TransactionalBitmap) {
				transactionalCount++;
			}
		}
		// 1 (referenceName hash) + groupId (0 or 1) + facetIds count + transactional bitmap count
		final int groupIdSize = this.facetGroupId == null ? 0 : 1;
		final long[] hashes = new long[1 + groupIdSize + this.facetIds.size() + transactionalCount];
		int idx = 0;
		hashes[idx++] = hashFunction.hashChars(this.referenceName);
		if (this.facetGroupId != null) {
			hashes[idx++] = this.facetGroupId;
		}
		for (int facetId : this.facetIds) {
			hashes[idx++] = facetId;
		}
		// collect transactional bitmap ids into a temporary array for sorting
		final long[] txIds = new long[transactionalCount];
		int txIdx = 0;
		for (Bitmap bitmap : this.bitmaps) {
			if (bitmap instanceof TransactionalBitmap) {
				txIds[txIdx++] = ((TransactionalBitmap) bitmap).getId();
			}
		}
		Arrays.sort(txIds);
		System.arraycopy(txIds, 0, hashes, idx, txIds.length);
		return hashFunction.hashLongs(hashes);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		long sum = 0L;
		for (final Bitmap bitmap : this.bitmaps) {
			sum += bitmap.size();
		}
		return sum * getOperationCost();
	}
}
