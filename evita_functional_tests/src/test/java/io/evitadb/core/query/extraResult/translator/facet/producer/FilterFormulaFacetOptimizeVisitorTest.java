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
						new FacetGroupOrFormula(Entities.PARAMETER, 2, new ArrayBitmap(1), new ArrayBitmap(7)),
						new FacetGroupOrFormula(Entities.BRAND, 1, new ArrayBitmap(1), new ArrayBitmap(1)),
						new FacetGroupOrFormula(Entities.STORE, 1, new ArrayBitmap(1), new ArrayBitmap(2))
					)
				)
			)
		);
		assertEquals("""
			[#0] OR → [1, 2, 3]
			   [#1] OR → [1, 2, 3]
			      [#2] [1, 2]
			      [#3] [2, 3]
			   [#4] USER FILTER → EMPTY
			      [#5] AND → [2]
			         [#6] [1, 2]
			         [#7] [2, 3]
			      [#8] AND → []
			         [#9] FACET PARAMETER OR (2 - [1]):  ↦ [7]
			         [#10] FACET BRAND OR (1 - [1]):  ↦ [1]
			         [#11] FACET STORE OR (1 - [1]):  ↦ [2]
			""",
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
						new FacetGroupOrFormula(Entities.PARAMETER, 10, new ArrayBitmap(1), new ArrayBitmap(12)),
						new AndFormula(
							new AndFormula(
								new FacetGroupOrFormula(Entities.PARAMETER, 2, new ArrayBitmap(1), new ArrayBitmap(7)),
								new FacetGroupOrFormula(Entities.BRAND, 1, new ArrayBitmap(1), new ArrayBitmap(1)),
								new FacetGroupOrFormula(Entities.STORE, 1, new ArrayBitmap(1), new ArrayBitmap(2))
							),
							new OrFormula(
								new FacetGroupOrFormula(Entities.BRAND, 1, new ArrayBitmap(2), new ArrayBitmap(7)),
								new FacetGroupOrFormula(Entities.STORE, 1, new ArrayBitmap(3), new ArrayBitmap(9))
							)
						)
					),
					new ConstantFormula(new ArrayBitmap(2, 3))
				),
				new ConstantFormula(new ArrayBitmap(8, 6))
			)
		);
		assertEquals("""
			[#0] OR → [1, 2, 3, 6, 8]
			   [#1] OR → [1, 2, 3, 6, 8]
			      [#2] [1, 2]
			      [#3] [2, 3]
			      [#4] [8, 6]
			   [#5] USER FILTER → EMPTY
			      [#6] AND → [2]
			         [#7] [1, 2]
			         [#8] [2, 3]
			      [#9] NOT → []
			         [#10] FACET PARAMETER OR (10 - [1]):  ↦ [12]
			         [#11] AND → EMPTY
			            [#12] AND → []
			               [#13] FACET PARAMETER OR (2 - [1]):  ↦ [7]
			               [#14] FACET BRAND OR (1 - [1]):  ↦ [1]
			               [#15] FACET STORE OR (1 - [1]):  ↦ [2]
			            [#16] OR → [7, 9]
			               [#17] FACET BRAND OR (1 - [2]):  ↦ [7]
			               [#18] FACET STORE OR (1 - [3]):  ↦ [9]
			""",
			PrettyPrintingFormulaVisitor.toString(optimizedFormula)
		);
	}

}