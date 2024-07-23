/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.PrimitiveIterator.OfInt;
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
	static <T extends FacetGroupFormula> T mergeWith(@Nonnull FacetGroupFormula a, @Nonnull FacetGroupFormula b, @Nonnull BiFunction<Bitmap, Bitmap[], T> factory) {
		Assert.isPremiseValid(
			Objects.equals(a.getFacetGroupId(), b.getFacetGroupId()),
			"Both formulas must share facet group id!"
		);
		Assert.isPremiseValid(
			Objects.equals(a.getReferenceName(), b.getReferenceName()),
			"Both formulas must share facet type!"
		);

		final Bitmap thisFacetIds = a.getFacetIds();
		final Bitmap[] thisFacetBitmaps = a.getBitmaps();
		final Bitmap thatFacetIds = b.getFacetIds();
		final Bitmap[] thatFacetBitmaps = b.getBitmaps();

		final RoaringBitmapWriter<RoaringBitmap> collectedFacetIds = RoaringBitmapBackedBitmap.buildWriter();
		final IntObjectMap<Bitmap> aggregatedBitmaps = new IntObjectHashMap<>(thisFacetIds.size() + thatFacetIds.size());
		final OfInt thisFacetIdsIterator = thisFacetIds.iterator();
		int thisFacetIdsIndex = 0;
		while (thisFacetIdsIterator.hasNext()) {
			final int facetId = thisFacetIdsIterator.next();
			collectedFacetIds.add(facetId);
			aggregatedBitmaps.put(facetId, thisFacetBitmaps[thisFacetIdsIndex++]);
		}

		final OfInt thatFacetIdsIterator = thatFacetIds.iterator();
		int thatFacetIdsIndex = 0;
		while (thatFacetIdsIterator.hasNext()) {
			final int facetId = thatFacetIdsIterator.next();
			final Bitmap bitmap = thatFacetBitmaps[thatFacetIdsIndex++];
			final Bitmap combinedBitmaps = ofNullable(aggregatedBitmaps.get(facetId))
				.map(it -> (Bitmap) new BaseBitmap(RoaringBitmap.or(getRoaringBitmap(it), getRoaringBitmap(bitmap))))
				.orElse(bitmap);
			collectedFacetIds.add(facetId);
			aggregatedBitmaps.put(facetId, combinedBitmaps);
		}

		final BaseBitmap collectedAndFinalizedFacetIds = new BaseBitmap(collectedFacetIds.get());
		final Bitmap[] collectedBitmaps = new Bitmap[collectedAndFinalizedFacetIds.size()];
		int index = 0;
		for (Integer facetId : collectedAndFinalizedFacetIds) {
			collectedBitmaps[index++] = aggregatedBitmaps.get(facetId);
		}

		return factory.apply(collectedAndFinalizedFacetIds, collectedBitmaps);
	}

	/**
	 * Returns {@link FacetHaving#getType()} of the facet that is targeted by this formula.
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
	 * Returns array of requested facet ids from {@link FacetHaving} filtering query.
	 * This information is crucial for correct {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} computation.
	 */
	@Nonnull
	Bitmap getFacetIds();

	/**
	 * Returns array of bitmaps that match the requested facet ids from {@link #getFacetIds()}.
	 */
	@Nonnull
	Bitmap[] getBitmaps();

	/**
	 * Returns clone of the formula combining all facets from the `anotherFormula` and this formula.
	 * Both formulas must be of same type and must target same facet group id.
	 */
	@Nonnull
	FacetGroupFormula mergeWith(@Nonnull FacetGroupFormula anotherFormula);

}
