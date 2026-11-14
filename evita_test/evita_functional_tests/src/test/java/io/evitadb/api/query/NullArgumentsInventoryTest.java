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

package io.evitadb.api.query;

import io.evitadb.api.query.require.*;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Inventories every public factory so new overloads must honor the null argument contract.
 */
@DisplayName("Query factory null argument inventory")
@Tag(CONTRACT)
@Tag(QUERY)
class NullArgumentsInventoryTest {
	private static final List<Method> FACTORIES = Arrays.stream(QueryConstraints.class.getDeclaredMethods())
		.filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()))
		.filter(method -> Constraint.class.isAssignableFrom(method.getReturnType()))
		.sorted(Comparator.comparing(Method::toGenericString)).toList();
	private static final Map<Class<?>, Constraint<?>> CONSTRAINT_DUMMIES = createHashMap(32);
	private static final Set<String> VALUE_STRINGS = Set.of("textToSearch", "currency", "expression");
	private static final Set<String> REQUIRED_ENUMS = Set.of("inScope", "attributeIs", "priceContent");
	private static final List<Map.Entry<String, String>> BLANK_NAMES = List.of(
		Map.entry("empty", ""), Map.entry("space", " "), Map.entry("tab and newline", "\t\n")
	);
	private static final Set<String> VALUE_LISTS = Set.of(
		"attributeInSet", "entityPrimaryKeyInSet", "priceInPriceLists", "attributeSetExact",
		"entityPrimaryKeyExact", "priceDiscount", "defaultAccompanyingPriceLists", "accompanyingPriceContent",
		"priceContent", "priceContentRespectingFilter", "dataInLocales"
	);
	private static final Set<String> FILTER_VALUE_LISTS = Set.of(
		"attributeInSet", "entityPrimaryKeyInSet", "priceInPriceLists"
	);

	/**
	 * Checks mandatory names and optional enum defaults across all overloads.
	 */
	@Nested
	@DisplayName("Scalar names and enum defaults")
	class Scalars {
		/**
		 * Exercises null names and every scalar enum in every factory overload.
		 *
		 * @return one regression case per scalar parameter
		 */
		@TestFactory
		@DisplayName("Names are mandatory and enum nulls select a declared default")
		Stream<DynamicTest> shouldEnforceScalarRulesWhenAnyFactoryReceivesNull() {
			return factories().flatMap(method -> {
				final List<DynamicTest> tests = new ArrayList<>();
				final Parameter[] parameters = method.getParameters();
				for (int i = 0; i < parameters.length; i++) {
					final int index = i;
					final Parameter parameter = parameters[i];
					final Class<?> type = parameter.getType();
					if (type == String.class && !VALUE_STRINGS.contains(parameter.getName())) {
						tests.add(DynamicTest.dynamicTest(method + " null " + parameter.getName(), () -> {
							final Object[] arguments = arguments(method, 0);
							arguments[index] = null;
							assertThrows(EvitaInvalidUsageException.class, () -> invoke(method, arguments));
						}));
						for (final Map.Entry<String, String> blank : BLANK_NAMES) {
							tests.add(DynamicTest.dynamicTest(
								method + " " + blank.getKey() + " " + parameter.getName(), () -> {
								final Object[] arguments = arguments(method, 0);
								arguments[index] = blank.getValue();
								assertThrows(EvitaInvalidUsageException.class, () -> invoke(method, arguments));
							}));
						}
					} else if (type.isEnum()) {
						tests.add(DynamicTest.dynamicTest(method + " default " + parameter.getName(), () -> {
							final Object[] arguments = arguments(method, 0);
							arguments[index] = null;
							if (REQUIRED_ENUMS.contains(method.getName())) {
								assertThrows(EvitaInvalidUsageException.class, () -> invoke(method, arguments));
							} else {
								final Object actual = invoke(method, arguments);
								assertNotNull(actual, "An enum with a default must retain the constraint");
								final Class<?>[] withoutEnum = Stream.concat(
									Arrays.stream(method.getParameterTypes()).limit(index),
									Arrays.stream(method.getParameterTypes()).skip(index + 1)).toArray(Class<?>[]::new);
								try {
									final Method overload = QueryConstraints.class.getMethod(
										method.getName(), withoutEnum);
									final Object[] withoutArgument = Stream.concat(
										Arrays.stream(arguments).limit(index),
										Arrays.stream(arguments).skip(index + 1)
									).toArray();
									assertEquals(invoke(overload, withoutArgument), actual);
								} catch (NoSuchMethodException exception) {
									arguments[index] = documentedDefault(method, parameter);
									assertEquals(invoke(method, arguments), actual,
										"Null must select the documented enum default");
								}
							}
						}));
					}
				}
				return tests.stream();
			});
		}
	}

	/**
	 * Checks missing lists and null items without losing the array component type.
	 */
	@Nested
	@DisplayName("Array parameters")
	class Lists {
		/**
		 * Compares missing, empty, mixed and all-null lists with their specified canonical forms.
		 *
		 * @return regression cases for every non-constraint array parameter
		 */
		@TestFactory
		@DisplayName("Every value, name and enum list follows its null policy")
		Stream<DynamicTest> shouldNormalizeListsWhenAnyFactoryReceivesUndefinedItems() {
			return factories().flatMap(method -> {
				final List<DynamicTest> tests = new ArrayList<>();
				final Parameter[] parameters = method.getParameters();
				for (int i = 0; i < parameters.length; i++) {
					final int index = i;
					final Class<?> type = parameters[i].getType();
					if (!type.isArray() || Constraint.class.isAssignableFrom(type.getComponentType())) {
						continue;
					}
					final Class<?> component = type.getComponentType();
					if (component == String.class) {
						for (final String shape : List.of("mixed", "leading", "only")) {
							tests.add(DynamicTest.dynamicTest(method + " blank list " + shape, () -> {
								final Object[] arguments = arguments(method, 0);
								final Object value = dummy(component, parameters[index].getName(), 0);
								arguments[index] = shape.equals("mixed") ? array(component, value, " ") :
									shape.equals("leading") ? array(component, " ", value) : array(component, "", " ");
								if (!VALUE_LISTS.contains(method.getName())) {
									assertThrows(EvitaInvalidUsageException.class, () -> invoke(method, arguments));
								} else {
									final Object actual = invoke(method, arguments);
									arguments[index] = shape.equals("only") ?
										array(component) : array(component, value);
									assertEquals(invoke(method, arguments), actual);
								}
							}));
						}
					}
					for (final String shape : component.isPrimitive() ? List.of("missing", "empty") :
						List.of("missing", "empty", "value then null", "null then value", "only nulls")) {
						tests.add(DynamicTest.dynamicTest(method + " " + parameters[i].getName() + " " + shape, () -> {
							final Object value = dummy(component, parameters[index].getName(), 0);
							final Object[] arguments = arguments(method, 0);
							arguments[index] = switch (shape) {
								case "missing" -> null;
								case "empty" -> array(component);
								case "value then null" -> array(component, value, null);
								case "null then value" -> array(component, null, value);
								case "only nulls" -> array(component, null, null);
								default -> throw new IllegalStateException("Unknown list shape");
							};
							final boolean hasNullItem = shape.contains("null");
							if (hasNullItem && !VALUE_LISTS.contains(method.getName())) {
								assertThrows(EvitaInvalidUsageException.class, () -> invoke(method, arguments));
							} else if (shape.equals("missing") && FILTER_VALUE_LISTS.contains(method.getName())) {
								assertNull(invoke(method, arguments));
							} else {
								final Object actual = invoke(method, arguments);
								arguments[index] = shape.contains("value") ? array(component, value) : array(component);
								assertEquals(invoke(method, arguments), actual);
							}
						}));
					}
				}
				return tests.stream();
			});
		}
	}

	/**
	 * Checks omitted children in every array position, preserving usable siblings.
	 */
	@Nested
	@DisplayName("Child arrays")
	class Children {
		/**
		 * Compares mixed arrays with a single usable child and rejects no valid omission.
		 *
		 * @return child-array cases for all public factories
		 */
		@TestFactory
		@DisplayName("Null children leave valid siblings intact")
		Stream<DynamicTest> shouldPreserveChildrenWhenAnArrayContainsNull() {
			return factories().flatMap(method -> {
				final List<DynamicTest> tests = new ArrayList<>();
				for (int i = 0; i < method.getParameterCount(); i++) {
					final int index = i;
					final Class<?> type = method.getParameterTypes()[i];
					if (!type.isArray() || !Constraint.class.isAssignableFrom(type.getComponentType())) continue;
					for (final boolean nullFirst : List.of(false, true)) {
						tests.add(DynamicTest.dynamicTest(method + " child " + index + " " + nullFirst, () -> {
							final Object[] arguments = arguments(method, 0);
							final Object child = method.getName().equals("hierarchyWithinRoot") ?
								QueryConstraints.excluding(QueryConstraints.entityPrimaryKeyInSet(1)) :
								dummy(type.getComponentType(), "child", 0);
							arguments[index] = array(type.getComponentType(), child);
							final Object expected = invoke(method, arguments);
							arguments[index] = nullFirst ? array(type.getComponentType(), null, child) :
								array(type.getComponentType(), child, null);
							assertEquals(expected, invoke(method, arguments));
						}));
					}
				}
				return tests.stream();
			});
		}
	}

	/**
	 * Verifies that absent children never produce non-applicable containers.
	 */
	@Nested
	@DisplayName("Empty containers")
	class Containers {
		/**
		 * Probes every container factory with all children undefined.
		 *
		 * @return cases per public container factory
		 */
		@TestFactory
		@DisplayName("Empty containers omit or retain an applicable documented default")
		Stream<DynamicTest> shouldOmitNonApplicableContainersWhenAllChildrenAreUndefined() {
			return factories().filter(method -> ConstraintContainer.class.isAssignableFrom(method.getReturnType()))
				.map(method -> DynamicTest.dynamicTest(method.toString(), () -> {
					final Object[] arguments = arguments(method, 0);
					for (int i = 0; i < arguments.length; i++) {
						final Class<?> type = method.getParameterTypes()[i];
						if (Constraint.class.isAssignableFrom(type)) arguments[i] = null;
						if (type.isArray() && Constraint.class.isAssignableFrom(type.getComponentType())) {
							arguments[i] = array(type.getComponentType(), null, null);
						}
					}
					final Constraint<?> result = (Constraint<?>) invoke(method, arguments);
					assertTrue(result == null || result.isApplicable(), "Non-applicable container must be omitted");
				}));
		}
	}

	/**
	 * Supplies documented defaults independently of the factory implementation.
	 *
	 * @param method factory under test
	 * @param parameter enum setting
	 * @return documented default enum constant
	 */
	@Nonnull
	private static Object documentedDefault(@Nonnull Method method, @Nonnull Parameter parameter) {
		final String constant = switch (parameter.getType().getSimpleName()) {
			case "OrderDirection" -> method.getName().equals("priceDiscount") ? "DESC" : "ASC";
			case "TraversalMode" -> "DEPTH_FIRST";
			case "EmptyHierarchicalEntityBehaviour" -> "REMOVE_EMPTY";
			case "FacetGroupRelationLevel" -> method.getName().equals("facetGroupsDisjunction") ?
				"WITH_DIFFERENT_GROUPS" : "WITH_DIFFERENT_FACETS_IN_GROUP";
			case "FacetRelationType" -> parameter.getName().equals("facetsWithSameGroup") ?
				"DISJUNCTION" : "CONJUNCTION";
			case "FacetStatisticsDepth" -> "COUNTS";
			case "HierarchyParentsBehaviour" -> "MATCHING";
			case "HistogramBehavior" -> "STANDARD";
			case "ManagedReferencesBehaviour" -> "ANY";
			case "QueryPriceMode" -> "WITH_TAX";
			case "QueryTelemetryContent" -> "TIMINGS";
			case "StatisticsBase" -> "WITHOUT_USER_FILTER";
			default -> throw new IllegalStateException("Missing documented default for " + parameter);
		};
		return Arrays.stream(parameter.getType().getEnumConstants())
			.filter(value -> ((Enum<?>) value).name().equals(constant)).findFirst().orElseThrow();
	}

	/**
	 * Finds the public constraint factories, including overloads added in the future.
	 *
	 * @return sorted factories
	 */
	@Nonnull
	private static Stream<Method> factories() {
		return FACTORIES.stream();
	}

	/**
	 * Creates representative arguments for a factory.
	 *
	 * @param method factory being exercised
	 * @param depth recursion depth for optional child factories
	 * @return arguments in declaration order
	 */
	@Nonnull
	private static Object[] arguments(@Nonnull Method method, int depth) {
		return Arrays.stream(method.getParameters())
			.map(parameter -> dummy(parameter.getType(), parameter.getName(), depth))
			.toArray();
	}

	/**
	 * Constructs a typed array without losing its runtime component type.
	 *
	 * @param component array component type
	 * @param items array contents
	 * @return typed array
	 */
	@Nonnull
	private static Object array(@Nonnull Class<?> component, @Nullable Object... items) {
		final Object array = Array.newInstance(component, items.length);
		for (int i = 0; i < items.length; i++) {
			Array.set(array, i, items[i]);
		}
		return array;
	}

	/**
	 * Invokes a factory and exposes its original client exception to assertions.
	 *
	 * @param method factory to invoke
	 * @param arguments supplied arguments
	 * @return factory result
	 * @throws Throwable the factory's original exception or a reflection failure
	 */
	@Nullable
	private static Object invoke(@Nonnull Method method, @Nonnull Object[] arguments) throws Throwable {
		try {
			return method.invoke(null, arguments);
		} catch (InvocationTargetException exception) {
			throw exception.getCause();
		}
	}

	/**
	 * Creates a valid representative value, recursively using real factories for child constraints.
	 *
	 * @param type required type
	 * @param name parameter name, used for string conversion inputs
	 * @param depth recursion depth
	 * @return a representative value, or null for an optional child at the recursion limit
	 */
	@Nullable
	private static Object dummy(@Nonnull Class<?> type, @Nonnull String name, int depth) {
		if (type.isArray()) {
			return array(type.getComponentType(), dummy(type.getComponentType(), name, depth));
		}
		if (type == Expression.class) return ExpressionFactory.parse("true");
		if (type == String.class) {
			return name.equals("currency") ? "EUR" : name.equals("expression") ? "true" : "code";
		}
		if (type == int.class || type == Integer.class) return 1;
		if (type == long.class || type == Long.class) return 1L;
		if (type == boolean.class || type == Boolean.class) return Boolean.TRUE;
		if (type == BigDecimal.class || type == Number.class) return BigDecimal.ONE;
		if (type == Locale.class) return Locale.ENGLISH;
		if (type == Currency.class) return Currency.getInstance("EUR");
		if (type == OffsetDateTime.class) return OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		if (type.isEnum()) return type.getEnumConstants()[0];
		if (type == Serializable.class || type == Comparable.class || type == Object.class) return "value";
		if (Constraint.class.isAssignableFrom(type)) {
			if (CONSTRAINT_DUMMIES.containsKey(type)) return CONSTRAINT_DUMMIES.get(type);
			if (depth > 3) return null;
			final List<Method> candidates = factories().filter(method -> type.isAssignableFrom(method.getReturnType()))
				.sorted(Comparator.comparingInt(Method::getParameterCount)).toList();
			for (final Method candidate : candidates) {
				try {
					final Constraint<?> result = (Constraint<?>) candidate.invoke(null, arguments(candidate,
						depth + 1));
					if (result != null && result.isApplicable()) {
						CONSTRAINT_DUMMIES.put(type, result);
						return result;
					}
				} catch (ReflectiveOperationException exception) {
					// Some overloads require a specific combination; try the next real factory.
				}
			}
			fail("No representative constraint factory for " + type);
		}
		throw new IllegalStateException("No representative value for " + type);
	}
}
