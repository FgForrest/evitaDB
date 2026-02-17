/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.dataType.expression;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.expression.evaluate.MultiVariableEvaluationContext;
import io.evitadb.api.query.expression.object.accessor.ObjectAccessorRegistry;
import io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.exception.ExpressionEvaluationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.utils.ListBuilder.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test class for the {@link ExpressionFactory}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class ExpressionTest {

	@BeforeAll
	static void setup() {
		// provide accessors for test objects
		ObjectAccessorRegistry.getInstance().registerPropertyAccessor(new TestObjectAccessor());
		ObjectAccessorRegistry.getInstance().registerPropertyAccessor(new NestedTestObjectAccessor());
	}

	@ParameterizedTest
	@MethodSource("expressions")
	void shouldEvaluate(String expression, Map<String, Object> variables, Object result) {
		assertEquals(result, evaluate(expression, variables));
	}

	@ParameterizedTest
	@MethodSource("invalidExpressions")
	void shouldNotEvaluate(String expression, Map<String, Object> variables) {
		assertThrows(ExpressionEvaluationException.class, () -> evaluate(expression, variables));
	}

	@ParameterizedTest
	@MethodSource("expressions")
	void shouldSerializeExpressionToString(String expression) {
		assertEquals("'" + expression.trim().replaceAll("'", "\\\\'") + "'", ExpressionFactory.parse(expression).toString());
	}

	@ParameterizedTest
	@MethodSource("expressions")
	void shouldCalculatePossibleRange(String expression, Map<String, Object> variables, Object result, BigDecimalNumberRange range) {
		assertEquals(range, determineRange(expression));
	}

	@Nonnull
	static Stream<Arguments> expressions() {
		return Stream.of(
			/* 1 */ Arguments.of("true", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 2 */ Arguments.of("!true", Map.of(), false, BigDecimalNumberRange.INFINITE),
			/* 3 */ Arguments.of("true || false", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 4 */ Arguments.of("true && false", Map.of(), false, BigDecimalNumberRange.INFINITE),
			/* 5 */ Arguments.of("true && (true || false)", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 6 */ Arguments.of("!true == false", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 7 */ Arguments.of("true == !(false && true)", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 8 */ Arguments.of("true == true", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 9 */ Arguments.of("true == false", Map.of(), false, BigDecimalNumberRange.INFINITE),
			/* 10 */ Arguments.of("5 != 4", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 11 */ Arguments.of("5 == 5", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("5"), new BigDecimal("5"))),
			/* 12 */ Arguments.of("(5 == 5)", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("5"), new BigDecimal("5"))),
			/* 13 */ Arguments.of("true == (5 == 5)", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 14 */ Arguments.of("'abc' == 'abc'", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 15 */ Arguments.of("'abc' == 'def'", Map.of(), false, BigDecimalNumberRange.INFINITE),
			/* 16 */ Arguments.of("10 > 5", Map.of(), true, BigDecimalNumberRange.from(new BigDecimal("5.0000000000000001"))),
			/* 17 */ Arguments.of("5 < 10", Map.of(), true, BigDecimalNumberRange.to(new BigDecimal("4.9999999999999999"))),
			/* 18 */ Arguments.of("10 < 5", Map.of(), false, BigDecimalNumberRange.to(new BigDecimal("4.9999999999999999"))),
			/* 19 */ Arguments.of("5 > 10", Map.of(), false, BigDecimalNumberRange.from(new BigDecimal("5.0000000000000001"))),
			/* 20 */ Arguments.of("10 > 5 && 5 < 10", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 21 */ Arguments.of("10 > 5 || 5 > 10", Map.of(), true, BigDecimalNumberRange.from(new BigDecimal("5.0000000000000001"))),
			/* 22 */ Arguments.of("10 < 5 || 5 < 10", Map.of(), true, BigDecimalNumberRange.to(new BigDecimal("4.9999999999999999"))),
			/* 23 */ Arguments.of("10 < 5 || 5 > 10", Map.of(), false, BigDecimalNumberRange.INFINITE),
			/* 24 */ Arguments.of("10 <= 5 || 5 >= 10", Map.of(), false, BigDecimalNumberRange.INFINITE),
			/* 25 */ Arguments.of("10 <= 5 || 6 >= 5", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 26 */ Arguments.of("1 + 3 + 5 == 9", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("9"), new BigDecimal("9"))),
			/* 27 */ Arguments.of("1 + 3 + 5 == 8", Map.of(), false, BigDecimalNumberRange.INFINITE),
			/* 28 */ Arguments.of("1 + 3 + 5 != 8", Map.of(), true, BigDecimalNumberRange.INFINITE),
			/* 29 */ Arguments.of("1 + 3 + 5 != 9", Map.of(), false, BigDecimalNumberRange.INFINITE),
			/* 30 */ Arguments.of("-1.0 < -1.11", Map.of(), false, BigDecimalNumberRange.to(new BigDecimal("-1.1100000000000001"))),
			/* 31 */ Arguments.of("-1.0 > -1.11", Map.of(), true, BigDecimalNumberRange.from(new BigDecimal("-1.1099999999999999"))),
			/* 32 */ Arguments.of("-1 < +1", Map.of(), true, BigDecimalNumberRange.to(new BigDecimal("-1.0000000000000001"))),
			/* 33 */ Arguments.of("-(1 + 2) < +1", Map.of(), true, BigDecimalNumberRange.to(new BigDecimal("-3.0000000000000001"))),
			/* 34 */ Arguments.of("2 - 5 > +1", Map.of(), false, BigDecimalNumberRange.from(new BigDecimal("-2.9999999999999999"))),
			/* 35 */ Arguments.of("2 * (8 - 4) == 8", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("8"), new BigDecimal("8"))),
			/* 36 */ Arguments.of("pow(2, 6) == 64", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("64"), new BigDecimal("64"))),
			/* 37 */ Arguments.of("(2 + 4) * 2 == (8 - 2) * 2", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("12"), new BigDecimal("12"))),
			/* 38 */ Arguments.of("random(5) > 8", Map.of(), false, BigDecimalNumberRange.from(new BigDecimal("8.0000000000000001"))),
			/* 39 */ Arguments.of("random() > 0 && 5 > 7", Map.of(), false, BigDecimalNumberRange.from(new BigDecimal("5.0000000000000001"))),
			/* 40 */ Arguments.of("max(4, 8) > 5", Map.of(), true, BigDecimalNumberRange.from(new BigDecimal("4.0000000000000001"))),
			/* 41 */ Arguments.of("max(4, 8) < 5", Map.of(), false, BigDecimalNumberRange.to(new BigDecimal("4.9999999999999999"))),
			/* 42 */ Arguments.of("min(4, 8) > 5", Map.of(), false, BigDecimalNumberRange.from(new BigDecimal("4.0000000000000001"))),
			/* 43 */ Arguments.of("min(4, 8) < 5", Map.of(), true, BigDecimalNumberRange.to(new BigDecimal("4.9999999999999999"))),
			/* 44 */ Arguments.of("abs(-4) == 4", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("4"), new BigDecimal("4"))),
			/* 45 */ Arguments.of("round(log(20)) == 3", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("3"), new BigDecimal("3"))),
			/* 46 */ Arguments.of("round(2.4) == 2", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("2"), new BigDecimal("2"))),
			/* 47 */ Arguments.of("round(2.5) == 3", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("3"), new BigDecimal("3"))),
			/* 48 */ Arguments.of("sqrt(3 + 13) == 4", Map.of(), true, BigDecimalNumberRange.between(new BigDecimal("4"), new BigDecimal("4"))),
			/* 49 */ Arguments.of("$pageNumber > 5", Map.of("pageNumber", BigDecimal.valueOf(10)), true, BigDecimalNumberRange.from(new BigDecimal("5.0000000000000001"))),
			/* 50 */ Arguments.of("$pageNumber > 5", Map.of("pageNumber", BigDecimal.valueOf(1)), false, BigDecimalNumberRange.from(new BigDecimal("5.0000000000000001"))),
			/* 51 */ Arguments.of("$pageNumber > 5 ", Map.of("pageNumber", BigDecimal.valueOf(1)), false, BigDecimalNumberRange.from(new BigDecimal("5.0000000000000001"))),
			/* 52 */ Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(1)), false, BigDecimalNumberRange.from(new BigDecimal("2.0000000000000001"))),
			/* 53 */ Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(2)), false, BigDecimalNumberRange.from(new BigDecimal("2.0000000000000001"))),
			/* 54 */ Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(3)), false, BigDecimalNumberRange.from(new BigDecimal("2.0000000000000001"))),
			/* 55 */ Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(4)), true, BigDecimalNumberRange.from(new BigDecimal("2.0000000000000001"))),
			/* 56 */ Arguments.of("ceil($pageNumber / 2) == 3", Map.of("pageNumber", BigDecimal.valueOf(5)), true, BigDecimalNumberRange.INFINITE),
			/* 57 */ Arguments.of("floor($pageNumber / 2) == 2", Map.of("pageNumber", BigDecimal.valueOf(5)), true, BigDecimalNumberRange.INFINITE),
			/* 58 */ Arguments.of("floor(sqrt($pageNumber)) == 2", Map.of("pageNumber", BigDecimal.valueOf(4)), true, BigDecimalNumberRange.INFINITE),
			/* 59 */ Arguments.of("floor(sqrt($pageNumber)) == 2", Map.of("pageNumber", BigDecimal.valueOf(5)), true, BigDecimalNumberRange.INFINITE),
			/* 60 */ Arguments.of("floor(sqrt($pageNumber)) == 2", Map.of("pageNumber", BigDecimal.valueOf(16)), false, BigDecimalNumberRange.INFINITE),
			/* 61 */ Arguments.of("$pageNumber >= 5", Map.of("pageNumber", BigDecimal.valueOf(10)), true, BigDecimalNumberRange.from(new BigDecimal("5"))),
			/* 62 */ Arguments.of("$pageNumber < 5", Map.of("pageNumber", BigDecimal.valueOf(10)), false, BigDecimalNumberRange.to(new BigDecimal("4.9999999999999999"))),
			/* 63 */ Arguments.of("$pageNumber <= 5", Map.of("pageNumber", BigDecimal.valueOf(10)), false, BigDecimalNumberRange.to(new BigDecimal("5"))),
			/* 64 */ Arguments.of("$pageNumber <= 5 && $pageNumber % 2 == 0", Map.of("pageNumber", BigDecimal.valueOf(10)), false, BigDecimalNumberRange.to(new BigDecimal("5"))),
			/* 65 */ Arguments.of("$pageNumber <= 5 && $pageNumber >= 2", Map.of("pageNumber", BigDecimal.valueOf(10)), false, BigDecimalNumberRange.between(new BigDecimal("2"), new BigDecimal("5"))),
			/* 66 */ Arguments.of("$pageNumber <= 5 || $pageNumber >= 2", Map.of("pageNumber", BigDecimal.valueOf(10)), true, BigDecimalNumberRange.INFINITE),
			/* 67 */ Arguments.of("$pageNumber <= 4 || $pageNumber >= 5", Map.of("pageNumber", BigDecimal.valueOf(10)), true, BigDecimalNumberRange.INFINITE),
			/* 68 */ Arguments.of("!($pageNumber <= 13)", Map.of("pageNumber", BigDecimal.valueOf(10)), false, BigDecimalNumberRange.from(new BigDecimal("13.0000000000000001"))),
			/* 69 */ Arguments.of("$pageNumber > 2 && $pageNumber < 10 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(1)), false, BigDecimalNumberRange.between(new BigDecimal("2.0000000000000001"), new BigDecimal("9.9999999999999999"))),
			/* 70 */ Arguments.of("$pageNumber > 2 || $pageNumber < 10 || $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(1)), true, BigDecimalNumberRange.INFINITE),
	        /* Properties retrieval */
			Arguments.of("$obj.prop", Map.of("obj", new TestObject()), "basic property", BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.optionalProp", Map.of("obj", new TestObject()), null, BigDecimalNumberRange.INFINITE),
			/* Array retrieval */
			Arguments.of("$obj.arr[1]", Map.of("obj", new TestObject()), BigDecimal.ONE, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.arrWithMissingValues[0]?.attribute", Map.of("obj", new TestObject()), "basic attribute", BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.arrWithMissingValues[1]?.attribute", Map.of("obj", new TestObject()), null, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.optionalArr?[0]", Map.of("obj", new TestObject()), null, BigDecimalNumberRange.INFINITE),
			/* List retrieval */
			Arguments.of("$obj.list[2]", Map.of("obj", new TestObject()), 300, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.listWithMissingValues[0]?.attribute", Map.of("obj", new TestObject()), "basic attribute", BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.listWithMissingValues[1]?.attribute", Map.of("obj", new TestObject()), null, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.optionalList?[0]", Map.of("obj", new TestObject()), null, BigDecimalNumberRange.INFINITE),
	        /* Map retrieval */
			Arguments.of("$obj.map['a']", Map.of("obj", new TestObject()), 5, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.mapWithMissingValues['a']?.attribute", Map.of("obj", new TestObject()), "basic attribute", BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.mapWithMissingValues['b']?.list[0]", Map.of("obj", new TestObject()), null, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.mapWithPrimitiveMissingValues *? 10", Map.of("obj", new TestObject()), Map.of("a", 1L, "b", 10L), BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.optionalMap?['b']", Map.of("obj", new TestObject()), null, BigDecimalNumberRange.INFINITE),
			/* Nested data retrieval */
			Arguments.of("$obj.nested.list[1]", Map.of("obj", new TestObject()), 200, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.nested.map['a']", Map.of("obj", new TestObject()), 5, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.nested.map['c'].prop", Map.of("obj", new TestObject()), "basic property", BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.nested.map['d'][0]", Map.of("obj", new TestObject()), 1000, BigDecimalNumberRange.INFINITE),
			Arguments.of("$obj.optionalNested?.map['c'].list[0]", Map.of("obj", new TestObject()), null, BigDecimalNumberRange.INFINITE),
	        /* Spread mapping */
	        Arguments.of("$obj.objectList.*[$.attribute]", Map.of("obj", new TestObject()), List.of("basic attribute", "basic attribute"), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.objectList.*[$.originalNested.nested.map['a']]", Map.of("obj", new TestObject()), List.of(5, 5), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.objectList.*[$.originalNested.optionalNested?.map['a']]", Map.of("obj", new TestObject()), list().i(null).i(null).build(), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.objectListWithMissingValues.*[$?.attribute]", Map.of("obj", new TestObject()), list().i("basic attribute").i(null).build(), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.objectListWithMissingValues.*![$?.attribute]", Map.of("obj", new TestObject()), List.of("basic attribute"), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.objectListWithMissingValues.*[$?.originalNested.nested.map['a']]", Map.of("obj", new TestObject()), list().i(5).i(null).build(), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.objectListWithMissingValues.*[$?.originalNested.nested.map['a'] ?? 10]", Map.of("obj", new TestObject()), list().i(5).i(10L).build(), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.objectListWithMissingValues.*[$?.attribute] *? 10", Map.of("obj", new TestObject()), list().i("basic attribute").i(10L).build(), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.mapWithPrimitiveValues.*[$]", Map.of("obj", new TestObject()), Map.of("a", 5, "b", 6, "c", 7, "d", 8), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.mapWithPrimitiveValues.*[max($, 7)]", Map.of("obj", new TestObject()), Map.of("a", BigDecimal.valueOf(7L), "b", BigDecimal.valueOf(7L), "c", BigDecimal.valueOf(7L), "d", BigDecimal.valueOf(8L)), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.mapWithPrimitiveValues.entries.*[$.key]", Map.of("obj", new TestObject()), List.of("a", "b", "c", "d"), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.mapWithPrimitiveValues.entries.*[$.value]", Map.of("obj", new TestObject()), List.of(5, 6, 7, 8), BigDecimalNumberRange.INFINITE),
	        Arguments.of("$obj.mapWithPrimitiveValues.entries.*[min($.value, 6)]", Map.of("obj", new TestObject()), List.of(BigDecimal.valueOf(5L), BigDecimal.valueOf(6L), BigDecimal.valueOf(6L), BigDecimal.valueOf(6L)), BigDecimalNumberRange.INFINITE)
		);
	}

	@Nonnull
	static Stream<Arguments> invalidExpressions() {
		return Stream.of(
			Arguments.of("$obj.optionalArr[0]", Map.of("obj", new TestObject())),
			Arguments.of("$obj.optionalList[0]", Map.of("obj", new TestObject())),
			Arguments.of("$obj.optionalMap['b']", Map.of("obj", new TestObject())),
			Arguments.of("$obj.optionalNested.map['c'].list[0]", Map.of("obj", new TestObject())),
			Arguments.of("$obj.mapWithMissingValues['b'].list[0]", Map.of("obj", new TestObject()))
		);
	}

	@Nullable
	private static Serializable evaluate(@Nonnull String expression, @Nonnull Map<String, Object> variables) {
		final ExpressionNode operator = ExpressionFactory.parse(expression);
		return operator.compute(new MultiVariableEvaluationContext(42, variables), Serializable.class);
	}

	@Nonnull
	private static BigDecimalNumberRange determineRange(@Nonnull String predicate) {
		final ExpressionNode operator = ExpressionFactory.parse(predicate);
		return operator.determinePossibleRange();
	}

	private static class TestObject implements Serializable {
		@Serial private static final long serialVersionUID = -7219505286689232042L;

		@Nonnull
		public String prop() {
			return "basic property";
		};

		@Nullable
		public String optionalProp() {
			return null;
		}

		@Nonnull
		public BigDecimal[] arr() {
			return new BigDecimal[] {BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO};
		}

		@Nullable
		public BigDecimal[] optionalArr() {
			return null;
		}

		@Nonnull
		public NestedTestObject[] arrWithMissingValues() {
			return new NestedTestObject[] {
				new NestedTestObject(),
				null
			};
		}

		@Nonnull
		public List<Integer> list() {
			return Arrays.asList(100, 200, 300);
		}

		@Nullable
		public List<Integer> optionalList() {
			return null;
		}

		@Nonnull
		public List<NestedTestObject> listWithMissingValues() {
			final List<NestedTestObject> list = new ArrayList<>();
			list.add(new NestedTestObject());
			list.add(null);
			list.add(new NestedTestObject());
			return list;
		}

		@Nonnull
		public Map<String, Object> map() {
			return Map.of(
				"a", 5,
				"b", 6,
				"c", new NestedTestObject(),
				"d", List.of(1000, 2000, 3000)
			);
		}

		@Nonnull
		public Map<String, Object> mapWithPrimitiveValues() {
			// we want to preserve order of entries for easier testing
			final Map<String, Object> map = new LinkedHashMap<>();
			map.put("a", 5);
			map.put("b", 6);
			map.put("c", 7);
			map.put("d", 8);
			return map;
		}

		@Nonnull
		public Map<String, Object> mapWithMissingValues() {
			final Map<String, Object> map = new HashMap<>();
			map.put("a", new NestedTestObject());
			map.put("b", null);
			return map;
		}

		@Nonnull
		public Map<String, Object> mapWithPrimitiveMissingValues() {
			final Map<String, Object> map = new HashMap<>();
			map.put("a", 1L);
			map.put("b", null);
			return map;
		}

		@Nullable
		public Map<String, Object> optionalMap() {
			return null;
		}

		@Nonnull
		public NestedTestObject nested() {
			return new NestedTestObject();
		}

		@Nullable
		public NestedTestObject optionalNested() {
			return null;
		}

		@Nonnull
		public List<NestedTestObject> objectList() {
			return Arrays.asList(new NestedTestObject(), new NestedTestObject());
		}

		@Nonnull
		public List<NestedTestObject> objectListWithMissingValues() {
			final List<NestedTestObject> list = new ArrayList<>();
			list.add(new NestedTestObject());
			list.add(null);
			return list;
		}
	}

	private static class TestObjectAccessor implements ObjectPropertyAccessor {

		@Nonnull
		@Override
		public Class<? extends Serializable>[] getSupportedTypes() {
			//noinspection unchecked
			return new Class[] { TestObject.class };
		}

		@Nullable
		@Override
		public Serializable get(
			@Nonnull Serializable object,
			@Nonnull String propertyIdentifier
		) throws ExpressionEvaluationException {
			final TestObject testObject = ((TestObject) object);
			return switch (propertyIdentifier) {
				case "prop" -> testObject.prop();
				case "optionalProp" -> testObject.optionalProp();
				case "arr" -> testObject.arr();
				case "optionalArr" -> testObject.optionalArr();
				case "arrWithMissingValues" -> testObject.arrWithMissingValues();
				case "list" -> (Serializable) testObject.list();
				case "optionalList" -> (Serializable) testObject.optionalList();
				case "listWithMissingValues" -> (Serializable) testObject.listWithMissingValues();
				case "map" -> (Serializable) testObject.map();
				case "mapWithPrimitiveValues" -> (Serializable) testObject.mapWithPrimitiveValues();
				case "optionalMap" -> (Serializable) testObject.optionalMap();
				case "mapWithMissingValues" -> (Serializable) testObject.mapWithMissingValues();
				case "mapWithPrimitiveMissingValues" -> (Serializable) testObject.mapWithPrimitiveMissingValues();
				case "nested" -> testObject.nested();
				case "optionalNested" -> testObject.optionalNested();
				case "objectList" -> (Serializable) testObject.objectList();
				case "objectListWithMissingValues" -> (Serializable) testObject.objectListWithMissingValues();
				default -> throw new IllegalArgumentException("Unknown property: " + propertyIdentifier);
			};
		}
	}

	private static class NestedTestObject implements Serializable {

		@Serial private static final long serialVersionUID = -1193179128145431094L;

		@Nonnull
		public String attribute() {
			return "basic attribute";
		};

		@Nonnull
		public List<Integer> list() {
			return Arrays.asList(100, 200, 300);
		}

		@Nonnull
		public Map<String, Object> map() {
			return Map.of(
				"a", 5,
				"b", 6,
				"c", new TestObject(),
				"d", List.of(1000, 2000, 3000)
			);
		}

		@Nonnull
		public TestObject originalNested() {
			return new TestObject();
		}
	}

	private static class NestedTestObjectAccessor implements ObjectPropertyAccessor {

		@Nonnull
		@Override
		public Class<? extends Serializable>[] getSupportedTypes() {
			//noinspection unchecked
			return new Class[] { NestedTestObject.class };
		}

		@Nullable
		@Override
		public Serializable get(
			@Nonnull Serializable object,
			@Nonnull String propertyIdentifier
		) throws ExpressionEvaluationException {
			final NestedTestObject nestedTestObject = ((NestedTestObject) object);
			return switch (propertyIdentifier) {
				case "attribute" -> nestedTestObject.attribute();
				case "list" -> (Serializable) nestedTestObject.list();
				case "map" -> (Serializable) nestedTestObject.map();
				case "originalNested" -> nestedTestObject.originalNested();
				default -> throw new IllegalArgumentException("Unknown property: " + propertyIdentifier);
			};
		}
	}
}
