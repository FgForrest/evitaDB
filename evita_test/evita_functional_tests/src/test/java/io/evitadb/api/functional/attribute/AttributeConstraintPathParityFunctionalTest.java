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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesEditor;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins that the four ways the engine evaluates one attribute filter constraint on one stored value agree:
 *
 * - **index** - the translator probes `FilterIndex` / `RangeIndex` (query steered by `PREFER_INDEX_SCAN`),
 * - **prefetch** - the translator builds an `AttributeBitmapFilter` over the matcher of its constraint and runs it over
 *   the prefetched entity bodies (query steered by `PREFER_PREFETCHING` plus an `entityPrimaryKeyInSet`),
 * - **row** - `referenceContent(r, filterBy(...))` evaluates the constraint on every reference row,
 * - **entity filtering** - a query naming no collection evaluates a not catalog attribute on the entities found by
 *   their globally unique code.
 *
 * Every fixture stores one owner entity per value plus one owner without a value. An owner carries its value twice - as
 * its own filterable attribute (read by the index, prefetch and entity filtering arms) and as the attribute of its
 * single reference row of reference `r` (read by the row arm), both at the same `indexedDecimalPlaces`. The outcome of
 * an arm is the set of owner primary keys the constraint selects, or the exception it throws; every case asserts all
 * four outcomes are equal. A case whose index outcome is not an error must select some owners but not all of them,
 * otherwise it would prove nothing.
 *
 * The values a main query refuses before it reads any index - an inverted interval over a scalar attribute, a decimal
 * the index key cannot encode - and the one difference of the reference row (it compares wide decimals) have tests of
 * their own below the agreement of the arms.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute filter constraints answer alike on the index, prefetch, reference row and entity filtering")
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttributeConstraintPathParityFunctionalTest implements EvitaTestSupport {

	private static final String ATTR = "val";
	private static final String CODE = "code";
	private static final String REF = "r";
	private static final String TARGET = "target";
	private static final String NFD_CAFE = "café";
	private static final String NFC_CAFE = "café";

	/**
	 * Values stored by one family of owners.
	 *
	 * @param name       name of the family, part of the entity type
	 * @param type       type of the attribute
	 * @param places     `indexedDecimalPlaces` of the attribute, 0 when not decimal
	 * @param values     one value per owner, owners are numbered from 1; one more owner follows with no value
	 * @param filterable whether the attribute is filterable, on the owner and on its reference alike
	 * @param populated  whether the collection holds any owner at all
	 */
	private record Fixture(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		int places,
		@Nonnull Serializable[] values,
		boolean filterable,
		boolean populated
	) {

		/**
		 * Creates a fixture of a filterable attribute in a populated collection.
		 */
		Fixture(
			@Nonnull String name,
			@Nonnull Class<? extends Serializable> type,
			int places,
			@Nonnull Serializable[] values
		) {
			this(name, type, places, values, true, true);
		}

		@Nonnull
		String entityType() {
			return "owner" + this.name;
		}

		/**
		 * Returns the globally unique code of the owner with the given primary key.
		 */
		@Nonnull
		String code(int primaryKey) {
			return this.name + "-" + primaryKey;
		}

		/**
		 * Returns the codes of every owner, in the order of the primary keys.
		 */
		@Nonnull
		String[] codes() {
			final int[] owners = owners();
			final String[] codes = new String[owners.length];
			for (int i = 0; i < owners.length; i++) {
				codes[i] = code(owners[i]);
			}
			return codes;
		}

		/**
		 * Returns every owner primary key, the one without a value last.
		 */
		@Nonnull
		int[] owners() {
			final int[] owners = new int[this.populated ? this.values.length + 1 : 0];
			for (int i = 0; i < owners.length; i++) {
				owners[i] = i + 1;
			}
			return owners;
		}
	}

	/**
	 * One constraint over one fixture.
	 *
	 * @param fixture    the fixture
	 * @param label      human description of the constraint and its values
	 * @param constraint creates the constraint over an attribute name
	 * @param errorCase  true when an error is the expected index outcome, so no discrimination is demanded
	 */
	private record Case(
		@Nonnull Fixture fixture,
		@Nonnull String label,
		@Nonnull Function<String, FilterConstraint> constraint,
		boolean errorCase
	) {
	}

	private static final Fixture INTEGER = new Fixture("Integer", Integer.class, 0, new Serializable[]{1, 5, 10, 20});
	private static final Fixture LONG = new Fixture("Long", Long.class, 0, new Serializable[]{1L, 5L, 10L, 20L});
	private static final Fixture DECIMAL = new Fixture(
		"Decimal", BigDecimal.class, 2,
		new Serializable[]{
			new BigDecimal("1.005"), new BigDecimal("1.0049"), new BigDecimal("1.01"), new BigDecimal("-1.005")
		}
	);
	private static final Fixture STRING = new Fixture(
		"String", String.class, 0,
		new Serializable[]{NFD_CAFE, NFC_CAFE, "cafe", "bar", "x" + NFD_CAFE}
	);
	private static final Fixture OFFSET = new Fixture(
		"OffsetDateTime", OffsetDateTime.class, 0,
		new Serializable[]{
			OffsetDateTime.parse("2026-01-01T12:00:00+01:00"), OffsetDateTime.parse("2026-01-01T11:00:00Z"),
			OffsetDateTime.parse("2026-01-01T13:00:00Z"), OffsetDateTime.parse("2025-12-31T10:00:00Z")
		}
	);
	private static final Fixture LOCAL = new Fixture(
		"LocalDateTime", LocalDateTime.class, 0,
		new Serializable[]{
			LocalDateTime.parse("2026-01-01T10:00:00"), LocalDateTime.parse("2026-01-01T11:00:00"),
			LocalDateTime.parse("2026-01-01T12:00:00"), LocalDateTime.parse("2026-01-01T09:00:00")
		}
	);
	private static final Fixture BOOLEAN = new Fixture(
		"Boolean", Boolean.class, 0, new Serializable[]{true, false, true}
	);
	private static final Fixture INTEGER_ARRAY = new Fixture(
		"IntegerArray", Integer[].class, 0,
		new Serializable[]{new Integer[]{1, 2, 3}, new Integer[]{4, 5}, new Integer[0], new Integer[]{2, 9}}
	);
	private static final Fixture STRING_ARRAY = new Fixture(
		"StringArray", String[].class, 0,
		new Serializable[]{
			new String[]{NFD_CAFE, "x"}, new String[]{NFC_CAFE}, new String[]{"bar", "baz"}, new String[0]
		}
	);
	private static final Fixture INTEGER_RANGE = new Fixture(
		"IntegerRange", IntegerNumberRange.class, 0,
		new Serializable[]{
			IntegerNumberRange.between(1, 5), IntegerNumberRange.between(10, 20), IntegerNumberRange.between(3, 12)
		}
	);
	private static final Fixture LONG_RANGE = new Fixture(
		"LongRange", LongNumberRange.class, 0,
		new Serializable[]{
			LongNumberRange.between(1L, 5L), LongNumberRange.between(10L, 20L), LongNumberRange.between(3L, 12L)
		}
	);
	private static final Fixture DECIMAL_RANGE = new Fixture(
		"DecimalRange", BigDecimalNumberRange.class, 2,
		new Serializable[]{
			BigDecimalNumberRange.between(new BigDecimal("1.005"), new BigDecimal("2.005")),
			BigDecimalNumberRange.between(new BigDecimal("2.004"), new BigDecimal("3.00")),
			BigDecimalNumberRange.between(new BigDecimal("1.50"), new BigDecimal("1.994"))
		}
	);
	private static final Fixture DATE_RANGE = new Fixture(
		"DateRange", DateTimeRange.class, 0,
		new Serializable[]{
			DateTimeRange.between(
				OffsetDateTime.parse("2026-01-01T00:00:00Z"), OffsetDateTime.parse("2026-01-31T00:00:00Z")
			),
			DateTimeRange.between(
				OffsetDateTime.parse("2026-02-01T00:00:00Z"), OffsetDateTime.parse("2026-02-28T00:00:00Z")
			),
			DateTimeRange.between(
				OffsetDateTime.parse("2026-01-15T00:00:00Z"), OffsetDateTime.parse("2026-02-10T00:00:00Z")
			)
		}
	);
	/**
	 * No stored value at all, so the owner without a value is the only one and the index holds no data.
	 */
	private static final Fixture EMPTY = new Fixture("EmptyInteger", Integer.class, 0, new Serializable[0]);
	private static final Fixture BYTE = new Fixture(
		"Byte", Byte.class, 0, new Serializable[]{(byte) 1, (byte) 5, (byte) 10, (byte) 20}
	);
	private static final Fixture SHORT = new Fixture(
		"Short", Short.class, 0, new Serializable[]{(short) 1, (short) 5, (short) 10, (short) 20}
	);
	private static final Fixture CHARACTER = new Fixture(
		"Character", Character.class, 0, new Serializable[]{'a', 'e', 'j', 't'}
	);
	private static final Fixture LOCAL_DATE = new Fixture(
		"LocalDate", LocalDate.class, 0,
		new Serializable[]{
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-10"),
			LocalDate.parse("2026-01-20")
		}
	);
	private static final Fixture LOCAL_TIME = new Fixture(
		"LocalTime", LocalTime.class, 0,
		new Serializable[]{
			LocalTime.parse("01:00:00"), LocalTime.parse("05:30:00"), LocalTime.parse("10:15:30"),
			LocalTime.parse("20:00:00")
		}
	);
	private static final Fixture CURRENCY = new Fixture(
		"Currency", Currency.class, 0,
		new Serializable[]{
			Currency.getInstance("CZK"), Currency.getInstance("EUR"), Currency.getInstance("GBP"),
			Currency.getInstance("USD")
		}
	);
	private static final Fixture LOCALE = new Fixture(
		"Locale", Locale.class, 0,
		new Serializable[]{Locale.forLanguageTag("cs"), Locale.forLanguageTag("de"), Locale.forLanguageTag("en"),
			Locale.forLanguageTag("fr")}
	);
	/**
	 * A collection that holds no entity at all.
	 */
	private static final Fixture NO_ENTITIES = new Fixture(
		"NoEntities", Integer.class, 0, new Serializable[0], true, false
	);
	/**
	 * A decimal attribute no index holds, one of its values being beyond what the index keys could encode.
	 */
	private static final Fixture DECIMAL_NOT_FILTERABLE = new Fixture(
		"DecimalNotFilterable", BigDecimal.class, 2,
		new Serializable[]{new BigDecimal("1.5"), new BigDecimal("123456789012.5")}, false, true
	);
	private static final List<Fixture> FIXTURES = List.of(
		EMPTY, NO_ENTITIES, INTEGER, LONG, DECIMAL, DECIMAL_NOT_FILTERABLE, STRING, OFFSET, LOCAL, BOOLEAN,
		INTEGER_ARRAY, STRING_ARRAY, INTEGER_RANGE, LONG_RANGE, DECIMAL_RANGE, DATE_RANGE,
		BYTE, SHORT, CHARACTER, LOCAL_DATE, LOCAL_TIME, CURRENCY, LOCALE
	);
	/**
	 * The fixtures whose cases are not part of the agreement of the four arms.
	 */
	private static final Set<Fixture> OUTSIDE_AGREEMENT = Set.of(EMPTY, NO_ENTITIES, DECIMAL_NOT_FILTERABLE);

	private TestPaths paths;
	private Evita evita;

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("AttributeConstraintPathParityFunctionalTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build()
		);
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(Scope.values()))
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (Fixture fixture : FIXTURES) {
					defineAndFill(session, fixture);
				}
				session.goLiveAndClose();
			}
		);
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@TestFactory
	@DisplayName("index, prefetch, reference row and entity filtering agree")
	Stream<DynamicTest> shouldAgreeOnEveryPath() {
		return cases().stream().map(
			it -> DynamicTest.dynamicTest(
				it.fixture().name() + " " + it.label(),
				() -> verify(it)
			)
		);
	}

	/**
	 * Runs the case through the four arms and compares the outcomes.
	 */
	private void verify(@Nonnull Case theCase) {
		final String[] outcomes = outcomesOf(theCase);
		final String index = outcomes[0].replaceFirst(" \\[(NOT-)?prefetched]$", "");
		if (!theCase.errorCase() && !index.startsWith("ERR")) {
			final Set<Integer> selected = parseSet(index);
			final int all = theCase.fixture().owners().length;
			if (selected.isEmpty() || selected.size() == all) {
				fail("Vacuous case - the index arm selects " + index + " of " + all + " owners");
			}
		}
		final String prefetch = outcomes[1].replaceFirst(" \\[(NOT-)?prefetched]$", "");
		if (!index.equals(prefetch) || !index.equals(outcomes[2]) || !index.equals(outcomes[3])) {
			fail("I=" + outcomes[0] + ", P=" + outcomes[1] + ", R=" + outcomes[2] + ", E=" + outcomes[3]);
		}
	}

	@Test
	@DisplayName("a decimal beyond the key of the index is refused by index, prefetch and entity filtering")
	void shouldRefuseDecimalBeyondIndexKeyExceptOnReferenceRow() {
		final String tooBig = "99999999999";
		final String refusal = "ERR EvitaInvalidUsageException: Cannot convert big decimal " + tooBig
			+ " to exact integer by using 2 decimal places!";
		final BigDecimal huge = new BigDecimal(tooBig);
		final BigDecimal one = new BigDecimal("1.00");
		final Map<Case, String> rowAnswers = new LinkedHashMap<>();
		rowAnswers.put(new Case(DECIMAL, "equals(huge)", a -> attributeEquals(a, huge), true), "[]");
		rowAnswers.put(
			new Case(DECIMAL, "inSet(1.01,huge)", a -> attributeInSet(a, new BigDecimal("1.01"), huge), true),
			"[1, 3]"
		);
		rowAnswers.put(new Case(DECIMAL, "lessThan(huge)", a -> attributeLessThan(a, huge), true), "[1, 2, 3, 4]");
		rowAnswers.put(
			new Case(DECIMAL, "lessThanEquals(huge)", a -> attributeLessThanEquals(a, huge), true), "[1, 2, 3, 4]"
		);
		rowAnswers.put(new Case(DECIMAL, "greaterThan(huge)", a -> attributeGreaterThan(a, huge), true), "[]");
		rowAnswers.put(
			new Case(DECIMAL, "greaterThanEquals(huge)", a -> attributeGreaterThanEquals(a, huge), true), "[]"
		);
		rowAnswers.put(
			new Case(DECIMAL, "between(1.00,huge)", a -> attributeBetween(a, one, huge), true), "[1, 2, 3]"
		);
		rowAnswers.put(
			new Case(DECIMAL, "between(null,huge)", a -> attributeBetween(a, (BigDecimal) null, huge), true),
			"[1, 2, 3, 4]"
		);
		rowAnswers.put(
			new Case(DECIMAL, "between(-huge,1.00)", a -> attributeBetween(a, huge.negate(), one), true), "[2, 4]"
		);
		for (Map.Entry<Case, String> entry : rowAnswers.entrySet()) {
			final String[] outcomes = outcomesOf(entry.getKey());
			final String label = entry.getKey().label();
			final String expectedRefusal = label.contains("-huge") ?
				refusal.replace(tooBig, "-" + tooBig) : refusal;
			assertEquals(expectedRefusal, stripPrefetchMark(outcomes[0]), label + " on the index");
			assertEquals(expectedRefusal, stripPrefetchMark(outcomes[1]), label + " on the prefetch");
			// the one difference: a reference attribute needs no index, so the row compares wide decimals
			assertEquals(entry.getValue(), outcomes[2], label + " on the reference row");
			assertEquals(expectedRefusal, outcomes[3], label + " on the entity filtering");
		}
	}

	@Test
	@DisplayName("the largest decimal the key of the index holds is no reason to refuse")
	void shouldAnswerDecimalOfTheLargestIndexKey() {
		final BigDecimal largest = new BigDecimal("21474836.47");
		final String[] outcomes = outcomesOf(
			new Case(DECIMAL, "lessThan(largest)", a -> attributeLessThan(a, largest), false)
		);
		for (String outcome : outcomes) {
			assertEquals("[1, 2, 3, 4]", stripPrefetchMark(outcome));
		}
		final BigDecimal beyond = new BigDecimal("21474836.48");
		final String[] refused = outcomesOf(
			new Case(DECIMAL, "lessThan(beyond)", a -> attributeLessThan(a, beyond), true)
		);
		final String refusal = "ERR EvitaInvalidUsageException: Cannot convert big decimal 21474836.48"
			+ " to exact integer by using 2 decimal places!";
		assertEquals(refusal, stripPrefetchMark(refused[0]));
		assertEquals(refusal, stripPrefetchMark(refused[1]));
		assertEquals("[1, 2, 3, 4]", refused[2]);
		assertEquals(refusal, refused[3]);
	}

	@Test
	@DisplayName("a decimal no index holds is compared as it is, however large")
	void shouldNotRefuseDecimalOfAttributeNoIndexHolds() {
		final BigDecimal huge = new BigDecimal("99999999999");
		final Map<Case, String> answers = new LinkedHashMap<>();
		answers.put(new Case(DECIMAL_NOT_FILTERABLE, "equals(huge)", a -> attributeEquals(a, huge), true), "[]");
		answers.put(
			new Case(DECIMAL_NOT_FILTERABLE, "lessThan(huge)", a -> attributeLessThan(a, huge), true), "[1]"
		);
		answers.put(
			new Case(DECIMAL_NOT_FILTERABLE, "greaterThan(huge)", a -> attributeGreaterThan(a, huge), true), "[2]"
		);
		for (Map.Entry<Case, String> entry : answers.entrySet()) {
			final String[] outcomes = outcomesOf(entry.getKey());
			final String label = entry.getKey().label();
			// the main query needs a filterable attribute, so it fails for that reason and no other
			assertTrue(outcomes[0].startsWith("ERR AttributeNotFilterableException"), label + ": " + outcomes[0]);
			assertTrue(outcomes[1].startsWith("ERR AttributeNotFilterableException"), label + ": " + outcomes[1]);
			assertEquals(entry.getValue(), outcomes[2], label + " on the reference row");
			assertEquals(entry.getValue(), outcomes[3], label + " on the entity filtering");
		}
	}

	@Test
	@DisplayName("an inverted interval is refused although the collection holds no entity")
	void shouldRefuseInvertedIntervalOfCollectionWithoutEntity() {
		final FilterConstraint inverted = attributeBetween(ATTR, 10, 5);
		final String refusal = "ERR EvitaInvalidUsageException: From must be lower than to: 10 vs. 5";
		final String[] outcomes = this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, String[]>) session -> new String[]{
				outcome(() -> keys(session.query(
					query(
						collection(NO_ENTITIES.entityType()), filterBy(inverted),
						require(debug(DebugMode.PREFER_INDEX_SCAN), page(1, 100))
					),
					EntityReference.class
				))),
				outcome(() -> keys(session.query(
					query(
						collection(NO_ENTITIES.entityType()), filterBy(inverted),
						require(PlanPreference.PREFETCH.debug(), page(1, 100))
					),
					EntityReference.class
				)))
			}
		);
		assertEquals(refusal, outcomes[0]);
		assertEquals(refusal, outcomes[1]);
	}

	@Test
	@DisplayName("an inverted interval is refused on entity filtering for a candidate holding a scalar attribute only")
	void shouldRefuseInvertedIntervalOnEntityFilteringPerCandidateType() {
		final FilterConstraint inverted = attributeBetween(ATTR, 10, 5);
		final String refusal = "ERR EvitaInvalidUsageException: From must be lower than to: 10 vs. 5";
		this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Object>) session -> {
				// no candidate: there is no schema to validate against
				assertEquals("[]", entityFiltering(session, new String[]{"no-such-code"}, inverted));
				assertEquals("[]", entityFiltering(session, NO_ENTITIES.codes(), inverted));
				// a candidate of a scalar type refuses
				assertEquals(refusal, entityFiltering(session, INTEGER.codes(), inverted));
				// a candidate whose attribute is a range matches nothing
				assertEquals("[]", entityFiltering(session, INTEGER_RANGE.codes(), inverted));
				assertEquals("[]", entityFiltering(session, DECIMAL_RANGE.codes(), inverted));
				// candidates of several types refuse once one of them is scalar
				assertEquals(
					refusal, entityFiltering(session, concat(INTEGER_RANGE.codes(), INTEGER.codes()), inverted)
				);
				assertEquals(refusal, entityFiltering(session, concat(INTEGER.codes(), LONG.codes()), inverted));
				// candidates of several types, none scalar, match nothing
				assertEquals(
					"[]", entityFiltering(session, concat(INTEGER_RANGE.codes(), LONG_RANGE.codes()), inverted)
				);
				return null;
			}
		);
	}

	/**
	 * Returns the outcome without the mark telling whether the plan was prefetched.
	 */
	@Nonnull
	private static String stripPrefetchMark(@Nonnull String outcome) {
		return outcome.replaceFirst(" \\[(NOT-)?prefetched]$", "");
	}

	/**
	 * Returns the codes of both arrays, one after the other.
	 */
	@Nonnull
	private static String[] concat(@Nonnull String[] first, @Nonnull String[] second) {
		final String[] result = new String[first.length + second.length];
		System.arraycopy(first, 0, result, 0, first.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	/**
	 * Returns the outcomes of the four arms: index, prefetch, row and entity filtering.
	 */
	@Nonnull
	private String[] outcomesOf(@Nonnull Case theCase) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, String[]>) session -> new String[]{
				indexArm(session, theCase),
				prefetchArm(session, theCase),
				rowArm(session, theCase),
				entityFilteringArm(session, theCase)
			}
		);
	}

	/**
	 * Parses the primary keys of an outcome printed as a set.
	 */
	@Nonnull
	private static Set<Integer> parseSet(@Nonnull String text) {
		final Set<Integer> result = new TreeSet<>();
		final String body = text.substring(1, text.length() - 1).trim();
		if (!body.isEmpty()) {
			for (String part : body.split(",")) {
				result.add(Integer.parseInt(part.trim()));
			}
		}
		return result;
	}

	/**
	 * Index arm: the translator resolves the indexes.
	 */
	@Nonnull
	private static String indexArm(@Nonnull EvitaSessionContract session, @Nonnull Case theCase) {
		return outcome(() -> {
			final EvitaResponse<EntityReference> response = session.query(
				query(
					collection(theCase.fixture().entityType()),
					filterBy(
						entityPrimaryKeyInSet(theCase.fixture().owners()),
						theCase.constraint().apply(ATTR)
					),
					require(
						queryTelemetry(),
						debug(DebugMode.PREFER_INDEX_SCAN),
						page(1, 100)
					)
				),
				EntityReference.class
			);
			return keys(response) + (PlanPreference.prefetched(response) ? " [prefetched]" : "");
		});
	}

	/**
	 * Prefetch arm: the engine is steered onto the prefetched bodies.
	 */
	@Nonnull
	private static String prefetchArm(@Nonnull EvitaSessionContract session, @Nonnull Case theCase) {
		return outcome(() -> {
			final EvitaResponse<EntityReference> response = session.query(
				query(
					collection(theCase.fixture().entityType()),
					filterBy(
						entityPrimaryKeyInSet(theCase.fixture().owners()),
						theCase.constraint().apply(ATTR)
					),
					require(queryTelemetry(), PlanPreference.PREFETCH.debug(), page(1, 100))
				),
				EntityReference.class
			);
			return keys(response) + (PlanPreference.prefetched(response) ? "" : " [NOT-prefetched]");
		});
	}

	/**
	 * Row arm: the owners whose single reference row survives the `referenceContent` filter.
	 */
	@Nonnull
	private static String rowArm(@Nonnull EvitaSessionContract session, @Nonnull Case theCase) {
		return outcome(() -> {
			final EvitaResponse<SealedEntity> response = session.query(
				query(
					collection(theCase.fixture().entityType()),
					filterBy(entityPrimaryKeyInSet(theCase.fixture().owners())),
					require(
						entityFetch(referenceContent(REF, filterBy(theCase.constraint().apply(ATTR)))),
						page(1, 100)
					)
				),
				SealedEntity.class
			);
			final Set<Integer> result = new TreeSet<>();
			for (SealedEntity entity : response.getRecordData()) {
				if (!entity.getReferences(REF).isEmpty()) {
					result.add(entity.getPrimaryKey());
				}
			}
			return result.toString();
		});
	}

	/**
	 * Entity filtering arm: the query names no collection, so the entity type is unknown and the attribute, not being
	 * a catalog one, is evaluated on the entities found by the globally unique code of every owner.
	 */
	@Nonnull
	private static String entityFilteringArm(@Nonnull EvitaSessionContract session, @Nonnull Case theCase) {
		return entityFiltering(session, theCase.fixture().codes(), theCase.constraint().apply(ATTR));
	}

	/**
	 * Entity filtering: the query names no collection and finds the entities by their globally unique codes.
	 */
	@Nonnull
	private static String entityFiltering(
		@Nonnull EvitaSessionContract session,
		@Nonnull String[] codes,
		@Nonnull FilterConstraint constraint
	) {
		return outcome(() -> {
			final EvitaResponse<EntityReference> response = session.query(
				query(
					filterBy(attributeInSet(CODE, codes), constraint),
					require(page(1, 100))
				),
				EntityReference.class
			);
			return keys(response);
		});
	}

	/**
	 * Returns the primary keys of the response as the sorted set of them.
	 */
	@Nonnull
	private static String keys(@Nonnull EvitaResponse<EntityReference> response) {
		final Set<Integer> result = new TreeSet<>();
		for (EntityReference reference : response.getRecordData()) {
			result.add(reference.getPrimaryKey());
		}
		return result.toString();
	}

	/**
	 * Returns the outcome of the arm: its text, or the exception class with its single-line message.
	 */
	@Nonnull
	private static String outcome(@Nonnull Supplier<String> arm) {
		try {
			return arm.get();
		} catch (Exception e) {
			return "ERR " + e.getClass().getSimpleName() + ": "
				+ String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
		}
	}

	/**
	 * Defines the owner collection of the fixture and stores one owner per value and one without a value.
	 */
	private static void defineAndFill(@Nonnull EvitaSessionContract session, @Nonnull Fixture fixture) {
		session.defineEntitySchema(fixture.entityType())
			.withGlobalAttribute(CODE)
			.withAttribute(
				ATTR, fixture.type(),
				whichIs -> {
					if (fixture.filterable()) {
						whichIs.filterable();
					}
					whichIs.nullable();
					if (fixture.places() > 0) {
						whichIs.indexDecimalPlaces(fixture.places());
					}
				}
			)
			.withReferenceTo(
				REF, TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(
						ATTR, fixture.type(),
						thatIs -> {
							if (fixture.filterable()) {
								thatIs.filterable();
							}
							thatIs.nullable();
							if (fixture.places() > 0) {
								thatIs.indexDecimalPlaces(fixture.places());
							}
						}
					)
			)
			.updateVia(session);
		for (int i = 0; fixture.populated() && i <= fixture.values().length; i++) {
			final Serializable value = i < fixture.values().length ? fixture.values()[i] : null;
			final EntityBuilder owner = session.createNewEntity(fixture.entityType(), i + 1);
			owner.setAttribute(CODE, fixture.code(i + 1));
			storeValue(owner, value);
			owner.setReference(REF, i + 1, whichIs -> storeValue(whichIs, value));
			owner.upsertVia(session);
		}
	}

	/**
	 * Stores the value as the attribute, an array through the array overload; a `null` stores nothing.
	 */
	private static void storeValue(@Nonnull AttributesEditor<?, ?> editor, @Nullable Serializable value) {
		if (value instanceof Serializable[] array) {
			editor.setAttribute(ATTR, array);
		} else if (value != null) {
			editor.setAttribute(ATTR, value);
		}
	}

	/**
	 * Lists every case.
	 */
	@Nonnull
	private static List<Case> cases() {
		final List<Case> cases = new ArrayList<>(256);
		for (Fixture fixture : FIXTURES) {
			if (OUTSIDE_AGREEMENT.contains(fixture)) {
				continue;
			}
			cases.add(new Case(fixture, "attributeIs(NULL)", a -> attributeIs(a, AttributeSpecialValue.NULL), false));
			cases.add(
				new Case(fixture, "attributeIs(NOT_NULL)", a -> attributeIs(a, AttributeSpecialValue.NOT_NULL), false)
			);
		}

		// no data in the index: an error case only, nothing can discriminate
		cases.add(new Case(EMPTY, "between(10,5) inverted", a -> attributeBetween(a, 10, 5), true));
		cases.add(new Case(EMPTY, "between(5,10)", a -> attributeBetween(a, 5, 10), true));

		// Integer
		cases.add(new Case(INTEGER, "equals(10)", a -> attributeEquals(a, 10), false));
		cases.add(new Case(INTEGER, "equals(10L)", a -> attributeEquals(a, 10L), false));
		cases.add(new Case(INTEGER, "inSet(1,20)", a -> attributeInSet(a, new Integer[]{1, 20}), false));
		cases.add(new Case(INTEGER, "lessThan(10)", a -> attributeLessThan(a, 10), false));
		cases.add(new Case(INTEGER, "lessThanEquals(10)", a -> attributeLessThanEquals(a, 10), false));
		cases.add(new Case(INTEGER, "greaterThan(5)", a -> attributeGreaterThan(a, 5), false));
		cases.add(new Case(INTEGER, "greaterThanEquals(5)", a -> attributeGreaterThanEquals(a, 5), false));
		cases.add(new Case(INTEGER, "between(5,10)", a -> attributeBetween(a, 5, 10), false));
		cases.add(new Case(INTEGER, "between(null,10)", a -> attributeBetween(a, (Integer) null, 10), false));
		cases.add(new Case(INTEGER, "between(10,null)", a -> attributeBetween(a, 10, (Integer) null), false));
		cases.add(new Case(INTEGER, "between(10,5) inverted", a -> attributeBetween(a, 10, 5), true));
		cases.add(
			new Case(INTEGER, "between(5L,10L) Long bounds", a -> attributeBetween(a, (Serializable) 5L, 10L), false)
		);
		cases.add(
			new Case(
				INTEGER, "between(4.5,10.5) BigDecimal bounds",
				a -> attributeBetween(a, new BigDecimal("4.5"), new BigDecimal("10.5")), false
			)
		);
		cases.add(new Case(INTEGER, "lessThan(10L) Long probe", a -> attributeLessThan(a, 10L), false));

		// Long
		cases.add(new Case(LONG, "equals(10L)", a -> attributeEquals(a, 10L), false));
		cases.add(new Case(LONG, "equals(10) Integer probe", a -> attributeEquals(a, 10), false));
		cases.add(new Case(LONG, "inSet(1L,20L)", a -> attributeInSet(a, new Long[]{1L, 20L}), false));
		cases.add(new Case(LONG, "inSet(1,20) Integer probes", a -> attributeInSet(a, new Integer[]{1, 20}), false));
		cases.add(new Case(LONG, "lessThan(10L)", a -> attributeLessThan(a, 10L), false));
		cases.add(new Case(LONG, "lessThan(10) Integer probe", a -> attributeLessThan(a, 10), false));
		cases.add(new Case(LONG, "lessThanEquals(10L)", a -> attributeLessThanEquals(a, 10L), false));
		cases.add(new Case(LONG, "greaterThan(5L)", a -> attributeGreaterThan(a, 5L), false));
		cases.add(new Case(LONG, "greaterThanEquals(5L)", a -> attributeGreaterThanEquals(a, 5L), false));
		cases.add(new Case(LONG, "between(5L,10L)", a -> attributeBetween(a, 5L, 10L), false));
		cases.add(new Case(LONG, "between(5,10) Integer bounds", a -> attributeBetween(a, 5, 10), false));
		cases.add(new Case(LONG, "between(null,10L)", a -> attributeBetween(a, (Long) null, 10L), false));
		cases.add(new Case(LONG, "between(10L,null)", a -> attributeBetween(a, 10L, (Long) null), false));
		cases.add(new Case(LONG, "between(10L,5L) inverted", a -> attributeBetween(a, 10L, 5L), true));
		cases.add(new Case(LONG, "between(10,5) Integer inverted", a -> attributeBetween(a, 10, 5), true));

		// Decimal at 2 places: 1.005 and 1.0049 and 1.01 and -1.005 are stored
		cases.add(
			new Case(DECIMAL, "equals(1.01)", a -> attributeEquals(a, new BigDecimal("1.01")), false)
		);
		cases.add(
			new Case(DECIMAL, "equals(1.005)", a -> attributeEquals(a, new BigDecimal("1.005")), false)
		);
		cases.add(
			new Case(DECIMAL, "equals(1.0049)", a -> attributeEquals(a, new BigDecimal("1.0049")), false)
		);
		cases.add(
			new Case(DECIMAL, "equals(-1.005)", a -> attributeEquals(a, new BigDecimal("-1.005")), false)
		);
		cases.add(
			new Case(
				DECIMAL, "inSet(1.01,-1.005)",
				a -> attributeInSet(a, new BigDecimal[]{new BigDecimal("1.01"), new BigDecimal("-1.005")}), false
			)
		);
		cases.add(
			new Case(DECIMAL, "lessThan(1.01)", a -> attributeLessThan(a, new BigDecimal("1.01")), false)
		);
		cases.add(
			new Case(DECIMAL, "lessThanEquals(1.01)", a -> attributeLessThanEquals(a, new BigDecimal("1.01")), false)
		);
		cases.add(
			new Case(DECIMAL, "greaterThan(1.0)", a -> attributeGreaterThan(a, new BigDecimal("1.0")), false)
		);
		cases.add(
			new Case(
				DECIMAL, "greaterThanEquals(1.01)", a -> attributeGreaterThanEquals(a, new BigDecimal("1.01")), false
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(1.00,1.01)",
				a -> attributeBetween(a, new BigDecimal("1.00"), new BigDecimal("1.01")), false
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(1.0045,1.0055)",
				a -> attributeBetween(a, new BigDecimal("1.0045"), new BigDecimal("1.0055")), false
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(null,1.00)", a -> attributeBetween(a, (BigDecimal) null, new BigDecimal("1.00")),
				false
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(1.01,null)", a -> attributeBetween(a, new BigDecimal("1.01"), (BigDecimal) null),
				false
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(1.01,1.00) inverted",
				a -> attributeBetween(a, new BigDecimal("1.01"), new BigDecimal("1.00")), true
			)
		);
		cases.add(new Case(DECIMAL, "between(0,1) Integer bounds", a -> attributeBetween(a, 0, 1), false));
		cases.add(new Case(DECIMAL, "between(-2L,0L) Long bounds", a -> attributeBetween(a, -2L, 0L), false));
		cases.add(new Case(DECIMAL, "equals(1) Integer probe", a -> attributeEquals(a, 1), false));
		cases.add(
			new Case(
				DECIMAL, "lessThan(21474836.47) largest probe the key holds",
				a -> attributeLessThan(a, new BigDecimal("21474836.47")), false
			)
		);
		cases.add(new Case(DECIMAL, "lessThan(1) Integer probe", a -> attributeLessThan(a, 1), false));

		// String: NFD "cafe+U+0301", NFC "caf+U+00E9", "cafe", "bar", "x"+NFD
		cases.add(new Case(STRING, "equals(NFC cafe)", a -> attributeEquals(a, NFC_CAFE), false));
		cases.add(new Case(STRING, "equals(NFD cafe)", a -> attributeEquals(a, NFD_CAFE), false));
		cases.add(
			new Case(
				STRING, "inSet(NFC cafe,bar)",
				a -> attributeInSet(a, new String[]{NFC_CAFE, "bar"}), false
			)
		);
		cases.add(new Case(STRING, "lessThan(NFD cafe)", a -> attributeLessThan(a, NFD_CAFE), false));
		cases.add(new Case(STRING, "lessThan(NFC cafe)", a -> attributeLessThan(a, NFC_CAFE), false));
		cases.add(new Case(STRING, "lessThanEquals(NFC cafe)", a -> attributeLessThanEquals(a, NFC_CAFE), false));
		cases.add(new Case(STRING, "greaterThan(NFC cafe)", a -> attributeGreaterThan(a, NFC_CAFE), false));
		cases.add(
			new Case(STRING, "greaterThanEquals(NFC cafe)", a -> attributeGreaterThanEquals(a, NFC_CAFE), false)
		);
		cases.add(new Case(STRING, "between(cafe,NFC cafe)", a -> attributeBetween(a, "cafe", NFC_CAFE), false));
		cases.add(
			new Case(
				STRING, "between(NFC cafe,xNFC cafe)",
				a -> attributeBetween(a, NFC_CAFE, "x" + NFC_CAFE), false
			)
		);
		cases.add(new Case(STRING, "between(null,NFC cafe)", a -> attributeBetween(a, (String) null, NFC_CAFE), false));
		cases.add(new Case(STRING, "between(NFC cafe,null)", a -> attributeBetween(a, NFC_CAFE, (String) null), false));
		cases.add(new Case(STRING, "between(z,a) inverted", a -> attributeBetween(a, "z", "a"), true));
		cases.add(new Case(STRING, "startsWith(NFC caf+e acute)", a -> attributeStartsWith(a, NFC_CAFE), false));
		cases.add(new Case(STRING, "startsWith(NFD cafe)", a -> attributeStartsWith(a, NFD_CAFE), false));
		cases.add(new Case(STRING, "startsWith(ca)", a -> attributeStartsWith(a, "ca"), false));
		cases.add(new Case(STRING, "endsWith(NFC e acute)", a -> attributeEndsWith(a, "é"), false));
		cases.add(new Case(STRING, "endsWith(NFD e+acute)", a -> attributeEndsWith(a, "é"), false));
		cases.add(new Case(STRING, "contains(NFC f e acute)", a -> attributeContains(a, "fé"), false));
		cases.add(new Case(STRING, "contains(NFD f e+acute)", a -> attributeContains(a, "fé"), false));
		cases.add(new Case(STRING, "contains(ba)", a -> attributeContains(a, "ba"), false));

		// OffsetDateTime: 12:00+01:00 and 11:00Z are one instant
		final OffsetDateTime instantPlus = OffsetDateTime.parse("2026-01-01T12:00:00+01:00");
		final OffsetDateTime instantZ = OffsetDateTime.parse("2026-01-01T11:00:00Z");
		cases.add(new Case(OFFSET, "equals(11:00Z)", a -> attributeEquals(a, instantZ), false));
		cases.add(new Case(OFFSET, "equals(12:00+01:00)", a -> attributeEquals(a, instantPlus), false));
		cases.add(new Case(OFFSET, "inSet(12:00+01:00,13:00Z)", a -> attributeInSet(a, new OffsetDateTime[]{
			instantPlus, OffsetDateTime.parse("2026-01-01T13:00:00Z")
		}), false));
		cases.add(new Case(OFFSET, "lessThan(12:00+01:00)", a -> attributeLessThan(a, instantPlus), false));
		cases.add(new Case(OFFSET, "lessThanEquals(12:00+01:00)", a -> attributeLessThanEquals(a, instantPlus), false));
		cases.add(new Case(OFFSET, "greaterThan(11:00Z)", a -> attributeGreaterThan(a, instantZ), false));
		cases.add(
			new Case(
				OFFSET, "greaterThanEquals(12:00+01:00)",
				a -> attributeGreaterThanEquals(a, instantPlus), false
			)
		);
		cases.add(
			new Case(
				OFFSET, "between(11:30+01:00,12:30+01:00)",
				a -> attributeBetween(
					a,
					OffsetDateTime.parse("2026-01-01T11:30:00+01:00"),
					OffsetDateTime.parse("2026-01-01T12:30:00+01:00")
				),
				false
			)
		);
		cases.add(
			new Case(
				OFFSET, "between(null,11:00Z)", a -> attributeBetween(a, (OffsetDateTime) null, instantZ), false
			)
		);
		cases.add(
			new Case(
				OFFSET, "between(13:00Z,11:00Z) inverted",
				a -> attributeBetween(a, OffsetDateTime.parse("2026-01-01T13:00:00Z"), instantZ), true
			)
		);

		// LocalDateTime
		final LocalDateTime eleven = LocalDateTime.parse("2026-01-01T11:00:00");
		cases.add(new Case(LOCAL, "equals(11:00)", a -> attributeEquals(a, eleven), false));
		cases.add(new Case(LOCAL, "inSet(10:00,12:00)", a -> attributeInSet(a, new LocalDateTime[]{
			LocalDateTime.parse("2026-01-01T10:00:00"), LocalDateTime.parse("2026-01-01T12:00:00")
		}), false));
		cases.add(new Case(LOCAL, "lessThan(11:00)", a -> attributeLessThan(a, eleven), false));
		cases.add(new Case(LOCAL, "lessThanEquals(11:00)", a -> attributeLessThanEquals(a, eleven), false));
		cases.add(
			new Case(
				LOCAL, "greaterThan(10:00)",
				a -> attributeGreaterThan(a, LocalDateTime.parse("2026-01-01T10:00:00")), false
			)
		);
		cases.add(new Case(LOCAL, "greaterThanEquals(11:00)", a -> attributeGreaterThanEquals(a, eleven), false));
		cases.add(
			new Case(
				LOCAL, "between(10:00,11:00)",
				a -> attributeBetween(a, LocalDateTime.parse("2026-01-01T10:00:00"), eleven), false
			)
		);
		cases.add(
			new Case(
				LOCAL, "between(12:00,10:00) inverted",
				a -> attributeBetween(
					a, LocalDateTime.parse("2026-01-01T12:00:00"), LocalDateTime.parse("2026-01-01T10:00:00")
				),
				true
			)
		);

		// Boolean
		cases.add(new Case(BOOLEAN, "equals(true)", a -> attributeEquals(a, true), false));
		cases.add(new Case(BOOLEAN, "equals(false)", a -> attributeEquals(a, false), false));
		cases.add(new Case(BOOLEAN, "inSet(false)", a -> attributeInSet(a, new Boolean[]{false}), false));
		cases.add(new Case(BOOLEAN, "greaterThan(false)", a -> attributeGreaterThan(a, false), false));
		cases.add(new Case(BOOLEAN, "lessThan(true)", a -> attributeLessThan(a, true), false));

		// Integer[]: {1,2,3} {4,5} {} {2,9}
		cases.add(new Case(INTEGER_ARRAY, "equals(2)", a -> attributeEquals(a, 2), false));
		cases.add(
			new Case(
				INTEGER_ARRAY, "equals(new Integer[]{4,5})",
				a -> attributeEquals(a, new Integer[]{4, 5}), true
			)
		);
		cases.add(new Case(INTEGER_ARRAY, "inSet(5,9)", a -> attributeInSet(a, new Integer[]{5, 9}), false));
		cases.add(new Case(INTEGER_ARRAY, "lessThan(2)", a -> attributeLessThan(a, 2), false));
		cases.add(new Case(INTEGER_ARRAY, "lessThanEquals(1)", a -> attributeLessThanEquals(a, 1), false));
		cases.add(new Case(INTEGER_ARRAY, "greaterThan(8)", a -> attributeGreaterThan(a, 8), false));
		cases.add(new Case(INTEGER_ARRAY, "greaterThanEquals(5)", a -> attributeGreaterThanEquals(a, 5), false));
		cases.add(new Case(INTEGER_ARRAY, "between(4,5)", a -> attributeBetween(a, 4, 5), false));
		cases.add(new Case(INTEGER_ARRAY, "between(4L,5L) Long bounds", a -> attributeBetween(a, 4L, 5L), false));
		cases.add(new Case(INTEGER_ARRAY, "between(5,4) inverted", a -> attributeBetween(a, 5, 4), true));

		// String[]: {NFD cafe,x} {NFC cafe} {bar,baz} {}
		cases.add(new Case(STRING_ARRAY, "equals(NFC cafe)", a -> attributeEquals(a, NFC_CAFE), false));
		cases.add(new Case(STRING_ARRAY, "inSet(x,baz)", a -> attributeInSet(a, new String[]{"x", "baz"}), false));
		cases.add(new Case(STRING_ARRAY, "lessThan(NFC cafe)", a -> attributeLessThan(a, NFC_CAFE), false));
		cases.add(new Case(STRING_ARRAY, "greaterThan(bar)", a -> attributeGreaterThan(a, "bar"), false));
		cases.add(new Case(STRING_ARRAY, "between(bar,baz)", a -> attributeBetween(a, "bar", "baz"), false));
		cases.add(new Case(STRING_ARRAY, "startsWith(NFC cafe)", a -> attributeStartsWith(a, NFC_CAFE), false));
		cases.add(new Case(STRING_ARRAY, "endsWith(NFC e acute)", a -> attributeEndsWith(a, "é"), false));
		cases.add(new Case(STRING_ARRAY, "contains(ba)", a -> attributeContains(a, "ba"), false));

		// families whose values are ordered by the natural order of their type
		addOrderedCases(cases, BYTE, (byte) 1, (byte) 5, (byte) 10, (byte) 20);
		addOrderedCases(cases, SHORT, (short) 1, (short) 5, (short) 10, (short) 20);
		addOrderedCases(cases, CHARACTER, 'a', 'e', 'j', 't');
		addOrderedCases(
			cases, LOCAL_DATE,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-10"),
			LocalDate.parse("2026-01-20")
		);
		addOrderedCases(
			cases, LOCAL_TIME,
			LocalTime.parse("01:00:00"), LocalTime.parse("05:30:00"), LocalTime.parse("10:15:30"),
			LocalTime.parse("20:00:00")
		);
		addOrderedCases(
			cases, CURRENCY,
			Currency.getInstance("CZK"), Currency.getInstance("EUR"), Currency.getInstance("GBP"),
			Currency.getInstance("USD")
		);
		addOrderedCases(
			cases, LOCALE,
			Locale.forLanguageTag("cs"), Locale.forLanguageTag("de"), Locale.forLanguageTag("en"),
			Locale.forLanguageTag("fr")
		);

		// the rounding turns the inverted bounds into one value, so the interval is not inverted
		cases.add(
			new Case(
				DECIMAL, "between(1.004,1.001) bounds equal once rounded",
				a -> attributeBetween(a, new BigDecimal("1.004"), new BigDecimal("1.001")), false
			)
		);

		// the array a client may pass as the value of a scalar constraint
		cases.add(new Case(INTEGER, "equals(new Integer[]{1,5})", a -> attributeEquals(a, new Integer[]{1, 5}), true));
		cases.add(new Case(INTEGER, "equals(new Integer[]{10})", a -> attributeEquals(a, new Integer[]{10}), true));
		cases.add(new Case(INTEGER, "equals(new Integer[0])", a -> attributeEquals(a, new Integer[0]), true));
		cases.add(
			new Case(INTEGER, "lessThan(new Integer[]{10})", a -> attributeLessThan(a, new Integer[]{10}), true)
		);
		cases.add(new Case(INTEGER, "lessThan(new Integer[0])", a -> attributeLessThan(a, new Integer[0]), true));
		cases.add(
			new Case(
				STRING, "equals(new String[]{NFC cafe,bar})",
				a -> attributeEquals(a, new String[]{NFC_CAFE, "bar"}), true
			)
		);
		cases.add(new Case(STRING, "equals(new String[0])", a -> attributeEquals(a, new String[0]), true));
		cases.add(
			new Case(STRING, "greaterThan(new String[]{bar})", a -> attributeGreaterThan(a, new String[]{"bar"}), true)
		);
		cases.add(new Case(STRING, "greaterThan(new String[0])", a -> attributeGreaterThan(a, new String[0]), true));

		// an inverted interval over a range attribute matches nothing, whatever the type of the range
		cases.add(new Case(INTEGER_RANGE, "between(10,5) inverted", a -> attributeBetween(a, 10, 5), true));
		cases.add(new Case(LONG_RANGE, "between(10L,5L) inverted", a -> attributeBetween(a, 10L, 5L), true));
		cases.add(
			new Case(
				DECIMAL_RANGE, "between(2.0,1.0) inverted",
				a -> attributeBetween(a, new BigDecimal("2.0"), new BigDecimal("1.0")), true
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "between(2026-02-10,2026-01-10) inverted",
				a -> attributeBetween(
					a, OffsetDateTime.parse("2026-02-10T00:00:00Z"), OffsetDateTime.parse("2026-01-10T00:00:00Z")
				),
				true
			)
		);

		// number ranges
		addNumberRangeCases(cases, INTEGER_RANGE, new Serializable[]{4, 11, 15}, new Serializable[]{4L, 11L, 15L});
		addNumberRangeCases(cases, LONG_RANGE, new Serializable[]{4, 11, 15}, new Serializable[]{4L, 11L, 15L});
		for (Fixture fixture : List.of(INTEGER_RANGE, LONG_RANGE)) {
			cases.add(
				new Case(
					fixture, "inRange(4.5) BigDecimal probe",
					a -> attributeInRange(a, new BigDecimal("4.5")), false
				)
			);
			cases.add(
				new Case(
					fixture, "inRange(11.0) BigDecimal probe",
					a -> attributeInRange(a, new BigDecimal("11.0")), false
				)
			);
			cases.add(
				new Case(
					fixture, "inRange(15.5) BigDecimal probe",
					a -> attributeInRange(a, new BigDecimal("15.5")), false
				)
			);
		}

		cases.add(new Case(DECIMAL_RANGE, "inRange(Integer 2)", a -> attributeInRange(a, 2), false));
		cases.add(new Case(DECIMAL_RANGE, "inRange(Integer 3)", a -> attributeInRange(a, 3), false));
		cases.add(new Case(DECIMAL_RANGE, "inRange(Long 2)", a -> attributeInRange(a, 2L), false));
		cases.add(new Case(DECIMAL_RANGE, "inRange(Long 3)", a -> attributeInRange(a, 3L), false));
		for (String probe : new String[]{
			"1.005", "1.006", "1.7", "1.9941", "1.9949", "2.00", "2.001", "2.0049", "2.005", "2.0051", "2.5", "3.004"
		}) {
			cases.add(
				new Case(
					DECIMAL_RANGE, "inRange(" + probe + ")", a -> attributeInRange(a, new BigDecimal(probe)), false
				)
			);
		}
		cases.add(
			new Case(
				DECIMAL_RANGE, "between(1,2) Integer bounds", a -> attributeBetween(a, 1, 2), false
			)
		);
		cases.add(
			new Case(
				DECIMAL_RANGE, "between(1.0,2.0) BigDecimal bounds",
				a -> attributeBetween(a, new BigDecimal("1.0"), new BigDecimal("2.0")), false
			)
		);
		cases.add(
			new Case(
				DECIMAL_RANGE, "equals(range [1.005,2.005])",
				a -> attributeEquals(
					a, BigDecimalNumberRange.between(new BigDecimal("1.005"), new BigDecimal("2.005"))
				),
				false
			)
		);
		cases.add(
			new Case(
				DECIMAL_RANGE, "equals(range [1.01,2.01]) rescaled",
				a -> attributeEquals(a, BigDecimalNumberRange.between(new BigDecimal("1.01"), new BigDecimal("2.01"))),
				false
			)
		);

		// date time range
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-01-20T00:00Z)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-01-20T00:00:00Z")), false
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-01-20T01:00+01:00)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-01-20T01:00:00+01:00")), false
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-02-05T00:00Z)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-02-05T00:00:00Z")), false
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-01-31T00:00+01:00)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-01-31T00:00:00+01:00")), false
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-02-01T00:00+01:00)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-02-01T00:00:00+01:00")), false
			)
		);
		return cases;
	}

	/**
	 * Adds the comparison, set and interval cases of a fixture whose four values are given in ascending order.
	 */
	private static void addOrderedCases(
		@Nonnull List<Case> cases,
		@Nonnull Fixture fixture,
		@Nonnull Serializable first,
		@Nonnull Serializable second,
		@Nonnull Serializable third,
		@Nonnull Serializable fourth
	) {
		cases.add(new Case(fixture, "equals(" + second + ")", a -> attributeEquals(a, second), false));
		cases.add(
			new Case(
				fixture, "inSet(" + first + "," + fourth + ")",
				a -> attributeInSet(a, first, fourth), false
			)
		);
		cases.add(new Case(fixture, "lessThan(" + third + ")", a -> attributeLessThan(a, third), false));
		cases.add(new Case(fixture, "lessThanEquals(" + second + ")", a -> attributeLessThanEquals(a, second), false));
		cases.add(new Case(fixture, "greaterThan(" + second + ")", a -> attributeGreaterThan(a, second), false));
		cases.add(
			new Case(fixture, "greaterThanEquals(" + third + ")", a -> attributeGreaterThanEquals(a, third), false)
		);
		cases.add(
			new Case(
				fixture, "between(" + second + "," + third + ")",
				a -> attributeBetween(a, second, third), false
			)
		);
		cases.add(
			new Case(
				fixture, "between(null," + second + ")", a -> attributeBetween(a, (Serializable) null, second), false
			)
		);
		cases.add(
			new Case(
				fixture, "between(" + third + ",null)", a -> attributeBetween(a, third, (Serializable) null), false
			)
		);
		cases.add(
			new Case(
				fixture, "between(" + third + "," + second + ") inverted", a -> attributeBetween(a, third, second), true
			)
		);
	}

	/**
	 * Adds the `inRange` and `between` cases of an integral number range fixture.
	 */
	private static void addNumberRangeCases(
		@Nonnull List<Case> cases,
		@Nonnull Fixture fixture,
		@Nonnull Serializable[] integerProbes,
		@Nonnull Serializable[] longProbes
	) {
		for (Serializable probe : integerProbes) {
			cases.add(
				new Case(
					fixture, "inRange(Integer " + probe + ")",
					a -> attributeInRange(a, (Number) probe), false
				)
			);
		}
		for (Serializable probe : longProbes) {
			cases.add(
				new Case(
					fixture, "inRange(Long " + probe + ")",
					a -> attributeInRange(a, (Number) probe), false
				)
			);
		}
		cases.add(new Case(fixture, "between(2,11) Integer bounds", a -> attributeBetween(a, 2, 11), false));
		cases.add(new Case(fixture, "between(2L,11L) Long bounds", a -> attributeBetween(a, 2L, 11L), false));
	}
}
