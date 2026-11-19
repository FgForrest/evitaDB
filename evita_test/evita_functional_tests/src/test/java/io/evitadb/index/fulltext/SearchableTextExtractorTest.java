/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.fulltext;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.index.fulltext.SearchableTextExtractor.ExtractionMode;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SearchableTextExtractor} - what text a stored associated data value contributes to its fulltext
 * field.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Searchable text extraction")
@Tag(ENGINE)
@Tag(FULLTEXT)
class SearchableTextExtractorTest {

	private static final ComplexDataObject ARTICLE = new ComplexDataObject(
		map(
			"title", new DataItemValue("Black shoes"),
			"views", new DataItemValue(42L),
			"blocks", new DataItemArray(
				new DataItem[]{
					map("text", new DataItemValue("first block"), "caption", new DataItemValue("photo")),
					map("text", new DataItemValue("second block")),
					map("text", new DataItemValue("first block")),
					map("text", new DataItemValue(7L))
				}
			),
			"sections", new DataItemArray(
				new DataItem[]{
					map("items", new DataItemArray(new DataItem[]{map("text", new DataItemValue("nested a"))})),
					map("items", new DataItemArray(new DataItem[]{map("text", new DataItemValue("nested b"))}))
				}
			),
			"labels", map("cs", new DataItemValue("Obuv"), "en", new DataItemValue("Shoes"))
		)
	);
	/**
	 * A tagged product: string arrays as a property, inside the elements of an array, two levels deep and nested in
	 * each other, plus an array holding a number.
	 */
	private static final ComplexDataObject TAGGED = new ComplexDataObject(
		map(
			"tags", strings("red", "leather", "red"),
			"blocks", new DataItemArray(
				new DataItem[]{
					map("tags", strings("summer", "red")),
					map("tags", strings("winter")),
					map("text", new DataItemValue("untagged"))
				}
			),
			"chapters", new DataItemArray(
				new DataItem[]{
					map(
						"sections", new DataItemArray(
							new DataItem[]{map("tags", strings("deep a")), map("tags", strings("deep b", "deep a"))}
						)
					)
				}
			),
			"matrix", new DataItemArray(new DataItem[]{strings("x", "y"), strings("z")}),
			"codes", new DataItemArray(new DataItem[]{new DataItemValue("A1"), new DataItemValue(7L)})
		)
	);

	/**
	 * Builds a map node of the passed key/value pairs, keeping their order.
	 *
	 * @param keysAndValues alternating keys and nodes
	 * @return the map node
	 */
	@Nonnull
	private static DataItemMap map(@Nonnull Object... keysAndValues) {
		final Map<String, DataItem> children = CollectionUtils.createLinkedHashMap(keysAndValues.length / 2);
		for (int i = 0; i < keysAndValues.length; i += 2) {
			children.put((String) keysAndValues[i], (DataItem) keysAndValues[i + 1]);
		}
		return new DataItemMap(children);
	}

	/**
	 * Builds an array node of string leaves, keeping their order.
	 *
	 * @param values the strings
	 * @return the array node
	 */
	@Nonnull
	private static DataItemArray strings(@Nonnull String... values) {
		final DataItem[] children = new DataItem[values.length];
		for (int i = 0; i < values.length; i++) {
			children[i] = new DataItemValue(values[i]);
		}
		return new DataItemArray(children);
	}

	/**
	 * Extracts the texts of a value with an expression in the lenient mode.
	 *
	 * @param value      the value
	 * @param expression the expression text, or `null`
	 * @return the texts
	 */
	@Nonnull
	private static Set<String> extract(@Nonnull Serializable value, @Nullable String expression) {
		return extract(value, expression, ExtractionMode.LENIENT);
	}

	/**
	 * Extracts the texts of a value with an expression in the passed mode.
	 *
	 * @param value      the value
	 * @param expression the expression text, or `null`
	 * @param mode       the extraction mode
	 * @return the texts
	 */
	@Nonnull
	private static Set<String> extract(
		@Nonnull Serializable value,
		@Nullable String expression,
		@Nonnull ExtractionMode mode
	) {
		final Expression textExpression = expression == null ? null : ExpressionFactory.parse(expression);
		return SearchableTextExtractor.extract(value, textExpression, "product", "article", mode);
	}

	@Nested
	@DisplayName("Plain values")
	class PlainValues {

		@Test
		@DisplayName("a String is one text")
		void shouldExtractString() {
			assertEquals(List.of("black shoes"), List.copyOf(extract("black shoes", null)));
		}

		@Test
		@DisplayName("a String[] contributes its distinct non-null elements")
		void shouldExtractStringArray() {
			assertEquals(
				List.of("red", "leather"),
				List.copyOf(extract(new String[]{"red", null, "leather", "red"}, null))
			);
		}

		@Test
		@DisplayName("any other type, or a complex data object without an expression, holds no text")
		void shouldExtractNothingFromOtherValues() {
			assertTrue(extract(42, null).isEmpty());
			assertTrue(extract(ARTICLE, null).isEmpty());
		}

		@Test
		@DisplayName("the runtime type decides, so a String stays a String whatever expression is passed")
		void shouldDispatchOnRuntimeType() {
			assertEquals(Set.of("black shoes"), extract("black shoes", "$value.title"));
		}

	}

	@Nested
	@DisplayName("Complex data objects")
	class ComplexDataObjects {

		@Test
		@DisplayName("a path selects one text")
		void shouldExtractPath() {
			assertEquals(Set.of("Black shoes"), extract(ARTICLE, "$value.title"));
		}

		@Test
		@DisplayName("a lenient spread selects its distinct string leaves and skips the others")
		void shouldExtractSpreadSkippingNonStrings() {
			assertEquals(
				List.of("first block", "second block"),
				List.copyOf(extract(ARTICLE, "$value.blocks.*[$.text]"))
			);
		}

		@Test
		@DisplayName("nested spreads are flattened")
		void shouldFlattenNestedSpreads() {
			assertEquals(Set.of("nested a", "nested b"), extract(ARTICLE, "$value.sections.*[$.items.*[$.text]]"));
		}

		@Test
		@DisplayName("a spread over a map contributes its values")
		void shouldExtractMapSpreadValues() {
			assertEquals(Set.of("Obuv", "Shoes"), extract(ARTICLE, "$value.labels.*[$]"));
		}

		@Test
		@DisplayName("an array node of strings contributes its distinct strings in both modes")
		void shouldExtractArrayNodeOfStrings() {
			assertEquals(List.of("red", "leather"), List.copyOf(extract(TAGGED, "$value.tags")));
			assertEquals(
				List.of("red", "leather"), List.copyOf(extract(TAGGED, "$value.tags", ExtractionMode.STRICT))
			);
		}

		@Test
		@DisplayName("a spread over array nodes contributes every string of every array in both modes")
		void shouldExtractSpreadOverArrayNodes() {
			assertEquals(Set.of("summer", "red", "winter"), extract(TAGGED, "$value.blocks.*[$.tags]"));
			assertEquals(
				Set.of("summer", "red", "winter"),
				extract(TAGGED, "$value.blocks.*[$.tags]", ExtractionMode.STRICT)
			);
		}

		@Test
		@DisplayName("nested spreads over array nodes and array nodes of array nodes are flattened in both modes")
		void shouldFlattenNestedArrayNodes() {
			final String nestedSpread = "$value.chapters.*[$.sections.*[$.tags]]";
			assertEquals(Set.of("deep a", "deep b"), extract(TAGGED, nestedSpread));
			assertEquals(Set.of("deep a", "deep b"), extract(TAGGED, nestedSpread, ExtractionMode.STRICT));
			assertEquals(List.of("x", "y", "z"), List.copyOf(extract(TAGGED, "$value.matrix")));
			assertEquals(List.of("x", "y", "z"), List.copyOf(extract(TAGGED, "$value.matrix", ExtractionMode.STRICT)));
		}

		@Test
		@DisplayName("an array node holding a non-string is refused strictly and skipped leniently")
		void shouldRefuseNonStringInArrayNodeInStrictMode() {
			assertEquals(Set.of("A1"), extract(TAGGED, "$value.codes"));
			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				() -> extract(TAGGED, "$value.codes", ExtractionMode.STRICT)
			);
			assertTrue(exception.getPrivateMessage().contains("java.lang.Long"), exception.getPrivateMessage());
			// an array node of objects holds no text - an object is navigated, not read as text
			assertTrue(extract(TAGGED, "$value.blocks").isEmpty());
		}

		@Test
		@DisplayName("a missing path or a non-string result holds no text")
		void shouldExtractNothingFromMissingOrNonString() {
			assertTrue(extract(ARTICLE, "$value.perex").isEmpty());
			assertTrue(extract(ARTICLE, "$value.views").isEmpty());
			assertTrue(extract(ARTICLE, "$value.labels").isEmpty());
		}

		@Test
		@DisplayName("an expression that cannot be evaluated over the value holds no text and does not throw")
		void shouldExtractNothingWhenEvaluationFails() {
			assertTrue(extract(ARTICLE, "$value.blocks[10].text").isEmpty());
			assertTrue(extract(ARTICLE, "$value.title.length").isEmpty());
			assertTrue(extract(ARTICLE, "$value.blocks.*[$.text.foo]").isEmpty());
		}

		@Test
		@DisplayName("a value fitting the expression extracts the same texts strictly and leniently")
		void shouldExtractTheSameTextsInBothModesFromAFittingValue() {
			assertEquals(
				extract(ARTICLE, "$value.labels.*[$]", ExtractionMode.STRICT),
				extract(ARTICLE, "$value.labels.*[$]", ExtractionMode.LENIENT)
			);
			assertEquals(Set.of("Black shoes"), extract(ARTICLE, "$value.title", ExtractionMode.STRICT));
		}

		@Test
		@DisplayName("the strict mode skips a missing path and nulls of a spread")
		void shouldSkipNullsInStrictMode() {
			assertTrue(extract(ARTICLE, "$value.perex", ExtractionMode.STRICT).isEmpty());
			assertEquals(
				Set.of("photo"), extract(ARTICLE, "$value.blocks.*[$.caption]", ExtractionMode.STRICT)
			);
		}

		@Test
		@DisplayName("the strict mode refuses a leaf that is not a string, naming the item, expression and type")
		void shouldRefuseNonStringLeafInStrictMode() {
			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				() -> extract(ARTICLE, "$value.blocks.*[$.text]", ExtractionMode.STRICT)
			);
			assertTrue(exception.getPrivateMessage().contains("`article`"), exception.getPrivateMessage());
			assertTrue(exception.getPrivateMessage().contains("`product`"), exception.getPrivateMessage());
			assertTrue(exception.getPrivateMessage().contains("$value.blocks.*[$.text]"), exception.getPrivateMessage());
			assertTrue(exception.getPrivateMessage().contains("java.lang.Long"), exception.getPrivateMessage());
			assertThrows(ExpressionEvaluationException.class, () -> extract(ARTICLE, "$value.views", ExtractionMode.STRICT));
		}

		@Test
		@DisplayName("the strict mode refuses an expression that cannot be evaluated over the value")
		void shouldRefuseFailingExpressionInStrictMode() {
			assertThrows(
				ExpressionEvaluationException.class,
				() -> extract(ARTICLE, "$value.blocks[10].text", ExtractionMode.STRICT)
			);
		}

		@Test
		@DisplayName("the strict mode hints at `?.*` when a plain spread meets a missing collection")
		void shouldHintAtOptionalSpreadInStrictMode() {
			final ExpressionEvaluationException exception = assertThrows(
				ExpressionEvaluationException.class,
				() -> extract(ARTICLE, "$value.chapters.*[$.text]", ExtractionMode.STRICT)
			);
			assertTrue(exception.getPrivateMessage().contains("`?.*`"), exception.getPrivateMessage());
			// the optional spread is the fix
			assertTrue(extract(ARTICLE, "$value.chapters?.*[$.text]", ExtractionMode.STRICT).isEmpty());
		}

		@Test
		@DisplayName("the same value always extracts the same texts")
		void shouldBeDeterministic() {
			assertEquals(
				extract(ARTICLE, "$value.blocks.*[$.text] ?? $value.title"),
				extract(ARTICLE, "$value.blocks.*[$.text] ?? $value.title")
			);
		}

	}

}
