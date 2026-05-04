/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.algebra.utils.visitor;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.visitor.PrettyPrintingFormulaVisitor.PrettyPrintStyle;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link PrettyPrintingFormulaVisitor} verifying formula tree rendering
 * in both normal and verbose modes.
 *
 * @author evitaDB
 */
@DisplayName("PrettyPrintingFormulaVisitor - formula tree rendering")
@Tag(ENGINE)
@Tag(QUERY)
class PrettyPrintingFormulaVisitorTest {

	@Nested
	@DisplayName("Static factory methods")
	class StaticMethodTest {

		@Test
		@DisplayName("should produce non-empty output via toString()")
		void shouldProduceNonEmptyOutputViaToString() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(1, 2, 3));

			final String result = PrettyPrintingFormulaVisitor.toString(leaf);

			assertNotNull(result);
			assertFalse(result.isEmpty());
		}

		@Test
		@DisplayName("should produce non-empty output via toStringVerbose()")
		void shouldProduceNonEmptyOutputViaToStringVerbose() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(1, 2, 3));

			final String result = PrettyPrintingFormulaVisitor.toStringVerbose(leaf);

			assertNotNull(result);
			assertFalse(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("Leaf formula rendering")
	class LeafFormulaTest {

		@Test
		@DisplayName("should render leaf without result count suffix")
		void shouldRenderLeafWithoutResultCountSuffix() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(1, 2));

			final String result = PrettyPrintingFormulaVisitor.toString(leaf);

			// leaf has no inner formulas -> no arrow + result count suffix
			assertFalse(result.contains("\u2192"));
			// should contain [#0] formula id
			assertTrue(result.contains("[#0]"));
		}

		@Test
		@DisplayName("should render EmptyFormula")
		void shouldRenderEmptyFormula() {
			final String result = PrettyPrintingFormulaVisitor.toString(EmptyFormula.INSTANCE);

			assertTrue(result.contains("[#0]"));
			assertTrue(result.contains("EMPTY"));
		}
	}

	@Nested
	@DisplayName("Tree structure rendering")
	class TreeRenderingTest {

		@Test
		@DisplayName("should render parent with result count")
		void shouldRenderParentWithResultCount() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final String result = PrettyPrintingFormulaVisitor.toString(tree);

			// parent formula with inner formulas should have arrow + result count
			assertTrue(result.contains("\u2192"));
			assertTrue(result.contains("result count"));
		}

		@Test
		@DisplayName("should indent children deeper than parent")
		void shouldIndentChildrenDeeperThanParent() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final String result = PrettyPrintingFormulaVisitor.toString(tree);
			final String[] lines = result.split("\n");

			// root (line 0) should have no leading spaces
			assertTrue(lines[0].startsWith("[#"));
			// children (line 1, 2) should have leading spaces (3 spaces default indent)
			assertTrue(lines[1].startsWith("   "));
		}

		@Test
		@DisplayName("should use custom indent value")
		void shouldUseCustomIndentValue() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final PrettyPrintingFormulaVisitor visitor = new PrettyPrintingFormulaVisitor(5);
			tree.accept(visitor);
			final String result = visitor.getResult();
			final String[] lines = result.split("\n");

			// children should be indented by 5 spaces
			assertTrue(lines[1].startsWith("     ["));
		}
	}

	@Nested
	@DisplayName("Duplicate formula detection")
	class DuplicateDetectionTest {

		@Test
		@DisplayName("should mark duplicate formula instances as references")
		void shouldMarkDuplicateFormulaInstancesAsReferences() {
			final ConstantFormula shared = new ConstantFormula(new ArrayBitmap(1, 2));
			// Use the same instance in two branches
			final Formula tree = new AndFormula(
				new OrFormula(shared, new ConstantFormula(new ArrayBitmap(3))),
				new OrFormula(shared, new ConstantFormula(new ArrayBitmap(4)))
			);

			final String result = PrettyPrintingFormulaVisitor.toString(tree);

			// the shared formula should appear once as [#N] and once as [Ref to #N]
			assertTrue(result.contains("[Ref to #"));
		}

		@Test
		@DisplayName("should not mark distinct formulas as references")
		void shouldNotMarkDistinctFormulasAsReferences() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final String result = PrettyPrintingFormulaVisitor.toString(tree);

			assertFalse(result.contains("[Ref to #"));
		}
	}

	@Nested
	@DisplayName("Normal vs Verbose style")
	class StyleTest {

		@Test
		@DisplayName("should print formula toString in NORMAL mode")
		void shouldPrintFormulaToStringInNormalMode() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(1, 2, 3));

			final PrettyPrintingFormulaVisitor visitor = new PrettyPrintingFormulaVisitor(3, PrettyPrintStyle.NORMAL);
			leaf.accept(visitor);
			final String result = visitor.getResult();

			// ConstantFormula.toString() returns "N primary keys"
			assertTrue(result.contains("primary keys"));
		}

		@Test
		@DisplayName("should print formula toStringVerbose in VERBOSE mode")
		void shouldPrintFormulaToStringVerboseInVerboseMode() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(5, 10, 15));

			final String result = PrettyPrintingFormulaVisitor.toStringVerbose(leaf);

			// ConstantFormula.toStringVerbose() returns the bitmap contents
			// which should contain the actual values
			assertNotNull(result);
			assertFalse(result.isEmpty());
		}

		@Test
		@DisplayName("should include full bitmap in VERBOSE result count for parent formulas")
		void shouldIncludeFullBitmapInVerboseResultCountForParentFormulas() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final String normalResult = PrettyPrintingFormulaVisitor.toString(tree);
			final String verboseResult = PrettyPrintingFormulaVisitor.toStringVerbose(tree);

			// normal contains "result count", verbose contains the actual bitmap
			assertTrue(normalResult.contains("result count"));
			// verbose should NOT contain "result count" text - it shows the actual bitmap
			assertFalse(verboseResult.contains("result count"));
		}
	}

	@Nested
	@DisplayName("Formula ID numbering")
	class IdNumberingTest {

		@Test
		@DisplayName("should assign sequential IDs starting from zero")
		void shouldAssignSequentialIdsStartingFromZero() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final String result = PrettyPrintingFormulaVisitor.toString(tree);

			assertTrue(result.contains("[#0]"));
			assertTrue(result.contains("[#1]"));
			assertTrue(result.contains("[#2]"));
		}
	}

	@Nested
	@DisplayName("Deep tree rendering")
	class DeepTreeTest {

		@Test
		@DisplayName("should correctly render multi-level tree")
		void shouldCorrectlyRenderMultiLevelTree() {
			final Formula tree = new AndFormula(
				new OrFormula(
					new ConstantFormula(new ArrayBitmap(1)),
					new ConstantFormula(new ArrayBitmap(2))
				),
				new UserFilterFormula(
					new ConstantFormula(new ArrayBitmap(1, 2))
				)
			);

			final String result = PrettyPrintingFormulaVisitor.toString(tree);
			final String[] lines = result.split("\n");

			// root at level 0, two children at level 1, grandchildren at level 2
			assertTrue(lines.length >= 5);
			// root has no indent
			assertTrue(lines[0].startsWith("[#0]"));
			// level 1 children have 3 spaces indent
			assertTrue(lines[1].startsWith("   [#"));
			// level 2 grandchildren have 6 spaces indent
			assertTrue(lines[2].startsWith("      [#"));
		}
	}

	@Nested
	@DisplayName("Constructor variations")
	class ConstructorTest {

		@Test
		@DisplayName("should use default indent of 3 with no-arg constructor")
		void shouldUseDefaultIndentOfThreeWithNoArgConstructor() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final PrettyPrintingFormulaVisitor visitor = new PrettyPrintingFormulaVisitor();
			tree.accept(visitor);
			final String result = visitor.getResult();
			final String[] lines = result.split("\n");

			// children at level 1 should be indented by 3 spaces (default)
			assertTrue(lines[1].startsWith("   [#"));
		}

		@Test
		@DisplayName("should accept zero indent")
		void shouldAcceptZeroIndent() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final PrettyPrintingFormulaVisitor visitor = new PrettyPrintingFormulaVisitor(0);
			tree.accept(visitor);
			final String result = visitor.getResult();
			final String[] lines = result.split("\n");

			// all lines should start with [# because indent is 0
			for (String line : lines) {
				assertTrue(line.startsWith("[#") || line.startsWith("[Ref"));
			}
		}
	}
}
