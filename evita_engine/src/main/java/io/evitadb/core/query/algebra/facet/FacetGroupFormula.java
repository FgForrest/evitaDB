/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.facet;

import com.esotericsoftware.kryo.util.IntMap;
import com.esotericsoftware.kryo.util.IntMap.Entry;
import io.evitadb.api.query.filter.FacetInSet;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.BiFunction;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.getRoaringBitmap;
import static java.util.Optional.ofNullable;

/**
 * Interface marks all {@link Formula} that resolve facet filtering. This interface allows locating appropriate formulas
 * in the tree when {@link FacetStatisticsDepth#IMPACT} is requested to be computed and original requirements needs to
 * be altered in order to compute alternative searches.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface FacetGroupFormula extends NonCacheableFormula {

	/**
	 * Method merges two {@link FacetGroupFormula} of the same type related to same group id into the one.
	 */
	@Nonnull
	static <T extends FacetGroupFormula> T mergeWith(@Nonnull FacetGroupFormula a, @Nonnull FacetGroupFormula b, @Nonnull BiFunction<int[], Bitmap[], T> factory) {
		Assert.isPremiseValid(
			Objects.equals(a.getFacetGroupId(), b.getFacetGroupId()),
			"Both formulas must share facet group id!"
		);
		Assert.isPremiseValid(
			Objects.equals(a.getReferenceName(), b.getReferenceName()),
			"Both formulas must share facet type!"
		);
		Assert.isPremiseValid(
			a.getClass().equals(b.getClass()),
			"Both formulas must be of type FacetGroupAndFormula!"
		);

		final int[] thisFacetIds = a.getFacetIds();
		final Bitmap[] thisFacetBitmaps = a.getBitmaps();
		final int[] thatFacetIds = b.getFacetIds();
		final Bitmap[] thatFacetBitmaps = b.getBitmaps();

		final IntMap<Bitmap> aggregatedBitmaps = new IntMap<>(thisFacetIds.length + thatFacetIds.length);
		for (int i = 0; i < thisFacetIds.length; i++) {
			int facetId = thisFacetIds[i];
			aggregatedBitmaps.put(facetId, thisFacetBitmaps[i]);
		}

		for (int i = 0; i < thatFacetIds.length; i++) {
			int facetId = thatFacetIds[i];
			final Bitmap bitmap = thatFacetBitmaps[i];
			final Bitmap combinedBitmaps = ofNullable(aggregatedBitmaps.get(facetId))
				.map(it -> (Bitmap) new BaseBitmap(RoaringBitmap.or(getRoaringBitmap(it), getRoaringBitmap(bitmap))))
				.orElse(bitmap);
			aggregatedBitmaps.put(facetId, combinedBitmaps);
		}

		final int[] collectedFacetIds = new int[aggregatedBitmaps.size];
		final Bitmap[] collectedBitmaps = new Bitmap[aggregatedBitmaps.size];
		int index = 0;
		for (Entry<Bitmap> entry : aggregatedBitmaps) {
			collectedFacetIds[index] = entry.key;
			collectedBitmaps[index++] = entry.value;
		}

		return factory.apply(collectedFacetIds, collectedBitmaps);
	}

	/**
	 * Returns {@link FacetInSet#getType()} of the facet that is targeted by this formula.
	 * This information is crucial for correct {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} computation.
	 */
	@Nonnull
	String getReferenceName();

	/**
	 * Returns {@link GroupEntityReference#getPrimaryKey()} shared among all facets in {@link #getFacetIds()}.
	 */
	@Nullable
	Integer getFacetGroupId();

	/**
	 * Returns array of requested facet ids from {@link FacetInSet#getFacetIds()} filtering query.
	 * This information is crucial for correct {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} computation.
	 */
	@Nonnull
	int[] getFacetIds();

	/**
	 * Returns array of bitmaps that match the requested facet ids from {@link #getFacetIds()}.
	 */
	@Nonnull
	Bitmap[] getBitmaps();

	/**
	 * Returns clone of the formula adding new facet to the formula along with `entityIds` that match this facet id.
	 */
	@Nonnull
	FacetGroupFormula getCloneWithFacet(int facetId, @Nonnull Bitmap... entityIds);

	/**
	 * Returns clone of the formula combining all facets from the `anotherFormula` and this formula.
	 * Both formulas must be of same type and must target same facet group id.
	 */
	@Nonnull
	FacetGroupFormula mergeWith(@Nonnull FacetGroupFormula anotherFormula);

}
