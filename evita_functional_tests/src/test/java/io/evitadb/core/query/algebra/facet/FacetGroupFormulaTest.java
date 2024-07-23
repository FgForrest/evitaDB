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

import com.esotericsoftware.kryo.util.IntMap;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies behaviour of {@link FacetGroupFormula} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class FacetGroupFormulaTest {

	@Test
	void shouldMergeTwoFacetGroupFormulasTogether() {
		final FacetGroupAndFormula a = new FacetGroupAndFormula(
			"product", 1,
			new ArrayBitmap(1, 2, 3),
			new BaseBitmap(10),
			new BaseBitmap(11),
			new BaseBitmap(12)
		);

		final FacetGroupAndFormula b = new FacetGroupAndFormula(
			"product", 1,
			new ArrayBitmap(3, 4),
			new BaseBitmap(20),
			new BaseBitmap(21)
		);

		final FacetGroupAndFormula result = FacetGroupFormula.mergeWith(
			a, b, (facetIds, bitmaps) -> new FacetGroupAndFormula("product", 1, facetIds, bitmaps)
		);

		assertNotNull(result);

		assertFacetGroupFormulaIs(
			new int[] {1, 2, 3, 4},
			new Bitmap[] {
				new BaseBitmap(10),
				new BaseBitmap(11),
				new BaseBitmap(12, 20),
				new BaseBitmap(21),
			},
			result
		);
	}

	@Test
	void shouldFailToMergeTwoFacetGroupFormulasTogetherWhenEntitTypeDiffers() {
		assertThrows(
			EvitaInternalError.class,
			() -> FacetGroupFormula.mergeWith(
				new FacetGroupAndFormula(
					"productA", 1,
					EmptyBitmap.INSTANCE
				), new FacetGroupAndFormula(
					"productB", 1,
					EmptyBitmap.INSTANCE
				),
				(facetIds, bitmaps) -> new FacetGroupAndFormula("product", 1, facetIds, bitmaps)
			)
		);
	}

	@Test
	void shouldFailToMergeTwoFacetGroupFormulasTogetherWhenGroupIdDiffers() {
		assertThrows(
			EvitaInternalError.class,
			() -> FacetGroupFormula.mergeWith(
				new FacetGroupAndFormula(
					"product", 1,
					EmptyBitmap.INSTANCE
				), new FacetGroupAndFormula(
					"product", 2,
					EmptyBitmap.INSTANCE
				),
				(facetIds, bitmaps) -> new FacetGroupAndFormula("product", 1, facetIds, bitmaps)
			)
		);
	}

	private static void assertFacetGroupFormulaIs(int[] facetIds, Bitmap[] bitmaps, FacetGroupAndFormula actualFormula) {
		final Bitmap actualFacetIds = actualFormula.getFacetIds();
		final Bitmap[] actualBitmaps = actualFormula.getBitmaps();

		assertEquals(facetIds.length, actualFacetIds.size());
		assertEquals(bitmaps.length, actualBitmaps.length);

		final IntMap<Bitmap> expected = new IntMap<>(facetIds.length);
		for (int i = 0; i < facetIds.length; i++) {
			expected.put(facetIds[i], bitmaps[i]);
		}

		final IntMap<Bitmap> actual = new IntMap<>(actualFacetIds.size());
		int index = 0;
		for (Integer facetId : actualFacetIds) {
			actual.put(facetId, actualBitmaps[index++]);
		}

		assertEquals(expected, actual);
	}

}
