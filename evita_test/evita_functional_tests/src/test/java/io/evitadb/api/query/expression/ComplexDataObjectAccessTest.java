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

package io.evitadb.api.query.expression;

import io.evitadb.api.query.expression.evaluate.SingleVariableEvaluationContext;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.exception.ExpressionEvaluationException;
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

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.EXPRESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that EvitaEL navigates a {@link ComplexDataObject} - the stored form of associated data of a POJO type -
 * with dot and bracket notation, spreads its arrays and maps, and presents every leaf unwrapped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EvitaEL over a complex data object")
@Tag(CONTRACT)
@Tag(EXPRESSION)
class ComplexDataObjectAccessTest {

	/**
	 * An article: a title, a missing perex, and two blocks of which only the first carries a caption.
	 */
	private static final ComplexDataObject ARTICLE = new ComplexDataObject(
		map(
			"title", new DataItemValue("Černá obuv"),
			"views", new DataItemValue(42L),
			"blocks", new DataItemArray(
				new DataItem[]{
					map("text", new DataItemValue("first block"), "caption", new DataItemValue("photo")),
					map("text", new DataItemValue("second block"))
				}
			),
			"labels", map("cs", new DataItemValue("Obuv"), "en", new DataItemValue("Shoes"))
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
	 * Evaluates the expression with the article bound to `$value` as it is stored - a complex data object.
	 *
	 * @param expression the expression
	 * @return the result
	 */
	@Nullable
	private static Serializable evaluate(@Nonnull String expression) {
		return ExpressionFactory.parse(expression).compute(new SingleVariableEvaluationContext("value", ARTICLE));
	}

	/**
	 * Evaluates the expression with the passed complex data object bound to `$value`.
	 *
	 * @param value      the complex data object
	 * @param expression the expression
	 * @return the result
	 */
	@Nullable
	private static Serializable evaluate(@Nonnull ComplexDataObject value, @Nonnull String expression) {
		return ExpressionFactory.parse(expression).compute(new SingleVariableEvaluationContext("value", value));
	}

	/**
	 * Asserts that evaluating the expression over the article fails with a message containing the fragment.
	 *
	 * @param fragment   the expected part of the message
	 * @param expression the expression
	 */
	private static void assertMessageContains(@Nonnull String fragment, @Nonnull String expression) {
		final ExpressionEvaluationException exception = assertThrows(
			ExpressionEvaluationException.class, () -> evaluate(expression)
		);
		assertTrue(exception.getPrivateMessage().contains(fragment), exception.getPrivateMessage());
	}

	@Nested
	@DisplayName("Property and element access")
	class PropertyAndElementAccess {

		@Test
		@DisplayName("Reads a property with dot notation and unwraps the leaf")
		void shouldReadPropertyWithDotNotation() {
			assertEquals("Černá obuv", evaluate("$value.title"));
			assertEquals(42L, evaluate("$value.views"));
		}

		@Test
		@DisplayName("Reads a property with bracket notation")
		void shouldReadPropertyWithBracketNotation() {
			assertEquals("Černá obuv", evaluate("$value['title']"));
			assertEquals("Shoes", evaluate("$value.labels['en']"));
		}

		@Test
		@DisplayName("Reads an array element by index")
		void shouldReadArrayElementByIndex() {
			assertEquals("second block", evaluate("$value.blocks[1].text"));
		}

		@Test
		@DisplayName("Returns null for a missing property, which coalescing handles")
		void shouldReturnNullForMissingProperty() {
			assertNull(evaluate("$value.perex"));
			assertEquals("Černá obuv", evaluate("$value.perex ?? $value.title"));
			assertNull(evaluate("$value.perex?.text"));
		}

		@Test
		@DisplayName("Refuses an index outside an array node, naming the index")
		void shouldRefuseIndexOutOfBounds() {
			assertMessageContains("Index `5` is out of bounds", "$value.blocks[5]");
			assertMessageContains("Index `-1` is out of bounds", "$value.blocks[-1]");
		}

		@Test
		@DisplayName("Refuses a name on an array node, naming the element")
		void shouldRefuseNameOnArrayNode() {
			assertMessageContains("Cannot access element `text` by name", "$value.blocks['text']");
		}

		@Test
		@DisplayName("Refuses an index on a map node, naming the element")
		void shouldRefuseIndexOnMapNode() {
			assertMessageContains("Cannot access element `0` by index", "$value[0]");
		}

	}

	@Nested
	@DisplayName("Spreading and methods")
	class SpreadingAndMethods {

		@Test
		@DisplayName("Spreads an array node with every element unwrapped")
		void shouldSpreadArrayNode() {
			assertEquals(List.of("first block", "second block"), evaluate("$value.blocks.*[$.text]"));
		}

		@Test
		@DisplayName("Drops nulls with the compact spread")
		void shouldDropNullsWithCompactSpread() {
			assertEquals(List.of("photo"), evaluate("$value.blocks.*![$.caption]"));
		}

		@Test
		@DisplayName("Spreads a map node over its values")
		void shouldSpreadMapNode() {
			assertEquals(Map.of("cs", "Obuv", "en", "Shoes"), evaluate("$value.labels.*[$]"));
		}

		@Test
		@DisplayName("Spreads a complex data object as its root")
		void shouldSpreadComplexDataObjectAsRoot() {
			final ComplexDataObject blocks = new ComplexDataObject(
				new DataItemArray(new DataItem[]{new DataItemValue("a"), new DataItemValue("b")})
			);
			assertEquals(
				List.of("a", "b"),
				ExpressionFactory.parse("$value.*[$]").compute(new SingleVariableEvaluationContext("value", blocks))
			);
		}

		@Test
		@DisplayName("Offers size, any, all and none on nodes")
		void shouldOfferCollectionMethods() {
			assertEquals(2, evaluate("$value.blocks.size()"));
			assertEquals(4, evaluate("$value.size()"));
			assertEquals(true, evaluate("$value.blocks.any($.caption == 'photo')"));
			assertEquals(false, evaluate("$value.blocks.all($.caption == 'photo')"));
			assertEquals(false, evaluate("$value.blocks.none($.text == 'first block')"));
		}

		@Test
		@DisplayName("Decides any, all and none over an empty array node by no element")
		void shouldMatchOverEmptyArrayNode() {
			final ComplexDataObject empty = new ComplexDataObject(map("blocks", new DataItemArray(new DataItem[0])));
			assertEquals(false, evaluate(empty, "$value.blocks.any($.text == 'x')"));
			assertEquals(true, evaluate(empty, "$value.blocks.all($.text == 'x')"));
			assertEquals(true, evaluate(empty, "$value.blocks.none($.text == 'x')"));
		}

		@Test
		@DisplayName("Refuses a predicate that does not evaluate to a boolean")
		void shouldRefuseNonBooleanPredicate() {
			assertMessageContains("Predicate must evaluate to boolean", "$value.blocks.any($.text)");
		}

		@Test
		@DisplayName("Refuses a predicate method on a map node")
		void shouldRefusePredicateMethodOnMapNode() {
			assertMessageContains("Expected an array", "$value.labels.any($ == 'Shoes')");
		}

		@Test
		@DisplayName("Refuses an unsupported method and a size with an argument")
		void shouldRefuseUnsupportedMethodOrArguments() {
			assertMessageContains("Cannot invoke method `first`", "$value.blocks.first()");
			assertMessageContains("requires exactly 0 argument(s)", "$value.blocks.size(1)");
		}

	}

}
