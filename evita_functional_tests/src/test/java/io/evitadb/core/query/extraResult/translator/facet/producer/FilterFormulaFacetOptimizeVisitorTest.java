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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.visitor.PrettyPrintingFormulaVisitor;
import io.evitadb.core.query.extraResult.translator.facet.FilterFormulaFacetOptimizeVisitor;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies behaviour of {@link FilterFormulaFacetOptimizeVisitor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FilterFormulaFacetOptimizeVisitorTest {

	@Test
	void shouldOptimizeBaseQuery() {
		final Formula optimizedFormula = FilterFormulaFacetOptimizeVisitor.optimize(
			new OrFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)),
				new ConstantFormula(new ArrayBitmap(2, 3)),
				new UserFilterFormula(
					new ConstantFormula(new ArrayBitmap(1, 2)),
					new ConstantFormula(new ArrayBitmap(2, 3)),
					new AndFormula(
						new FacetGroupOrFormula(Entities.PARAMETER, 2, new int[]{1}, new ArrayBitmap(7)),
						new FacetGroupOrFormula(Entities.BRAND, 1, new int[]{1}, new ArrayBitmap(1)),
						new FacetGroupOrFormula(Entities.STORE, 1, new int[]{1}, new ArrayBitmap(2))
					)
				)
			)
		);
		assertEquals(
			"[#0] OR\n" +
				"   [#1] OR\n" +
				"      [#2] [1, 2]\n" +
				"      [#3] [2, 3]\n" +
				"   [#4] USER FILTER\n" +
				"      [#5] AND\n" +
				"         [#6] [1, 2]\n" +
				"         [#7] [2, 3]\n" +
				"      [#8] AND\n" +
				"         [#9] FACET PARAMETER OR (2 - [1]):  ↦ [7]\n" +
				"         [#10] FACET BRAND OR (1 - [1]):  ↦ [1]\n" +
				"         [#11] FACET STORE OR (1 - [1]):  ↦ [2]\n",
			PrettyPrintingFormulaVisitor.toString(optimizedFormula)
		);
	}

	@Test
	void shouldOptimizeComplexBaseQuery() {
		final Formula optimizedFormula = FilterFormulaFacetOptimizeVisitor.optimize(
			new OrFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)),
				new ConstantFormula(new ArrayBitmap(2, 3)),
				new UserFilterFormula(
					new ConstantFormula(new ArrayBitmap(1, 2)),
					new NotFormula(
						new FacetGroupOrFormula(Entities.PARAMETER, 10, new int[]{1}, new ArrayBitmap(12)),
						new AndFormula(
							new AndFormula(
								new FacetGroupOrFormula(Entities.PARAMETER, 2, new int[]{1}, new ArrayBitmap(7)),
								new FacetGroupOrFormula(Entities.BRAND, 1, new int[]{1}, new ArrayBitmap(1)),
								new FacetGroupOrFormula(Entities.STORE, 1, new int[]{1}, new ArrayBitmap(2))
							),
							new OrFormula(
								new FacetGroupOrFormula(Entities.BRAND, 1, new int[]{2}, new ArrayBitmap(7)),
								new FacetGroupOrFormula(Entities.STORE, 1, new int[]{3}, new ArrayBitmap(9))
							)
						)
					),
					new ConstantFormula(new ArrayBitmap(2, 3))
				),
				new ConstantFormula(new ArrayBitmap(8, 6))
			)
		);
		assertEquals(
			"[#0] OR\n" +
				"   [#1] OR\n" +
				"      [#2] [1, 2]\n" +
				"      [#3] [2, 3]\n" +
				"      [#4] [8, 6]\n" +
				"   [#5] USER FILTER\n" +
				"      [#6] AND\n" +
				"         [#7] [1, 2]\n" +
				"         [#8] [2, 3]\n" +
				"      [#9] NOT\n" +
				"         [#10] FACET PARAMETER OR (10 - [1]):  ↦ [12]\n" +
				"         [#11] AND\n" +
				"            [#12] AND\n" +
				"               [#13] FACET PARAMETER OR (2 - [1]):  ↦ [7]\n" +
				"               [#14] FACET BRAND OR (1 - [1]):  ↦ [1]\n" +
				"               [#15] FACET STORE OR (1 - [1]):  ↦ [2]\n" +
				"            [#16] OR\n" +
				"               [#17] FACET BRAND OR (1 - [2]):  ↦ [7]\n" +
				"               [#18] FACET STORE OR (1 - [3]):  ↦ [9]\n",
			PrettyPrintingFormulaVisitor.toString(optimizedFormula)
		);
	}

}