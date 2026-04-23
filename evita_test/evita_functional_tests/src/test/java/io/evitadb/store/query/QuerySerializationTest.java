/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.query;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.filter.HistogramHaving;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Kryo round-trip tests for every constraint surface exposed by
 * {@link io.evitadb.api.query.QueryConstraints}. Each nested class groups a single constraint family
 * (mirroring the `io.evitadb.api.query.{filter,order,require}` package layout) and each
 * `@ParameterizedTest` row corresponds to one argument permutation. A failure therefore points at the
 * exact variant whose serializer drifted — not an entire "all filtering constraints" mega-method.
 *
 * Adding a new constraint permutation is a single `arguments("name", constraint)` row inside the
 * relevant `variants()` source method — no new `@Test` method, no duplicate Kryo wiring.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Query — Kryo serialization round-trip")
public class QuerySerializationTest {
	/**
	 * Fully-qualified name of the {@code HistogramHavingSerializer} class. The serializer lives
	 * in a non-exported sub-package of the {@code evita_store_server} module
	 * (`io.evitadb.store.query.serializer.filter`), so it cannot be referenced directly from
	 * the test module. Reflection sidesteps the package boundary for this single local fixture.
	 */
	private static final String HISTOGRAM_HAVING_SERIALIZER_FQN =
		"io.evitadb.store.query.serializer.filter.HistogramHavingSerializer";

	/**
	 * Kryo registration ID reserved for {@link HistogramHaving} inside this test. The shared
	 * {@link QuerySerializationKryoConfigurer} does not yet register {@link HistogramHaving}, so
	 * every round-trip exercised here must register the serializer locally. The ID sits safely
	 * below the configurer's upper-bound assertion (`index < 2000`) and well above every index
	 * the configurer itself currently consumes, so no collision is possible.
	 */
	private static final int HISTOGRAM_HAVING_LOCAL_ID = 1990;

	/**
	 * Kryo instance pre-configured with the shared {@link QuerySerializationKryoConfigurer} and
	 * then augmented with a test-local registration of the {@code HistogramHavingSerializer}.
	 * The local registration is a bridge: it lets us exercise the serializer's round-trip shape
	 * before the permanent registration is appended to the configurer. The serializer is
	 * instantiated reflectively because its package is not exported to this test module.
	 */
	private final Kryo kryo = KryoFactory.createKryo(
		QuerySerializationKryoConfigurer.INSTANCE
			.andThen((Consumer<Kryo>) k -> k.register(
				HistogramHaving.class,
				instantiateHistogramHavingSerializer(),
				HISTOGRAM_HAVING_LOCAL_ID
			))
	);

	/**
	 * Reflectively instantiates the {@code HistogramHavingSerializer} from the non-exported
	 * package. The serializer has a no-arg constructor (generated by Lombok
	 * {@code @RequiredArgsConstructor} on an empty field list), so we load the class, grab that
	 * constructor, and call it. Any reflective failure is rethrown as an
	 * {@link IllegalStateException} so the test class fails loudly rather than silently
	 * skipping the round-trip.
	 *
	 * @return a fresh serializer instance
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static Serializer<HistogramHaving> instantiateHistogramHavingSerializer() {
		try {
			final Class<?> serializerClass = Class.forName(HISTOGRAM_HAVING_SERIALIZER_FQN);
			final Constructor<?> noArg = serializerClass.getDeclaredConstructor();
			noArg.setAccessible(true);
			return (Serializer<HistogramHaving>) noArg.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(
				"Failed to reflectively instantiate HistogramHavingSerializer at "
					+ HISTOGRAM_HAVING_SERIALIZER_FQN,
				e
			);
		}
	}

	/**
	 * Writes `object` with the shared {@link #kryo} instance, reads it back, and asserts the
	 * deserialised value `.equals(...)` the original. Called from every parameterised test row.
	 */
	private void assertSerializationRound(@Nonnull Object object) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.kryo.writeObject(output, object);
		}
		try (final Input input = new Input(os.toByteArray())) {
			final Object deserialized = this.kryo.readObject(input, object.getClass());
			assertEquals(object, deserialized);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// query
	// ---------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("query")
	class Queries {
		@ParameterizedTest(name = "{0}")
		@MethodSource("variants")
		void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
			assertSerializationRound(constraint);
		}

		@Nonnull
		static Stream<Arguments> variants() {
			return Stream.of(
				arguments("collection-only",
					Query.query(collection("a"))),
				arguments("collection + filter",
					Query.query(collection("a"), filterBy(attributeEquals("a", "b")))),
				arguments("collection + filter + order",
					Query.query(collection("a"), filterBy(attributeEquals("a", "b")),
						orderBy(attributeNatural("a", OrderDirection.ASC)))),
				arguments("collection + two orders",
					Query.query(collection("a"),
						orderBy(attributeNatural("a", OrderDirection.ASC),
							attributeNatural("b", OrderDirection.DESC)))),
				arguments("collection + filter + order + require",
					Query.query(collection("a"), filterBy(attributeEquals("a", "b")),
						orderBy(attributeNatural("a", OrderDirection.ASC)),
						require(debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS), entityFetchAll()))),
				arguments("collection + require",
					Query.query(collection("a"),
						require(debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS), entityFetchAll())))
			);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// head
	// ---------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("head")
	class Head {
		@ParameterizedTest(name = "{0}")
		@MethodSource("variants")
		void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
			assertSerializationRound(constraint);
		}

		@Nonnull
		static Stream<Arguments> variants() {
			return Stream.of(
				arguments("collection", collection("a")),
				arguments("label", label("a", "b")),
				arguments("head(collection + labels)", head(collection("a"), label("a", "b"), label("c", "d")))
			);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// filter
	// ---------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("filter")
	class Filtering {

		@Nested
		@DisplayName("logical")
		class Logical {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("filterBy single",
						filterBy(attributeEquals("a", "b"))),
					arguments("filterBy two children",
						filterBy(attributeEquals("a", "b"), attributeIs("d", AttributeSpecialValue.NULL))),
					arguments("and",
						and(attributeEquals("a", "b"), attributeEquals("c", "d"))),
					arguments("or",
						or(attributeEquals("a", "b"), attributeEquals("c", "d"))),
					arguments("not",
						not(attributeEquals("a", "b"))),
					arguments("userFilter",
						userFilter(attributeEquals("a", "b"), priceBetween(BigDecimal.ZERO, BigDecimal.ONE)))
				);
			}
		}

		@Nested
		@DisplayName("entity")
		class Entity {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("primaryKey/inSet",
						entityPrimaryKeyInSet(1, 2, 3)),
					arguments("primaryKey/greaterThan",
						entityPrimaryKeyGreaterThan(5)),
					arguments("primaryKey/greaterThanEquals",
						entityPrimaryKeyGreaterThanEquals(5)),
					arguments("primaryKey/lessThan",
						entityPrimaryKeyLessThan(5)),
					arguments("primaryKey/lessThanEquals",
						entityPrimaryKeyLessThanEquals(5)),
					arguments("primaryKey/between",
						entityPrimaryKeyBetween(5, 10)),
					arguments("primaryKey/between, open lower",
						entityPrimaryKeyBetween(null, 10)),
					arguments("primaryKey/between, open upper",
						entityPrimaryKeyBetween(5, null)),
					arguments("locale/equals",
						entityLocaleEquals(Locale.ENGLISH)),
					arguments("having",
						entityHaving(attributeEquals("a", "b")))
				);
			}
		}

		@Nested
		@DisplayName("attribute")
		class Attribute {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("equals",
						attributeEquals("a", "b")),
					arguments("lessThan",
						attributeLessThan("a", "b")),
					arguments("lessThanEquals",
						attributeLessThanEquals("a", "b")),
					arguments("greaterThan",
						attributeGreaterThan("a", "b")),
					arguments("greaterThanEquals",
						attributeGreaterThanEquals("a", "b")),
					arguments("between (BigDecimal)",
						attributeBetween("a", BigDecimal.ZERO, BigDecimal.ONE)),
					arguments("inRange (OffsetDateTime)",
						attributeInRange("a", OffsetDateTime.now())),
					arguments("inRange (long)",
						attributeInRange("a", 12L)),
					arguments("inRangeNow",
						attributeInRangeNow("a")),
					arguments("inSet",
						attributeInSet("a", "b", "c")),
					arguments("is NULL",
						attributeIs("a", AttributeSpecialValue.NULL)),
					arguments("is NOT_NULL",
						attributeIs("a", AttributeSpecialValue.NOT_NULL)),
					arguments("contains",
						attributeContains("a", "b")),
					arguments("startsWith",
						attributeStartsWith("a", "b")),
					arguments("endsWith",
						attributeEndsWith("a", "b"))
				);
			}
		}

		@Nested
		@DisplayName("hierarchy")
		class Hierarchy {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("within",
						hierarchyWithin("d", attributeEquals("code", "a"))),
					arguments("within + excluding",
						hierarchyWithin("d", attributeEquals("code", "a"),
							excluding(attributeEquals("code", "a")))),
					arguments("within + having",
						hierarchyWithin("d", attributeEquals("code", "a"),
							having(attributeEquals("code", "a")))),
					arguments("within + directRelation",
						hierarchyWithin("d", attributeEquals("code", "a"), directRelation())),
					arguments("within + excludingRoot",
						hierarchyWithin("d", attributeEquals("code", "a"), excludingRoot())),
					arguments("withinRoot",
						hierarchyWithinRoot("d")),
					arguments("withinRoot + excluding",
						hierarchyWithinRoot("d", excluding(attributeEquals("code", "a")))),
					arguments("withinRoot + having",
						hierarchyWithinRoot("d", having(attributeEquals("code", "a"))))
				);
			}
		}

		@Nested
		@DisplayName("scope")
		class Scopes {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("inScope wrapping attribute",
						inScope(Scope.LIVE, attributeEquals("a", "b"))),
					arguments("scope (LIVE, ARCHIVED)",
						scope(Scope.LIVE, Scope.ARCHIVED))
				);
			}
		}

		@Nested
		@DisplayName("facet")
		class Facet {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("facetHaving",
						facetHaving("d", or(attributeEquals("code", "a"), attributeEquals("code", "b"))))
				);
			}
		}

		@Nested
		@DisplayName("price")
		class Price {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("inCurrency",
						priceInCurrency(Currency.getInstance("USD"))),
					arguments("validIn",
						priceValidIn(OffsetDateTime.now())),
					arguments("validInNow",
						priceValidInNow()),
					arguments("inPriceLists",
						priceInPriceLists("basic", "vip")),
					arguments("between",
						priceBetween(BigDecimal.ZERO, BigDecimal.ONE)),
					arguments("between, open lower",
						priceBetween(null, BigDecimal.ONE)),
					arguments("between, open upper",
						priceBetween(BigDecimal.ONE, null))
				);
			}
		}

		@Nested
		@DisplayName("reference")
		class Reference {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("referenceHaving",
						referenceHaving("d", attributeEquals("a", "b")))
				);
			}
		}

		/**
		 * Round-trips every meaningful {@link HistogramHaving} argument permutation — classifier-only,
		 * classifier + histogram name, open lower / upper bound, grouped selector, and each of the four
		 * {@link java.io.Serializable} bound payload types (Integer, Long, {@link BigDecimal}, String).
		 * Exercises the reflectively-registered {@code HistogramHavingSerializer} bridge; see the
		 * enclosing class's {@link #kryo} field for the registration.
		 */
		@Nested
		@DisplayName("histogramHaving")
		class HistogramHavingGroup {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("classifier-only, open lower",
						histogramHaving("parameterValues", null, 120)),
					arguments("classifier-only, open upper",
						histogramHaving("parameterValues", 50, null)),
					arguments("classifier + from/to, Integer",
						histogramHaving("parameterValues", 50, 120)),
					arguments("classifier + from/to, Long",
						histogramHaving("parameterValues", 50L, 120L)),
					arguments("classifier + from/to, BigDecimal",
						histogramHaving("parameterValues", BigDecimal.ZERO, BigDecimal.TEN)),
					arguments("classifier + from/to, String",
						histogramHaving("parameterValues", "A", "Z")),
					arguments("classifier + histogramName + from/to",
						histogramHaving("parameterValues", "basicUnitValue", 50, 120)),
					arguments("classifier + histogramName + from/to, BigDecimal",
						histogramHaving("parameterValues", "basicUnitValue",
							new BigDecimal("50.5"), new BigDecimal("120.75"))),
					arguments("classifier + from/to + groupSelector",
						histogramHaving("parameterValues", 50, 120,
							entityHaving(attributeEquals("code", "height")))),
					arguments("full arity, Integer + groupSelector",
						histogramHaving("parameterValues", "basicUnitValue", 50, 120,
							entityHaving(attributeEquals("code", "height")))),
					arguments("full arity, Long + groupSelector",
						histogramHaving("parameterValues", "basicUnitValue", 50L, 120L,
							entityHaving(attributeEquals("code", "weight")))),
					arguments("full arity, BigDecimal + groupSelector",
						histogramHaving("parameterValues", "basicUnitValue",
							new BigDecimal("50.5"), new BigDecimal("120.75"),
							entityHaving(attributeEquals("code", "depth")))),
					arguments("open lower + histogramName + groupSelector",
						histogramHaving("parameterValues", "basicUnitValue", null, 120,
							entityHaving(attributeEquals("code", "height")))),
					arguments("open upper + histogramName + groupSelector",
						histogramHaving("parameterValues", "basicUnitValue", 50, null,
							entityHaving(attributeEquals("code", "height")))),
					arguments("nested inside userFilter, two siblings",
						userFilter(
							histogramHaving("parameterValues", "basicUnitValue", 50, 120,
								entityHaving(attributeEquals("code", "height"))),
							histogramHaving("parameterValues", "basicUnitValue", 90, 140,
								entityHaving(attributeEquals("code", "weight")))
						))
				);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// order
	// ---------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("order")
	class Ordering {
		@ParameterizedTest(name = "{0}")
		@MethodSource("variants")
		void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
			assertSerializationRound(constraint);
		}

		@Nonnull
		static Stream<Arguments> variants() {
			return Stream.of(
				arguments("orderBy single",
					orderBy(attributeNatural("a", OrderDirection.ASC))),
				arguments("orderBy two",
					orderBy(attributeNatural("a", OrderDirection.ASC),
						attributeNatural("b", OrderDirection.DESC))),
				arguments("attributeNatural",
					attributeNatural("a", OrderDirection.DESC)),
				arguments("attributeSetExact",
					attributeSetExact("a", "b", "c")),
				arguments("attributeSetInFilter",
					attributeSetInFilter("a")),
				arguments("entityPrimaryKeyInFilter",
					entityPrimaryKeyInFilter()),
				arguments("entityPrimaryKeyNatural",
					entityPrimaryKeyNatural(OrderDirection.DESC)),
				arguments("entityPrimaryKeyExact",
					entityPrimaryKeyExact(1, 8, 10, 3)),
				arguments("inScope wrapping attributeNatural",
					inScope(Scope.LIVE, attributeNatural("a", OrderDirection.ASC))),
				arguments("priceNatural",
					priceNatural(OrderDirection.ASC)),
				arguments("random",
					random()),
				arguments("randomWithSeed",
					randomWithSeed(42)),
				arguments("referenceProperty",
					referenceProperty("d", attributeNatural("a", OrderDirection.ASC))),
				arguments("limit",
					limit(10)),
				arguments("segment (orderBy only)",
					segment(orderBy(attributeNatural("a", OrderDirection.DESC)))),
				arguments("segment (entityHaving + orderBy)",
					segment(entityHaving(attributeEquals("a", "b")),
						orderBy(attributeNatural("a", OrderDirection.DESC)))),
				arguments("segments (four segment shapes)",
					segments(
						segment(orderBy(attributeNatural("a", OrderDirection.DESC))),
						segment(orderBy(attributeNatural("a", OrderDirection.DESC)), limit(10)),
						segment(entityHaving(attributeEquals("a", "b")),
							orderBy(attributeNatural("a", OrderDirection.DESC))),
						segment(entityHaving(attributeEquals("a", "b")),
							orderBy(attributeNatural("a", OrderDirection.DESC)), limit(10))
					))
			);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// require
	// ---------------------------------------------------------------------------------------------

	@Nested
	@DisplayName("require")
	@SuppressWarnings("deprecation")
	class Require {

		@Nested
		@DisplayName("debug")
		class Debug {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("require(debug(single))",
						require(debug(DebugMode.PREFER_PREFETCHING))),
					arguments("debug(three modes)",
						debug(DebugMode.PREFER_PREFETCHING,
							DebugMode.VERIFY_POSSIBLE_CACHING_TREES,
							DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS))
				);
			}
		}

		@Nested
		@DisplayName("entityFetch")
		class EntityFetch {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("entityFetch()",
						entityFetch()),
					arguments("attributeContentAll",
						entityFetch(attributeContentAll())),
					arguments("attributeContent(names)",
						entityFetch(attributeContent("a", "b", "c"))),
					arguments("associatedDataContentAll",
						entityFetch(associatedDataContentAll())),
					arguments("associatedDataContent(names)",
						entityFetch(associatedDataContent("a", "b", "c"))),

					arguments("referenceContentAll",
						entityFetch(referenceContentAll())),
					arguments("referenceContent(names)",
						entityFetch(referenceContent("a", "b", "c"))),
					arguments("referenceContent(name + entityFetchAll)",
						entityFetch(referenceContent("a", entityFetchAll()))),
					arguments("referenceContent(names + entityFetchAll)",
						entityFetch(referenceContent(new String[] {"a", "b"}, entityFetchAll()))),
					arguments("referenceContent(name + entityGroupFetchAll)",
						entityFetch(referenceContent("a", entityGroupFetchAll()))),
					arguments("referenceContent(names + entityGroupFetchAll)",
						entityFetch(referenceContent(new String[] {"a", "b"}, entityGroupFetchAll()))),
					arguments("referenceContent(name + entityFetchAll + entityGroupFetchAll)",
						entityFetch(referenceContent("a", entityFetchAll(), entityGroupFetchAll()))),
					arguments("referenceContent(names + entityFetchAll + entityGroupFetchAll)",
						entityFetch(referenceContent(new String[] {"a", "b"},
							entityFetchAll(), entityGroupFetchAll()))),
					arguments("referenceContent(name + filterBy)",
						entityFetch(referenceContent("a", filterBy(attributeEquals("a", "b"))))),
					arguments("referenceContent(name + filterBy + entityFetchAll)",
						entityFetch(referenceContent("a",
							filterBy(attributeEquals("a", "b")), entityFetchAll()))),
					arguments("referenceContent(name + filterBy + entityFetchAll + page)",
						entityFetch(referenceContent("a",
							filterBy(attributeEquals("a", "b")), entityFetchAll(), page(1, 20)))),

					arguments("referenceContentAllWithAttributes()",
						entityFetch(referenceContentAllWithAttributes())),
					arguments("referenceContentAllWithAttributes(attributeContent)",
						entityFetch(referenceContentAllWithAttributes(attributeContent("a", "b", "c")))),
					arguments("referenceContentAllWithAttributes(entityFetchAll)",
						entityFetch(referenceContentAllWithAttributes(entityFetchAll()))),
					arguments("referenceContentAllWithAttributes(attributeContent + entityFetchAll)",
						entityFetch(referenceContentAllWithAttributes(
							attributeContent("a", "b", "c"), entityFetchAll()))),
					arguments("referenceContentAllWithAttributes(entityGroupFetchAll)",
						entityFetch(referenceContentAllWithAttributes(entityGroupFetchAll()))),
					arguments("referenceContentAllWithAttributes(attributeContent + entityGroupFetchAll)",
						entityFetch(referenceContentAllWithAttributes(
							attributeContent("a", "b", "c"), entityGroupFetchAll()))),
					arguments("referenceContentAllWithAttributes(entityFetchAll + entityGroupFetchAll)",
						entityFetch(referenceContentAllWithAttributes(
							entityFetchAll(), entityGroupFetchAll()))),
					arguments("referenceContentAllWithAttributes(attributeContent + entityFetchAll + entityGroupFetchAll)",
						entityFetch(referenceContentAllWithAttributes(
							attributeContent("a", "b", "c"), entityFetchAll(), entityGroupFetchAll()))),
					arguments("referenceContentAllWithAttributes(ANY)",
						entityFetch(referenceContentAllWithAttributes(ManagedReferencesBehaviour.ANY))),
					arguments("referenceContentAllWithAttributes(ANY + attributeContent)",
						entityFetch(referenceContentAllWithAttributes(
							ManagedReferencesBehaviour.ANY, attributeContent("a", "b", "c")))),
					arguments("referenceContentAllWithAttributes(ANY + attributeContent + page)",
						entityFetch(referenceContentAllWithAttributes(
							ManagedReferencesBehaviour.ANY, attributeContent("a", "b", "c"), page(1, 20)))),

					arguments("referenceContentWithAttributes(name)",
						entityFetch(referenceContentWithAttributes("a"))),
					arguments("referenceContentWithAttributes(name, extras)",
						entityFetch(referenceContentWithAttributes("a", "b", "c"))),
					arguments("referenceContentWithAttributes(name + attributeContent)",
						entityFetch(referenceContentWithAttributes("a", attributeContent("b", "c")))),
					arguments("referenceContentWithAttributes(name + entityFetchAll)",
						entityFetch(referenceContentWithAttributes("a", entityFetchAll()))),
					arguments("referenceContentWithAttributes(name + attributeContent + entityFetchAll)",
						entityFetch(referenceContentWithAttributes("a",
							attributeContent("b", "c"), entityFetchAll()))),
					arguments("referenceContentWithAttributes(name + entityGroupFetchAll)",
						entityFetch(referenceContentWithAttributes("a", entityGroupFetchAll()))),
					arguments("referenceContentWithAttributes(name + attributeContent + entityGroupFetchAll)",
						entityFetch(referenceContentWithAttributes("a",
							attributeContent("b", "c"), entityGroupFetchAll()))),
					arguments("referenceContentWithAttributes(name + entityFetchAll + entityGroupFetchAll)",
						entityFetch(referenceContentWithAttributes("a",
							entityFetchAll(), entityGroupFetchAll()))),
					arguments("referenceContentWithAttributes(name + attributeContent + entityFetchAll + entityGroupFetchAll)",
						entityFetch(referenceContentWithAttributes("a",
							attributeContent("b", "c"), entityFetchAll(), entityGroupFetchAll()))),
					arguments("referenceContentWithAttributes(name + filterBy)",
						entityFetch(referenceContentWithAttributes("a",
							filterBy(attributeEquals("a", "b"))))),
					arguments("referenceContentWithAttributes(name + filterBy + entityFetchAll)",
						entityFetch(referenceContentWithAttributes("a",
							filterBy(attributeEquals("a", "b")), entityFetchAll()))),
					arguments("referenceContentWithAttributes(name + filterBy + entityFetchAll + page)",
						entityFetch(referenceContentWithAttributes("a",
							filterBy(attributeEquals("a", "b")), entityFetchAll(), page(1, 20)))),

					arguments("priceContentAll",
						entityFetch(priceContentAll())),
					arguments("priceContent(NONE)",
						entityFetch(priceContent(PriceContentMode.NONE))),
					arguments("priceContent(RESPECTING_FILTER + priceLists)",
						entityFetch(priceContent(PriceContentMode.RESPECTING_FILTER, "a", "b", "c"))),

					arguments("hierarchyContent()",
						entityFetch(hierarchyContent())),
					arguments("hierarchyContent(stopAt distance)",
						entityFetch(hierarchyContent(stopAt(distance(1))))),
					arguments("hierarchyContent(stopAt level)",
						entityFetch(hierarchyContent(stopAt(level(1))))),
					arguments("hierarchyContent(stopAt node filterBy)",
						entityFetch(hierarchyContent(stopAt(node(filterBy(attributeEquals("a", "b"))))))),
					arguments("hierarchyContent(entityFetchAll)",
						entityFetch(hierarchyContent(entityFetchAll()))),
					arguments("hierarchyContent(stopAt distance + entityFetchAll)",
						entityFetch(hierarchyContent(stopAt(distance(1)), entityFetchAll()))),
					arguments("hierarchyContent(stopAt level + entityFetchAll)",
						entityFetch(hierarchyContent(stopAt(level(1)), entityFetchAll()))),
					arguments("hierarchyContent(stopAt node filterBy + entityFetchAll)",
						entityFetch(hierarchyContent(
							stopAt(node(filterBy(attributeEquals("a", "b")))), entityFetchAll()))),

					arguments("dataInLocalesAll",
						entityFetch(dataInLocalesAll())),
					arguments("dataInLocales(en, fr)",
						entityFetch(dataInLocales(Locale.ENGLISH, Locale.FRENCH))),

					arguments("entityFetchAll",
						entityFetchAll()),
					arguments("entityGroupFetchAll",
						entityGroupFetchAll()),
					arguments("entityGroupFetch(attributeContentAll + priceContentAll)",
						entityGroupFetch(attributeContentAll(), priceContentAll()))
				);
			}
		}

		@Nested
		@DisplayName("facetSummary")
		class FacetSummary {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("inScope wrapping facetSummary",
						inScope(Scope.LIVE, facetSummary())),

					arguments("facetSummary(null depth)",
						facetSummary((FacetStatisticsDepth) null)),
					arguments("facetSummary(IMPACT)",
						facetSummary(FacetStatisticsDepth.IMPACT)),
					arguments("facetSummary(IMPACT + entityFetchAll)",
						facetSummary(FacetStatisticsDepth.IMPACT, entityFetchAll())),
					arguments("facetSummary(IMPACT + entityFetchAll + entityGroupFetchAll)",
						facetSummary(FacetStatisticsDepth.IMPACT, entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummary(null + filterBy)",
						facetSummary(null, filterBy(attributeEquals("a", "b")))),
					arguments("facetSummary(null + filterBy + filterGroupBy)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("c", "d")))),
					arguments("facetSummary(null + filterBy + filterGroupBy + entityFetchAll)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("c", "d")),
							entityFetchAll())),
					arguments("facetSummary(null + filterBy + entityFetchAll)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							entityFetchAll())),
					arguments("facetSummary(null + filterBy + entityFetchAll + entityGroupFetchAll)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummary(null + filterBy + orderBy random)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							orderBy(random()))),
					arguments("facetSummary(null + filterBy + orderBy random + entityFetchAll)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							orderBy(random()), entityFetchAll())),
					arguments("facetSummary(null + filterBy + orderBy random + entityFetchAll + entityGroupFetchAll)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							orderBy(random()), entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummary(null + filterBy + filterGroupBy d=e)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("d", "e")))),
					arguments("facetSummary(null + filterBy + filterGroupBy d=e + entityFetchAll + entityGroupFetchAll)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("d", "e")),
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummary(null + filterBy + filterGroupBy + orderBy + entityFetchAll + entityGroupFetchAll)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("d", "e")),
							orderBy(random()),
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummary(null + filterBy + filterGroupBy + orderBy + orderGroupBy + entityFetchAll + entityGroupFetchAll)",
						facetSummary(null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("d", "e")),
							orderBy(random()),
							orderGroupBy(attributeNatural("d", OrderDirection.DESC)),
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummary(null + orderGroupBy)",
						facetSummary((FacetStatisticsDepth) null,
							orderGroupBy(attributeNatural("a", OrderDirection.DESC)))),
					arguments("facetSummary(null + orderGroupBy + entityFetchAll)",
						facetSummary((FacetStatisticsDepth) null,
							orderGroupBy(attributeNatural("a", OrderDirection.DESC)),
							entityFetchAll())),
					arguments("facetSummary(null + orderBy random + orderGroupBy)",
						facetSummary((FacetStatisticsDepth) null,
							orderBy(random()),
							orderGroupBy(attributeNatural("a", OrderDirection.DESC)))),
					arguments("facetSummary(null + orderBy random + orderGroupBy + entityFetchAll)",
						facetSummary((FacetStatisticsDepth) null,
							orderBy(random()),
							orderGroupBy(attributeNatural("a", OrderDirection.DESC)),
							entityFetchAll())),

					arguments("facetSummaryOfReference(a)",
						facetSummaryOfReference("a")),
					arguments("facetSummaryOfReference(a, IMPACT)",
						facetSummaryOfReference("a", FacetStatisticsDepth.IMPACT)),
					arguments("facetSummaryOfReference(a, IMPACT + entityFetchAll)",
						facetSummaryOfReference("a", FacetStatisticsDepth.IMPACT, entityFetchAll())),
					arguments("facetSummaryOfReference(a, IMPACT + entityFetchAll + entityGroupFetchAll)",
						facetSummaryOfReference("a", FacetStatisticsDepth.IMPACT,
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummaryOfReference(a, null + filterBy)",
						facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")))),
					arguments("facetSummaryOfReference(a, null + filterBy + filterGroupBy)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("c", "d")))),
					arguments("facetSummaryOfReference(a, null + filterBy + filterGroupBy + entityFetchAll)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("c", "d")),
							entityFetchAll())),
					arguments("facetSummaryOfReference(a, null + filterBy + entityFetchAll)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							entityFetchAll())),
					arguments("facetSummaryOfReference(a, null + filterBy + entityFetchAll + entityGroupFetchAll)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummaryOfReference(a, null + filterBy + orderBy random)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							orderBy(random()))),
					arguments("facetSummaryOfReference(a, null + filterBy + orderBy random + entityFetchAll)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							orderBy(random()), entityFetchAll())),
					arguments("facetSummaryOfReference(a, null + filterBy + orderBy random + entityFetchAll + entityGroupFetchAll)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							orderBy(random()), entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummaryOfReference(a, null + filterBy + filterGroupBy d=e)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("d", "e")))),
					arguments("facetSummaryOfReference(a, null + filterBy + filterGroupBy d=e + entityFetchAll + entityGroupFetchAll)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("d", "e")),
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummaryOfReference(a, null + filterBy + filterGroupBy + orderBy + entityFetchAll + entityGroupFetchAll)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("d", "e")),
							orderBy(random()),
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummaryOfReference(a, null + filterBy + filterGroupBy + orderBy + orderGroupBy + entityFetchAll + entityGroupFetchAll)",
						facetSummaryOfReference("a", null,
							filterBy(attributeEquals("a", "b")),
							filterGroupBy(attributeEquals("d", "e")),
							orderBy(random()),
							orderGroupBy(attributeNatural("d", OrderDirection.DESC)),
							entityFetchAll(), entityGroupFetchAll())),
					arguments("facetSummaryOfReference(a, orderGroupBy)",
						facetSummaryOfReference("a",
							orderGroupBy(attributeNatural("a", OrderDirection.DESC)))),
					arguments("facetSummaryOfReference(a, orderGroupBy + entityFetchAll)",
						facetSummaryOfReference("a",
							orderGroupBy(attributeNatural("a", OrderDirection.DESC)),
							entityFetchAll())),
					arguments("facetSummaryOfReference(a, orderBy random + orderGroupBy)",
						facetSummaryOfReference("a",
							orderBy(random()),
							orderGroupBy(attributeNatural("a", OrderDirection.DESC)))),
					arguments("facetSummaryOfReference(a, orderBy random + orderGroupBy + entityFetchAll)",
						facetSummaryOfReference("a",
							orderBy(random()),
							orderGroupBy(attributeNatural("a", OrderDirection.DESC)),
							entityFetchAll()))
				);
			}
		}

		@Nested
		@DisplayName("prices")
		class Prices {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("priceType(WITHOUT_TAX)",
						priceType(QueryPriceMode.WITHOUT_TAX)),
					arguments("defaultAccompanyingPriceLists(single)",
						defaultAccompanyingPriceLists("a")),
					arguments("defaultAccompanyingPriceLists(two)",
						defaultAccompanyingPriceLists("a", "b")),
					arguments("accompanyingPriceContentDefault",
						accompanyingPriceContentDefault()),
					arguments("accompanyingPriceContent(single)",
						accompanyingPriceContent("a")),
					arguments("accompanyingPriceContent(two)",
						accompanyingPriceContent("a", "b"))
				);
			}
		}

		@Nested
		@DisplayName("histograms")
		class Histograms {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("priceHistogram(buckets)",
						priceHistogram(20)),
					arguments("priceHistogram(buckets + OPTIMIZED)",
						priceHistogram(20, HistogramBehavior.OPTIMIZED)),
					arguments("attributeHistogram(buckets + names)",
						attributeHistogram(20, "a", "b")),
					arguments("attributeHistogram(buckets + OPTIMIZED + names)",
						attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b"))
				);
			}
		}

		@Nested
		@DisplayName("hierarchyOfSelf")
		class HierarchyOfSelf {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					arguments("fromRoot(a)",
						hierarchyOfSelf(fromRoot("a"))),
					arguments("fromRoot(a + stopAt distance + statistics type)",
						hierarchyOfSelf(fromRoot("a",
							stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("fromRoot(a + stopAt distance + statistics base+type)",
						hierarchyOfSelf(fromRoot("a",
							stopAt(distance(1)),
							statistics(StatisticsBase.COMPLETE_FILTER,
								StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("fromRoot(a + entityFetchAll + stopAt distance + statistics two types)",
						hierarchyOfSelf(fromRoot("a",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT,
								StatisticsType.CHILDREN_COUNT)))),
					arguments("fromNode(b + node filterBy)",
						hierarchyOfSelf(fromNode("b",
							node(filterBy(attributeEquals("a", "b")))))),
					arguments("fromNode(b + node filterBy + stopAt + statistics)",
						hierarchyOfSelf(fromNode("b",
							node(filterBy(attributeEquals("a", "b"))),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("fromNode(b + node filterBy + entityFetchAll + stopAt + statistics)",
						hierarchyOfSelf(fromNode("b",
							node(filterBy(attributeEquals("a", "b"))),
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("children(c)",
						hierarchyOfSelf(children("c"))),
					arguments("children(c + stopAt + statistics)",
						hierarchyOfSelf(children("c",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("children(c + entityFetchAll + stopAt + statistics)",
						hierarchyOfSelf(children("c",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("parents(d)",
						hierarchyOfSelf(parents("d"))),
					arguments("parents(d + stopAt + statistics)",
						hierarchyOfSelf(parents("d",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("parents(d + siblings + stopAt + statistics)",
						hierarchyOfSelf(parents("d",
							siblings(stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("parents(d + siblings w/ entityFetchAll + stopAt + statistics)",
						hierarchyOfSelf(parents("d",
							siblings(entityFetchAll(),
								stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("parents(d + entityFetchAll + stopAt + statistics)",
						hierarchyOfSelf(parents("d",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("parents(d + entityFetchAll + siblings + stopAt + statistics)",
						hierarchyOfSelf(parents("d",
							entityFetchAll(),
							siblings(stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("siblings(e)",
						hierarchyOfSelf(siblings("e"))),
					arguments("siblings(e + stopAt + statistics)",
						hierarchyOfSelf(siblings("e",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("siblings(e + entityFetchAll + stopAt + statistics)",
						hierarchyOfSelf(siblings("e",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT))))
				);
			}
		}

		@Nested
		@DisplayName("hierarchyOfReference")
		class HierarchyOfReference {
			@ParameterizedTest(name = "{0}")
			@MethodSource("variants")
			void shouldRoundTrip(@Nonnull String desc, @Nonnull Object constraint) {
				assertSerializationRound(constraint);
			}

			@Nonnull
			static Stream<Arguments> variants() {
				return Stream.of(
					// single-classifier overloads
					arguments("single/fromRoot(a)",
						hierarchyOfReference("a", fromRoot("a"))),
					arguments("single/fromRoot(a + stopAt + statistics)",
						hierarchyOfReference("a", fromRoot("a",
							stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/fromRoot(a + stopAt + statistics base+type)",
						hierarchyOfReference("a", fromRoot("a",
							stopAt(distance(1)),
							statistics(StatisticsBase.COMPLETE_FILTER,
								StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/fromRoot(a + entityFetchAll + stopAt + statistics two types)",
						hierarchyOfReference("a", fromRoot("a",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT,
								StatisticsType.CHILDREN_COUNT)))),
					arguments("single/fromNode(b + node filterBy)",
						hierarchyOfReference("a", fromNode("b",
							node(filterBy(attributeEquals("a", "b")))))),
					arguments("single/fromNode(b + node filterBy + stopAt + statistics)",
						hierarchyOfReference("a", fromNode("b",
							node(filterBy(attributeEquals("a", "b"))),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/fromNode(b + node filterBy + entityFetchAll + stopAt + statistics)",
						hierarchyOfReference("a", fromNode("b",
							node(filterBy(attributeEquals("a", "b"))),
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/children(c)",
						hierarchyOfReference("a", children("c"))),
					arguments("single/children(c + stopAt + statistics)",
						hierarchyOfReference("a", children("c",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/children(c + entityFetchAll + stopAt + statistics)",
						hierarchyOfReference("a", children("c",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/parents(d)",
						hierarchyOfReference("a", parents("d"))),
					arguments("single/parents(d + stopAt + statistics)",
						hierarchyOfReference("a", parents("d",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/parents(d + siblings + stopAt + statistics)",
						hierarchyOfReference("a", parents("d",
							siblings(stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/parents(d + siblings w/ entityFetchAll + stopAt + statistics)",
						hierarchyOfReference("a", parents("d",
							siblings(entityFetchAll(),
								stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/parents(d + entityFetchAll + stopAt + statistics)",
						hierarchyOfReference("a", parents("d",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/parents(d + entityFetchAll + siblings + stopAt + statistics)",
						hierarchyOfReference("a", parents("d",
							entityFetchAll(),
							siblings(stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/siblings(e)",
						hierarchyOfReference("a", siblings("e"))),
					arguments("single/siblings(e + stopAt + statistics)",
						hierarchyOfReference("a", siblings("e",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("single/siblings(e + entityFetchAll + stopAt + statistics)",
						hierarchyOfReference("a", siblings("e",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),

					// multi-classifier overloads
					arguments("multi/fromRoot(a)",
						hierarchyOfReference(new String[] {"a", "b"}, fromRoot("a"))),
					arguments("multi/fromRoot(a + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, fromRoot("a",
							stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/fromRoot(a + stopAt + statistics base+type)",
						hierarchyOfReference(new String[] {"a", "b"}, fromRoot("a",
							stopAt(distance(1)),
							statistics(StatisticsBase.COMPLETE_FILTER,
								StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/fromRoot(a + entityFetchAll + stopAt + statistics two types)",
						hierarchyOfReference(new String[] {"a", "b"}, fromRoot("a",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT,
								StatisticsType.CHILDREN_COUNT)))),
					arguments("multi/fromNode(b + node filterBy)",
						hierarchyOfReference(new String[] {"a", "b"}, fromNode("b",
							node(filterBy(attributeEquals("a", "b")))))),
					arguments("multi/fromNode(b + node filterBy + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, fromNode("b",
							node(filterBy(attributeEquals("a", "b"))),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/fromNode(b + node filterBy + entityFetchAll + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, fromNode("b",
							node(filterBy(attributeEquals("a", "b"))),
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/children(c)",
						hierarchyOfReference(new String[] {"a", "b"}, children("c"))),
					arguments("multi/children(c + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, children("c",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/children(c + entityFetchAll + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, children("c",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/parents(d)",
						hierarchyOfReference(new String[] {"a", "b"}, parents("d"))),
					arguments("multi/parents(d + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, parents("d",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/parents(d + siblings + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, parents("d",
							siblings(stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/parents(d + siblings w/ entityFetchAll + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, parents("d",
							siblings(entityFetchAll(),
								stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/parents(d + entityFetchAll + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, parents("d",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/parents(d + entityFetchAll + siblings + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, parents("d",
							entityFetchAll(),
							siblings(stopAt(distance(1)),
								statistics(StatisticsType.QUERIED_ENTITY_COUNT)),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/siblings(e)",
						hierarchyOfReference(new String[] {"a", "b"}, siblings("e"))),
					arguments("multi/siblings(e + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, siblings("e",
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT)))),
					arguments("multi/siblings(e + entityFetchAll + stopAt + statistics)",
						hierarchyOfReference(new String[] {"a", "b"}, siblings("e",
							entityFetchAll(),
							stopAt(distance(1)),
							statistics(StatisticsType.QUERIED_ENTITY_COUNT))))
				);
			}
		}
	}

}
