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
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;

/**
 * This formula has almost identical implementation as {@link AndFormula} but it accepts only set of
 * {@link Formula} as a children and allows containing even single child (on the contrary to the {@link AndFormula}).
 * The formula envelopes "facet filtering" part of the formula so that it could be easily located during
 * {@link FacetSummary} computation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FacetGroupAndFormula extends AbstractFormula implements FacetGroupFormula {
	private static final long CLASS_ID = 7601098585679511784L;
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

	public FacetGroupAndFormula(@Nonnull String referenceName, @Nullable Integer facetGroupId, @Nonnull Bitmap facetIds, @Nonnull Bitmap... bitmaps) {
		Assert.isPremiseValid(facetIds.size() == bitmaps.length, "Expected one bitmap for each facet.");
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
		return 15;
	}

	@Nonnull
	@Override
	public FacetGroupFormula mergeWith(@Nonnull FacetGroupFormula anotherFormula) {
		return FacetGroupFormula.mergeWith(
			this, anotherFormula,
			(collectedFacetIds, collectedBitmaps) -> new FacetGroupAndFormula(
				this.referenceName, this.facetGroupId, collectedFacetIds, collectedBitmaps
			)
		);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("FACET " + this.referenceName + " AND (" + ofNullable(this.facetGroupId).map(Object::toString).orElse("-") + " - " + this.facetIds.toString() + "): ");
		for (int i = 0; i < this.bitmaps.length; i++) {
			final Bitmap bitmap = this.bitmaps[i];
			sb.append(" ↦ ").append(bitmap.size());
			if (i + 1 < this.facetIds.size()) {
				sb.append(", ");
			}
		}
		return sb.append(" primary keys").toString();
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		final StringBuilder sb = new StringBuilder("FACET " + this.referenceName + " AND (" + ofNullable(this.facetGroupId).map(Object::toString).orElse("-") + " - " + this.facetIds.toString() + "): ");
		for (int i = 0; i < this.bitmaps.length; i++) {
			final Bitmap bitmap = this.bitmaps[i];
			sb.append(" ↦ ").append(bitmap.toString());
			if (i + 1 < this.facetIds.size()) {
				sb.append(", ");
			}
		}
		return sb.toString();
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return RoaringBitmapBackedBitmap.and(
			Arrays.stream(this.bitmaps)
				.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
				.toArray(RoaringBitmap[]::new)
		);
	}

	@Override
	protected long getEstimatedBaseCost() {
		return ofNullable(this.bitmaps)
			.map(it -> Arrays.stream(it).mapToLong(Bitmap::size).sum())
			.orElseGet(super::getEstimatedBaseCost);
	}

	@Override
	public int getEstimatedCardinality() {
		if (this.bitmaps == null) {
			return Arrays.stream(this.innerFormulas).mapToInt(Formula::getEstimatedCardinality).min().orElse(0);
		} else {
			return Arrays.stream(this.bitmaps).mapToInt(Bitmap::size).min().orElse(0);
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			Stream.of(
					LongStream.of(hashFunction.hashChars(this.referenceName)),
					this.facetGroupId == null ? LongStream.empty() : LongStream.of(this.facetGroupId),
					StreamSupport.stream(this.facetIds.spliterator(), false).mapToLong(it -> it),
					Arrays.stream(this.bitmaps)
						.filter(TransactionalBitmap.class::isInstance)
						.mapToLong(it -> ((TransactionalBitmap) it).getId())
						.sorted()
				)
				.flatMapToLong(it -> it)
				.toArray()
		);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long getCostInternal() {
		return ofNullable(this.bitmaps)
			.map(it -> Arrays.stream(it).mapToLong(Bitmap::size).sum())
			.orElseGet(super::getCostInternal);
	}
}
