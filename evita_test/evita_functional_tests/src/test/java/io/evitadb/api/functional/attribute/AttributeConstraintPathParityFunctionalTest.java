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
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeInSet;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesEditor;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins that the four ways the engine evaluates one attribute filter constraint on one stored value agree:
 *
 * - **index** - the translator probes `FilterIndex` / `RangeIndex` (query steered by `PREFER_INDEX_SCAN`),
 * - **prefetch** - the translator builds an `AttributeBitmapFilter` over the matcher of its constraint and runs it over
 *   the prefetched entity bodies (query steered by `PREFER_PREFETCHING` plus an `entityPrimaryKeyInSet`),
 * - **row** - `referenceContent(r, filterBy(...))` evaluates the constraint on every reference row,
 * - **entity filtering** - a query naming no collection evaluates an attribute on the entities found by their
 *   globally unique code, whatever the attribute is.
 *
 * Every fixture stores one owner entity per value plus one owner without a value. An owner carries its value twice - as
 * its own attribute (read by the index, prefetch and entity filtering arms) and as the attribute of its single
 * reference row of reference `r` (read by the row arm), both at the same `indexedDecimalPlaces`. The outcome of an
 * arm is the set of owner primary keys the constraint selects, or the exception it throws.
 *
 * Every case names what all four arms must answer: either that it selects some owners but not all of them, with no arm
 * answering an error, or the exact outcome - the refusal, `[]` or the set. A case that must select cannot pass on four
 * identical refusals, and a case that must refuse cannot pass on a set.
 *
 * The outcome of the index and of the prefetch arm carries the path evidence. The index arm must not have prefetched
 * the entity bodies. The prefetch arm must have, for every constraint whose typed translation has a prefetch
 * alternative: a prefetched query answers every `SelectionFormula` from its alternative
 * (`SelectionFormula#computeInternal` consults `isPrefetchExecution()`), so the `EXECUTION_PREFETCH` step of the query
 * telemetry says the per-value test ran. Typed `attributeEquals` and `attributeInSet` have no alternative - the
 * engine reads the index for them even when the bodies are prefetched - so the prefetch arm of their cases repeats the
 * index arm; their test names say so and the prefetch mark is not required. A `referenceHaving` leaf never runs a
 * prefetch alternative and is not covered here: the row arm tests the `referenceContent` filter, not `referenceHaving`.
 *
 * The assertion itself lives in {@link #assertAgreement}, and the negative guards below feed it outcomes that must
 * fail, so the gate is known to catch the faults it is there for.
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
	private static final String CATALOG_DECIMAL_ATTRIBUTE = "catalogDecimal";
	private static final String UNIQUE_DECIMAL_ATTRIBUTE = "uniqueDecimal";
	private static final Locale CZECH = Locale.forLanguageTag("cs");

	/**
	 * How the attribute of a fixture is indexed.
	 */
	private enum Indexing {
		/**
		 * A local attribute with a filter index.
		 */
		FILTERABLE,
		/**
		 * A local attribute no index holds.
		 */
		NOT_INDEXED,
		/**
		 * A local attribute with a unique index and no filter index of its own.
		 */
		UNIQUE_ONLY,
		/**
		 * An attribute of the catalog, globally unique, that the owners share.
		 */
		CATALOG_UNIQUE
	}

	/**
	 * Values stored by one family of owners.
	 *
	 * @param name      name of the family, part of the entity type
	 * @param type      type of the attribute
	 * @param places    `indexedDecimalPlaces` of the attribute, 0 when not decimal
	 * @param values    one value per owner, owners are numbered from 1; one more owner follows with no value
	 * @param indexing  how the attribute of the owner is indexed; the attribute of its reference is filterable unless
	 *                  nothing is indexed
	 * @param populated whether the collection holds any owner at all
	 * @param locale    the locale the values are stored in and the queries ask for, NULL for a not localized attribute
	 * @param attribute name of the attribute, on the owner and on its reference alike
	 */
	private record Fixture(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		int places,
		@Nonnull Serializable[] values,
		@Nonnull Indexing indexing,
		boolean populated,
		@Nullable Locale locale,
		@Nonnull String attribute
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
			this(name, type, places, values, Indexing.FILTERABLE, true, null, ATTR);
		}

		/**
		 * Creates a fixture of the given indexing, not localized, named {@link #ATTR}.
		 */
		Fixture(
			@Nonnull String name,
			@Nonnull Class<? extends Serializable> type,
			int places,
			@Nonnull Serializable[] values,
			@Nonnull Indexing indexing,
			boolean populated
		) {
			this(name, type, places, values, indexing, populated, null, ATTR);
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

		/**
		 * Returns the constraints with the locale the values are stored in added behind them when the fixture is
		 * localized.
		 */
		@Nonnull
		FilterConstraint[] withLocale(@Nonnull FilterConstraint... constraints) {
			if (this.locale == null) {
				return constraints;
			}
			final FilterConstraint[] result = new FilterConstraint[constraints.length + 1];
			System.arraycopy(constraints, 0, result, 0, constraints.length);
			result[constraints.length] = entityLocaleEquals(this.locale);
			return result;
		}
	}

	/**
	 * What a case must answer on every arm.
	 *
	 * @param outcome the outcome all four arms must print - the set of owner primary keys, `[]` or the refusal; NULL
	 *                when the case must select some of the owners but not all of them and no arm may answer an error
	 * @param prefix  whether the outcome is only the beginning of what the arms print, which is for a refusal whose
	 *                message lists what the engine supports
	 */
	private record Expectation(@Nullable String outcome, boolean prefix) {

		/**
		 * Creates the expectation of an exact outcome.
		 */
		Expectation(@Nullable String outcome) {
			this(outcome, false);
		}

		/**
		 * Tells whether the outcome printed by an arm is the expected one.
		 */
		boolean accepts(@Nonnull String printed) {
			return this.outcome != null
				&& (this.prefix ? printed.startsWith(this.outcome) : this.outcome.equals(printed));
		}
	}

	/**
	 * The case must select some owners but not all of them, so it discriminates.
	 */
	private static final Expectation SELECTS = new Expectation(null);
	/**
	 * The case must select no owner on any arm.
	 */
	private static final Expectation NOTHING = new Expectation("[]");
	/**
	 * The refusal of an array passed as the value of a constraint over a scalar attribute. The message goes on with the
	 * list of the supported types, which this test does not pin.
	 */
	private static final Expectation UNSUPPORTED_ARRAY = new Expectation(
		"ERR UnsupportedDataTypeException: Type [Ljava.lang.Integer; is not supported.", true
	);
	private static final String FROM_TO_REFUSAL = "ERR EvitaInvalidUsageException: From must be lower than to: ";
	private static final String PREFETCHED_MARK = " [prefetched]";
	private static final String NOT_PREFETCHED_MARK = " [NOT-prefetched]";

	/**
	 * Returns the expectation of every arm refusing an interval whose bounds are inverted, with the bounds printed as
	 * the refusal prints them.
	 */
	@Nonnull
	private static Expectation inverted(@Nonnull Object from, @Nonnull Object to) {
		return new Expectation(FROM_TO_REFUSAL + from + " vs. " + to);
	}

	/**
	 * Returns the expectation of every arm printing the given outcome.
	 */
	@Nonnull
	private static Expectation expect(@Nonnull String outcome) {
		return new Expectation(outcome);
	}

	/**
	 * One constraint over one fixture.
	 *
	 * @param fixture     the fixture
	 * @param label       human description of the constraint and its values
	 * @param constraint  creates the constraint over an attribute name
	 * @param expectation what every arm must answer
	 */
	private record Case(
		@Nonnull Fixture fixture,
		@Nonnull String label,
		@Nonnull Function<String, FilterConstraint> constraint,
		@Nonnull Expectation expectation
	) {

		/**
		 * Tells whether the typed translation of the constraint has a prefetch alternative. `attributeEquals` and
		 * `attributeInSet` have none: the engine reads the index for them even when the entity bodies are prefetched,
		 * so the prefetch arm of such a case repeats the index arm.
		 */
		boolean hasPrefetchAlternative() {
			final FilterConstraint filter = this.constraint.apply(this.fixture.attribute());
			return !(filter instanceof AttributeEquals) && !(filter instanceof AttributeInSet);
		}

		/**
		 * Tells whether the lookup of the index arm prefetches the entity bodies itself. It does for `attributeEquals`
		 * and `attributeInSet` over an attribute of the catalog, where the plan was observed to fetch the bodies of the
		 * entities the global unique index resolves although the index is preferred.
		 */
		boolean indexLookupPrefetches() {
			return this.fixture.indexing() == Indexing.CATALOG_UNIQUE && !hasPrefetchAlternative();
		}

		/**
		 * Returns the name of the test of the case.
		 */
		@Nonnull
		String displayName() {
			return this.fixture.name() + " " + this.label
				+ (hasPrefetchAlternative() ? "" : " (prefetch arm reads the index)");
		}
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
		"NoEntities", Integer.class, 0, new Serializable[0], Indexing.FILTERABLE, false
	);
	/**
	 * A decimal attribute no index holds, one of its values being beyond what the index keys could encode.
	 */
	private static final Fixture DECIMAL_NOT_FILTERABLE = new Fixture(
		"DecimalNotFilterable", BigDecimal.class, 2,
		new Serializable[]{new BigDecimal("1.5"), new BigDecimal("123456789012.5")}, Indexing.NOT_INDEXED, true
	);
	/**
	 * A localized attribute read in Czech, whose collation orders "ch" after "h". The values are "ca", "cha", "ha",
	 * "ia" and a decomposed "cafe" with an acute accent.
	 */
	private static final Fixture LOCALIZED_STRING = new Fixture(
		"LocalizedString", String.class, 0,
		new Serializable[]{"ca", "cha", "ha", "ia", NFD_CAFE}, Indexing.FILTERABLE, true, CZECH, ATTR
	);
	/**
	 * A decimal attribute of the catalog, shared by the owners.
	 */
	private static final Fixture CATALOG_DECIMAL = new Fixture(
		"CatalogDecimal", BigDecimal.class, 2,
		new Serializable[]{
			new BigDecimal("1.01"), new BigDecimal("1.50"), new BigDecimal("2.25"), new BigDecimal("9.99")
		},
		Indexing.CATALOG_UNIQUE, true, null, CATALOG_DECIMAL_ATTRIBUTE
	);
	/**
	 * A decimal attribute that is unique and nothing else.
	 */
	private static final Fixture UNIQUE_DECIMAL = new Fixture(
		"UniqueDecimal", BigDecimal.class, 2,
		new Serializable[]{
			new BigDecimal("1.01"), new BigDecimal("1.50"), new BigDecimal("2.25"), new BigDecimal("9.99")
		},
		Indexing.UNIQUE_ONLY, true, null, UNIQUE_DECIMAL_ATTRIBUTE
	);
	/**
	 * A decimal attribute indexed with two decimal places, one of two types holding the attribute at another scale.
	 */
	private static final Fixture SCALE_TWO = new Fixture(
		"ScaleTwo", BigDecimal.class, 2,
		new Serializable[]{new BigDecimal("1.4"), new BigDecimal("1.45"), new BigDecimal("9.9")}
	);
	/**
	 * A decimal attribute indexed with no decimal place, so 1.2 and 1.4 are one value, 1.
	 */
	private static final Fixture SCALE_ZERO = new Fixture(
		"ScaleZero", BigDecimal.class, 0,
		new Serializable[]{
			new BigDecimal("1.2"), new BigDecimal("1.4"), new BigDecimal("1.6"), new BigDecimal("9.9")
		}
	);
	private static final List<Fixture> FIXTURES = List.of(
		EMPTY, NO_ENTITIES, INTEGER, LONG, DECIMAL, DECIMAL_NOT_FILTERABLE, STRING, OFFSET, LOCAL, BOOLEAN,
		INTEGER_ARRAY, STRING_ARRAY, INTEGER_RANGE, LONG_RANGE, DECIMAL_RANGE, DATE_RANGE,
		BYTE, SHORT, CHARACTER, LOCAL_DATE, LOCAL_TIME, CURRENCY, LOCALE,
		LOCALIZED_STRING, CATALOG_DECIMAL, UNIQUE_DECIMAL, SCALE_TWO, SCALE_ZERO
	);
	/**
	 * The fixtures whose cases are not part of the agreement of the four arms.
	 */
	private static final Set<Fixture> OUTSIDE_AGREEMENT = Set.of(
		EMPTY, NO_ENTITIES, DECIMAL_NOT_FILTERABLE, SCALE_TWO, SCALE_ZERO
	);

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
			.withAttribute(
				CATALOG_DECIMAL_ATTRIBUTE, BigDecimal.class,
				thatIs -> thatIs.uniqueGloballyInScope(Scope.values()).nullable().indexDecimalPlaces(2)
			)
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
			it -> DynamicTest.dynamicTest(it.displayName(), () -> verify(it))
		);
	}

	/**
	 * Runs the case through the four arms and compares the outcomes.
	 */
	private void verify(@Nonnull Case theCase) {
		final String[] outcomes = outcomesOf(theCase.fixture(), theCase.constraint());
		assertAgreement(theCase, outcomes[0], outcomes[1], outcomes[2], outcomes[3]);
	}

	/**
	 * Asserts the four outcomes of a case are what the case expects.
	 *
	 * The outcomes of the index and of the prefetch arm carry a mark telling whether the plan prefetched the entity
	 * bodies, and both marks are asserted: the index arm must not have prefetched, otherwise it did not probe the
	 * index; the prefetch arm must have prefetched when the constraint has a prefetch alternative. A prefetched query
	 * answers every `SelectionFormula` from its alternative (`SelectionFormula#computeInternal` consults
	 * `isPrefetchExecution()`), so the `EXECUTION_PREFETCH` step of the query telemetry is the evidence that the
	 * per-value test ran. A refusal prints no mark, the query fails before any plan is chosen.
	 *
	 * A case that must select owners (see {@link #SELECTS}) is answered by no arm with an error and selects some of the
	 * owners but not all of them; every other case names the outcome all four arms must print.
	 *
	 * @param theCase         the case
	 * @param index           outcome of the index arm
	 * @param prefetch        outcome of the prefetch arm
	 * @param row             outcome of the reference row arm
	 * @param entityFiltering outcome of the entity filtering arm
	 */
	private static void assertAgreement(
		@Nonnull Case theCase,
		@Nonnull String index,
		@Nonnull String prefetch,
		@Nonnull String row,
		@Nonnull String entityFiltering
	) {
		final String all = "I=" + index + ", P=" + prefetch + ", R=" + row + ", E=" + entityFiltering;
		if (index.endsWith(PREFETCHED_MARK) && !theCase.indexLookupPrefetches()) {
			fail("The index arm prefetched the entity bodies, so it did not probe the index: " + all);
		}
		if (theCase.hasPrefetchAlternative() && prefetch.endsWith(NOT_PREFETCHED_MARK)) {
			fail("The prefetch arm did not prefetch the entity bodies, so the alternative did not run: " + all);
		}
		final String[] outcomes = {stripPrefetchMark(index), stripPrefetchMark(prefetch), row, entityFiltering};
		String expected = theCase.expectation().outcome();
		if (expected == null) {
			for (String outcome : outcomes) {
				if (outcome.startsWith("ERR")) {
					fail("The case must select owners but an arm answered an error: " + all);
				}
			}
			final int owners = theCase.fixture().owners().length;
			final int selected = parseSet(outcomes[0]).size();
			if (selected == 0 || selected == owners) {
				fail("Vacuous case - the index arm selects " + selected + " of " + owners + " owners: " + all);
			}
			expected = outcomes[0];
		}
		final Expectation expectation = new Expectation(expected, theCase.expectation().prefix());
		for (String outcome : outcomes) {
			if (!expectation.accepts(outcome)) {
				fail("Expected " + expected + " on every arm: " + all);
			}
		}
	}

	@Test
	@DisplayName("a prefetch arm that did not prefetch fails the case of a constraint with an alternative")
	void shouldFailWhenPrefetchArmDidNotPrefetch() {
		final Case theCase = new Case(INTEGER, "between(5,10)", a -> attributeBetween(a, 5, 10), SELECTS);
		assertDoesNotThrow(() -> assertAgreement(theCase, "[2, 3]", "[2, 3]", "[2, 3]", "[2, 3]"));
		assertThrows(
			AssertionError.class,
			() -> assertAgreement(theCase, "[2, 3]", "[2, 3]" + NOT_PREFETCHED_MARK, "[2, 3]", "[2, 3]")
		);
	}

	@Test
	@DisplayName("a missing prefetch is no failure for a constraint with no alternative")
	void shouldAcceptPrefetchArmReadingIndexForConstraintWithoutAlternative() {
		final Case equals = new Case(INTEGER, "equals(10)", a -> attributeEquals(a, 10), SELECTS);
		final Case inSet = new Case(INTEGER, "inSet(1,20)", a -> attributeInSet(a, new Integer[]{1, 20}), SELECTS);
		assertFalse(equals.hasPrefetchAlternative());
		assertFalse(inSet.hasPrefetchAlternative());
		assertDoesNotThrow(
			() -> assertAgreement(equals, "[3]", "[3]" + NOT_PREFETCHED_MARK, "[3]", "[3]")
		);
		assertTrue(
			new Case(INTEGER, "lessThan(10)", a -> attributeLessThan(a, 10), SELECTS).hasPrefetchAlternative()
		);
	}

	@Test
	@DisplayName("an index arm that prefetched fails the case")
	void shouldFailWhenIndexArmPrefetched() {
		final Case theCase = new Case(INTEGER, "between(5,10)", a -> attributeBetween(a, 5, 10), SELECTS);
		assertThrows(
			AssertionError.class,
			() -> assertAgreement(theCase, "[2, 3]" + PREFETCHED_MARK, "[2, 3]", "[2, 3]", "[2, 3]")
		);
	}

	@Test
	@DisplayName("a case that must select owners fails when every arm answers the same error")
	void shouldFailWhenEveryArmRefusesACaseThatMustSelect() {
		final Case theCase = new Case(INTEGER, "between(5,10)", a -> attributeBetween(a, 5, 10), SELECTS);
		final String error = "ERR EvitaInvalidUsageException: refused";
		assertThrows(AssertionError.class, () -> assertAgreement(theCase, error, error, error, error));
	}

	@Test
	@DisplayName("a case that must select owners fails when it selects none or all of them")
	void shouldFailWhenCaseIsVacuous() {
		final Case theCase = new Case(INTEGER, "between(5,10)", a -> attributeBetween(a, 5, 10), SELECTS);
		assertThrows(AssertionError.class, () -> assertAgreement(theCase, "[]", "[]", "[]", "[]"));
		final String every = "[1, 2, 3, 4, 5]";
		assertThrows(AssertionError.class, () -> assertAgreement(theCase, every, every, every, every));
	}

	@Test
	@DisplayName("a case that expects a refusal fails when an arm answers a set")
	void shouldFailWhenExpectedRefusalIsAnsweredWithASet() {
		final String refusal = FROM_TO_REFUSAL + "10 vs. 5";
		final Case theCase = new Case(
			INTEGER, "between(10,5) inverted", a -> attributeBetween(a, 10, 5), new Expectation(refusal)
		);
		assertDoesNotThrow(() -> assertAgreement(theCase, refusal, refusal, refusal, refusal));
		assertThrows(AssertionError.class, () -> assertAgreement(theCase, refusal, refusal, "[]", refusal));
		assertThrows(AssertionError.class, () -> assertAgreement(theCase, "[]", "[]", "[]", "[]"));
	}

	@Test
	@DisplayName("a decimal beyond the key of the index is refused by index, prefetch and entity filtering")
	void shouldRefuseDecimalBeyondIndexKeyExceptOnReferenceRow() {
		final String tooBig = "99999999999";
		final String refusal = "ERR EvitaInvalidUsageException: Cannot convert big decimal " + tooBig
			+ " to exact integer by using 2 decimal places!";
		final BigDecimal huge = new BigDecimal(tooBig);
		final BigDecimal one = new BigDecimal("1.00");
		final Map<String, Function<String, FilterConstraint>> constraints = new LinkedHashMap<>();
		final Map<String, String> rowAnswers = new LinkedHashMap<>();
		constraints.put("equals(huge)", a -> attributeEquals(a, huge));
		rowAnswers.put("equals(huge)", "[]");
		constraints.put("inSet(1.01,huge)", a -> attributeInSet(a, new BigDecimal("1.01"), huge));
		rowAnswers.put("inSet(1.01,huge)", "[1, 3]");
		constraints.put("lessThan(huge)", a -> attributeLessThan(a, huge));
		rowAnswers.put("lessThan(huge)", "[1, 2, 3, 4]");
		constraints.put("lessThanEquals(huge)", a -> attributeLessThanEquals(a, huge));
		rowAnswers.put("lessThanEquals(huge)", "[1, 2, 3, 4]");
		constraints.put("greaterThan(huge)", a -> attributeGreaterThan(a, huge));
		rowAnswers.put("greaterThan(huge)", "[]");
		constraints.put("greaterThanEquals(huge)", a -> attributeGreaterThanEquals(a, huge));
		rowAnswers.put("greaterThanEquals(huge)", "[]");
		constraints.put("between(1.00,huge)", a -> attributeBetween(a, one, huge));
		rowAnswers.put("between(1.00,huge)", "[1, 2, 3]");
		constraints.put("between(null,huge)", a -> attributeBetween(a, null, huge));
		rowAnswers.put("between(null,huge)", "[1, 2, 3, 4]");
		constraints.put("between(-huge,1.00)", a -> attributeBetween(a, huge.negate(), one));
		rowAnswers.put("between(-huge,1.00)", "[2, 4]");
		for (Map.Entry<String, Function<String, FilterConstraint>> entry : constraints.entrySet()) {
			final String[] outcomes = outcomesOf(DECIMAL, entry.getValue());
			final String label = entry.getKey();
			final String expectedRefusal = label.contains("-huge") ?
				refusal.replace(tooBig, "-" + tooBig) : refusal;
			assertEquals(expectedRefusal, outcomes[0], label + " on the index");
			assertEquals(expectedRefusal, outcomes[1], label + " on the prefetch");
			// the one difference: a reference attribute needs no index, so the row compares wide decimals
			assertEquals(rowAnswers.get(label), outcomes[2], label + " on the reference row");
			assertEquals(expectedRefusal, outcomes[3], label + " on the entity filtering");
		}
	}

	@Test
	@DisplayName("a catalog decimal attribute is compared alike by index, prefetch, reference row and entity filtering")
	void shouldCompareCatalogDecimalAttributeOnEveryPath() {
		final Map<String, Function<String, FilterConstraint>> constraints = new LinkedHashMap<>();
		final Map<String, String> answers = new LinkedHashMap<>();
		constraints.put("lessThan(2.25)", a -> attributeLessThan(a, new BigDecimal("2.25")));
		answers.put("lessThan(2.25)", "[1, 2]");
		constraints.put("greaterThanEquals(2.25)", a -> attributeGreaterThanEquals(a, new BigDecimal("2.25")));
		answers.put("greaterThanEquals(2.25)", "[3, 4]");
		constraints.put("between(1.00,2.00)", a -> attributeBetween(a, new BigDecimal("1.00"), new BigDecimal("2.00")));
		answers.put("between(1.00,2.00)", "[1, 2]");
		for (Map.Entry<String, Function<String, FilterConstraint>> entry : constraints.entrySet()) {
			final String[] outcomes = outcomesOf(CATALOG_DECIMAL, entry.getValue());
			assertEquals(answers.get(entry.getKey()), outcomes[0], entry.getKey() + " on the index");
			assertEquals(answers.get(entry.getKey()), outcomes[1], entry.getKey() + " on the prefetch");
			assertEquals(answers.get(entry.getKey()), outcomes[2], entry.getKey() + " on the reference row");
			assertEquals(answers.get(entry.getKey()), outcomes[3], entry.getKey() + " on the entity filtering");
		}
	}

	@Test
	@DisplayName("a decimal beyond the key is refused on a catalog attribute and on a unique-only one, not by the row")
	void shouldRefuseDecimalBeyondIndexKeyOnCatalogAndUniqueOnlyAttribute() {
		final String tooBig = "99999999999";
		final String refusal = "ERR EvitaInvalidUsageException: Cannot convert big decimal " + tooBig
			+ " to exact integer by using 2 decimal places!";
		final BigDecimal huge = new BigDecimal(tooBig);
		final Map<String, Function<String, FilterConstraint>> constraints = new LinkedHashMap<>();
		final Map<String, String> rowAnswers = new LinkedHashMap<>();
		constraints.put("equals(huge)", a -> attributeEquals(a, huge));
		rowAnswers.put("equals(huge)", "[]");
		constraints.put("inSet(1.01,huge)", a -> attributeInSet(a, new BigDecimal("1.01"), huge));
		rowAnswers.put("inSet(1.01,huge)", "[1]");
		constraints.put("lessThan(huge)", a -> attributeLessThan(a, huge));
		rowAnswers.put("lessThan(huge)", "[1, 2, 3, 4]");
		constraints.put("between(1.00,huge)", a -> attributeBetween(a, new BigDecimal("1.00"), huge));
		rowAnswers.put("between(1.00,huge)", "[1, 2, 3, 4]");
		for (Fixture fixture : List.of(CATALOG_DECIMAL, UNIQUE_DECIMAL)) {
			for (Map.Entry<String, Function<String, FilterConstraint>> entry : constraints.entrySet()) {
				final String[] outcomes = outcomesOf(fixture, entry.getValue());
				final String label = fixture.name() + " " + entry.getKey();
				assertEquals(refusal, outcomes[0], label + " on the index");
				assertEquals(refusal, outcomes[1], label + " on the prefetch");
				assertEquals(rowAnswers.get(entry.getKey()), outcomes[2], label + " on the reference row");
				assertEquals(refusal, outcomes[3], label + " on the entity filtering");
			}
		}
	}

	@Test
	@DisplayName("the largest decimal the key of the index holds is no reason to refuse")
	void shouldAnswerDecimalOfTheLargestIndexKey() {
		final BigDecimal largest = new BigDecimal("21474836.47");
		final String[] outcomes = outcomesOf(DECIMAL, a -> attributeLessThan(a, largest));
		for (String outcome : outcomes) {
			assertEquals("[1, 2, 3, 4]", stripPrefetchMark(outcome));
		}
		final BigDecimal beyond = new BigDecimal("21474836.48");
		final String[] refused = outcomesOf(DECIMAL, a -> attributeLessThan(a, beyond));
		final String refusal = "ERR EvitaInvalidUsageException: Cannot convert big decimal 21474836.48"
			+ " to exact integer by using 2 decimal places!";
		assertEquals(refusal, refused[0]);
		assertEquals(refusal, refused[1]);
		assertEquals("[1, 2, 3, 4]", refused[2]);
		assertEquals(refusal, refused[3]);
	}

	@Test
	@DisplayName("a decimal no index holds is compared as it is, however large")
	void shouldNotRefuseDecimalOfAttributeNoIndexHolds() {
		final BigDecimal huge = new BigDecimal("99999999999");
		final Map<String, Function<String, FilterConstraint>> constraints = new LinkedHashMap<>();
		final Map<String, String> answers = new LinkedHashMap<>();
		constraints.put("equals(huge)", a -> attributeEquals(a, huge));
		answers.put("equals(huge)", "[]");
		constraints.put("lessThan(huge)", a -> attributeLessThan(a, huge));
		answers.put("lessThan(huge)", "[1]");
		constraints.put("greaterThan(huge)", a -> attributeGreaterThan(a, huge));
		answers.put("greaterThan(huge)", "[2]");
		for (Map.Entry<String, Function<String, FilterConstraint>> entry : constraints.entrySet()) {
			final String[] outcomes = outcomesOf(DECIMAL_NOT_FILTERABLE, entry.getValue());
			final String label = entry.getKey();
			// the main query needs a filterable attribute, so it fails for that reason and no other
			assertTrue(outcomes[0].startsWith("ERR AttributeNotFilterableException"), label + ": " + outcomes[0]);
			assertTrue(outcomes[1].startsWith("ERR AttributeNotFilterableException"), label + ": " + outcomes[1]);
			assertEquals(answers.get(label), outcomes[2], label + " on the reference row");
			assertEquals(answers.get(label), outcomes[3], label + " on the entity filtering");
		}
	}

	@Test
	@DisplayName("entity filtering matches the candidates of each type at the decimal places of that type")
	void shouldMatchEveryTypeAtItsOwnDecimalPlaces() {
		final String[] codes = concat(SCALE_TWO.codes(), SCALE_ZERO.codes());
		final Map<String, String> answers = new LinkedHashMap<>();
		final Map<String, Function<String, FilterConstraint>> constraints = new LinkedHashMap<>();
		// 1.4 is 1.40 at two places, so it equals only 1.4 there, and 1 at no place, so it equals 1.2 and 1.4 there
		constraints.put("equals(1.4)", a -> attributeEquals(a, new BigDecimal("1.4")));
		answers.put("equals(1.4)", "[ownerScaleTwo:1, ownerScaleZero:1, ownerScaleZero:2]");
		// 1.45 is 1.45 at two places and 1 at no place, where it is the largest of 1.2 and 1.4 and below 1.6
		constraints.put("lessThanEquals(1.45)", a -> attributeLessThanEquals(a, new BigDecimal("1.45")));
		answers.put("lessThanEquals(1.45)", "[ownerScaleTwo:1, ownerScaleTwo:2, ownerScaleZero:1, ownerScaleZero:2]");
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (Map.Entry<String, Function<String, FilterConstraint>> entry : constraints.entrySet()) {
					final FilterConstraint constraint = entry.getValue().apply(ATTR);
					final String together = outcome(() -> typedKeys(session.query(
						query(filterBy(attributeInSet(CODE, codes), constraint), require(page(1, 100))),
						EntityReference.class
					)));
					assertEquals(answers.get(entry.getKey()), together, entry.getKey() + " on both types at once");
					final Set<String> perType = new TreeSet<>();
					for (Fixture fixture : List.of(SCALE_TWO, SCALE_ZERO)) {
						perType.addAll(
							session.query(
								query(
									collection(fixture.entityType()),
									filterBy(entityPrimaryKeyInSet(fixture.owners()), constraint),
									require(debug(DebugMode.PREFER_INDEX_SCAN), page(1, 100))
								),
								EntityReference.class
							).getRecordData().stream()
								.map(it -> it.getType() + ":" + it.getPrimaryKey())
								.toList()
						);
					}
					assertEquals(perType.toString(), together, entry.getKey() + " on each type by the index");
				}
				return null;
			}
		);
	}

	@Test
	@DisplayName("an inverted interval is refused although the collection holds no entity")
	void shouldRefuseInvertedIntervalOfCollectionWithoutEntity() {
		final FilterConstraint inverted = attributeBetween(ATTR, 10, 5);
		final String refusal = FROM_TO_REFUSAL + "10 vs. 5";
		final String[] outcomes = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> new String[]{
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
		final String refusal = FROM_TO_REFUSAL + "10 vs. 5";
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
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
	private String[] outcomesOf(@Nonnull Fixture fixture, @Nonnull Function<String, FilterConstraint> constraint) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> new String[]{
				indexArm(session, fixture, constraint),
				prefetchArm(session, fixture, constraint),
				rowArm(session, fixture, constraint),
				entityFilteringArm(session, fixture, constraint)
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
	 * Index arm: the translator resolves the indexes, so the plan must not prefetch.
	 */
	@Nonnull
	private static String indexArm(
		@Nonnull EvitaSessionContract session,
		@Nonnull Fixture fixture,
		@Nonnull Function<String, FilterConstraint> constraint
	) {
		return outcome(() -> {
			final EvitaResponse<EntityReference> response = session.query(
				query(
					collection(fixture.entityType()),
					filterBy(
						fixture.withLocale(
							entityPrimaryKeyInSet(fixture.owners()),
							constraint.apply(fixture.attribute())
						)
					),
					require(
						queryTelemetry(),
						debug(DebugMode.PREFER_INDEX_SCAN),
						page(1, 100)
					)
				),
				EntityReference.class
			);
			return keys(response) + (PlanPreference.prefetched(response) ? PREFETCHED_MARK : "");
		});
	}

	/**
	 * Prefetch arm: the engine is steered onto the prefetched bodies, so a constraint with a prefetch alternative
	 * answers from it.
	 */
	@Nonnull
	private static String prefetchArm(
		@Nonnull EvitaSessionContract session,
		@Nonnull Fixture fixture,
		@Nonnull Function<String, FilterConstraint> constraint
	) {
		return outcome(() -> {
			final EvitaResponse<EntityReference> response = session.query(
				query(
					collection(fixture.entityType()),
					filterBy(
						fixture.withLocale(
							entityPrimaryKeyInSet(fixture.owners()),
							constraint.apply(fixture.attribute())
						)
					),
					require(queryTelemetry(), PlanPreference.PREFETCH.debug(), page(1, 100))
				),
				EntityReference.class
			);
			return keys(response) + (PlanPreference.prefetched(response) ? "" : NOT_PREFETCHED_MARK);
		});
	}

	/**
	 * Row arm: the owners whose single reference row survives the `referenceContent` filter.
	 */
	@Nonnull
	private static String rowArm(
		@Nonnull EvitaSessionContract session,
		@Nonnull Fixture fixture,
		@Nonnull Function<String, FilterConstraint> constraint
	) {
		return outcome(() -> {
			final EvitaResponse<SealedEntity> response = session.query(
				query(
					collection(fixture.entityType()),
					filterBy(fixture.withLocale(entityPrimaryKeyInSet(fixture.owners()))),
					require(
						entityFetch(referenceContent(REF, filterBy(constraint.apply(fixture.attribute())))),
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
	 * Entity filtering arm: the query names no collection, so the entity type is unknown and the attribute is
	 * evaluated on the entities found by the globally unique code of every owner.
	 */
	@Nonnull
	private static String entityFilteringArm(
		@Nonnull EvitaSessionContract session,
		@Nonnull Fixture fixture,
		@Nonnull Function<String, FilterConstraint> constraint
	) {
		return entityFiltering(
			session, fixture.codes(), fixture.withLocale(constraint.apply(fixture.attribute()))
		);
	}

	/**
	 * Entity filtering: the query names no collection and finds the entities by their globally unique codes.
	 */
	@Nonnull
	private static String entityFiltering(
		@Nonnull EvitaSessionContract session,
		@Nonnull String[] codes,
		@Nonnull FilterConstraint... constraints
	) {
		return outcome(() -> {
			final FilterConstraint[] filter = new FilterConstraint[constraints.length + 1];
			filter[0] = attributeInSet(CODE, codes);
			System.arraycopy(constraints, 0, filter, 1, constraints.length);
			final EvitaResponse<EntityReference> response = session.query(
				query(
					filterBy(filter),
					require(page(1, 100))
				),
				EntityReference.class
			);
			return keys(response);
		});
	}

	/**
	 * Returns the types and primary keys of the response as the sorted set of `type:primaryKey` texts.
	 */
	@Nonnull
	private static String typedKeys(@Nonnull EvitaResponse<EntityReference> response) {
		final Set<String> result = new TreeSet<>();
		for (EntityReference reference : response.getRecordData()) {
			result.add(reference.getType() + ":" + reference.getPrimaryKey());
		}
		return result.toString();
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
		final EntitySchemaBuilder schema = session.defineEntitySchema(fixture.entityType())
			.withGlobalAttribute(CODE);
		if (fixture.locale() != null) {
			schema.withLocale(fixture.locale());
		}
		if (fixture.indexing() == Indexing.CATALOG_UNIQUE) {
			schema.withGlobalAttribute(fixture.attribute());
		} else {
			schema.withAttribute(fixture.attribute(), fixture.type(), whichIs -> configure(whichIs, fixture, true));
		}
		schema
			.withReferenceTo(
				REF, TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(
						fixture.attribute(), fixture.type(), thatIs -> configure(thatIs, fixture, false)
					)
			)
			.updateVia(session);
		for (int i = 0; fixture.populated() && i <= fixture.values().length; i++) {
			final Serializable value = i < fixture.values().length ? fixture.values()[i] : null;
			final EntityBuilder owner = session.createNewEntity(fixture.entityType(), i + 1);
			owner.setAttribute(CODE, fixture.code(i + 1));
			storeValue(owner, fixture, value);
			owner.setReference(REF, i + 1, whichIs -> storeValue(whichIs, fixture, value));
			owner.upsertVia(session);
		}
	}

	/**
	 * Configures the attribute of the fixture: nullable, localized when the fixture is, and indexed as the fixture is
	 * on the owner, or filterable on its reference unless nothing is indexed.
	 */
	private static void configure(
		@Nonnull AttributeSchemaEditor<?> attribute,
		@Nonnull Fixture fixture,
		boolean owner
	) {
		final Indexing indexing = owner ? fixture.indexing() : (
			fixture.indexing() == Indexing.NOT_INDEXED ? Indexing.NOT_INDEXED : Indexing.FILTERABLE
		);
		switch (indexing) {
			case FILTERABLE -> attribute.filterable();
			case UNIQUE_ONLY -> attribute.unique();
			case NOT_INDEXED, CATALOG_UNIQUE -> {
			}
		}
		attribute.nullable();
		if (fixture.locale() != null) {
			attribute.localized();
		}
		if (fixture.places() > 0) {
			attribute.indexDecimalPlaces(fixture.places());
		}
	}

	/**
	 * Stores the value as the attribute in the locale of the fixture, an array through the array overload; a `null`
	 * stores nothing.
	 */
	private static void storeValue(
		@Nonnull AttributesEditor<?, ?> editor,
		@Nonnull Fixture fixture,
		@Nullable Serializable value
	) {
		if (value == null) {
			return;
		}
		if (fixture.locale() != null) {
			editor.setAttribute(fixture.attribute(), fixture.locale(), value);
		} else if (value instanceof Serializable[] array) {
			editor.setAttribute(fixture.attribute(), array);
		} else {
			editor.setAttribute(fixture.attribute(), value);
		}
	}

	/**
	 * Lists every case.
	 */
	@Nonnull
	private static List<Case> cases() {
		final List<Case> cases = new ArrayList<>(256);
		for (Fixture fixture : FIXTURES) {
			if (OUTSIDE_AGREEMENT.contains(fixture) || fixture.locale() != null) {
				continue;
			}
			cases.add(new Case(fixture, "attributeIs(NULL)", a -> attributeIs(a, AttributeSpecialValue.NULL), SELECTS));
			cases.add(
				new Case(fixture, "attributeIs(NOT_NULL)", a -> attributeIs(a, AttributeSpecialValue.NOT_NULL), SELECTS)
			);
		}

		// no data in the index: an error case only, nothing can discriminate
		cases.add(new Case(EMPTY, "between(10,5) inverted", a -> attributeBetween(a, 10, 5), inverted("10", "5")));
		cases.add(new Case(EMPTY, "between(5,10)", a -> attributeBetween(a, 5, 10), NOTHING));

		// Integer
		cases.add(new Case(INTEGER, "equals(10)", a -> attributeEquals(a, 10), SELECTS));
		cases.add(new Case(INTEGER, "equals(10L)", a -> attributeEquals(a, 10L), SELECTS));
		cases.add(new Case(INTEGER, "inSet(1,20)", a -> attributeInSet(a, new Integer[]{1, 20}), SELECTS));
		cases.add(new Case(INTEGER, "lessThan(10)", a -> attributeLessThan(a, 10), SELECTS));
		cases.add(new Case(INTEGER, "lessThanEquals(10)", a -> attributeLessThanEquals(a, 10), SELECTS));
		cases.add(new Case(INTEGER, "greaterThan(5)", a -> attributeGreaterThan(a, 5), SELECTS));
		cases.add(new Case(INTEGER, "greaterThanEquals(5)", a -> attributeGreaterThanEquals(a, 5), SELECTS));
		cases.add(new Case(INTEGER, "between(5,10)", a -> attributeBetween(a, 5, 10), SELECTS));
		cases.add(new Case(INTEGER, "between(null,10)", a -> attributeBetween(a, null, 10), SELECTS));
		cases.add(new Case(INTEGER, "between(10,null)", a -> attributeBetween(a, 10, null), SELECTS));
		cases.add(new Case(INTEGER, "between(10,5) inverted", a -> attributeBetween(a, 10, 5), inverted("10", "5")));
		cases.add(
			new Case(INTEGER, "between(5L,10L) Long bounds", a -> attributeBetween(a, (Serializable) 5L, 10L), SELECTS)
		);
		cases.add(
			new Case(
				INTEGER, "between(4.5,10.5) BigDecimal bounds",
				a -> attributeBetween(a, new BigDecimal("4.5"), new BigDecimal("10.5")),
				new Expectation(
					"ERR InconvertibleDataTypeException: The value `4.5` cannot be converted to the type"
						+ " `java.lang.Integer`!"
				)
			)
		);
		cases.add(new Case(INTEGER, "lessThan(10L) Long probe", a -> attributeLessThan(a, 10L), SELECTS));

		// Long
		cases.add(new Case(LONG, "equals(10L)", a -> attributeEquals(a, 10L), SELECTS));
		cases.add(new Case(LONG, "equals(10) Integer probe", a -> attributeEquals(a, 10), SELECTS));
		cases.add(new Case(LONG, "inSet(1L,20L)", a -> attributeInSet(a, new Long[]{1L, 20L}), SELECTS));
		cases.add(new Case(LONG, "inSet(1,20) Integer probes", a -> attributeInSet(a, new Integer[]{1, 20}), SELECTS));
		cases.add(new Case(LONG, "lessThan(10L)", a -> attributeLessThan(a, 10L), SELECTS));
		cases.add(new Case(LONG, "lessThan(10) Integer probe", a -> attributeLessThan(a, 10), SELECTS));
		cases.add(new Case(LONG, "lessThanEquals(10L)", a -> attributeLessThanEquals(a, 10L), SELECTS));
		cases.add(new Case(LONG, "greaterThan(5L)", a -> attributeGreaterThan(a, 5L), SELECTS));
		cases.add(new Case(LONG, "greaterThanEquals(5L)", a -> attributeGreaterThanEquals(a, 5L), SELECTS));
		cases.add(new Case(LONG, "between(5L,10L)", a -> attributeBetween(a, 5L, 10L), SELECTS));
		cases.add(new Case(LONG, "between(5,10) Integer bounds", a -> attributeBetween(a, 5, 10), SELECTS));
		cases.add(new Case(LONG, "between(null,10L)", a -> attributeBetween(a, null, 10L), SELECTS));
		cases.add(new Case(LONG, "between(10L,null)", a -> attributeBetween(a, 10L, null), SELECTS));
		cases.add(new Case(LONG, "between(10L,5L) inverted", a -> attributeBetween(a, 10L, 5L), inverted("10", "5")));
		cases.add(
			new Case(
				LONG, "between(10,5) Integer inverted",
				a -> attributeBetween(a, 10, 5), inverted("10", "5")
			)
		);

		// Decimal at 2 places: 1.005 and 1.0049 and 1.01 and -1.005 are stored
		cases.add(
			new Case(DECIMAL, "equals(1.01)", a -> attributeEquals(a, new BigDecimal("1.01")), SELECTS)
		);
		cases.add(
			new Case(DECIMAL, "equals(1.005)", a -> attributeEquals(a, new BigDecimal("1.005")), SELECTS)
		);
		cases.add(
			new Case(DECIMAL, "equals(1.0049)", a -> attributeEquals(a, new BigDecimal("1.0049")), SELECTS)
		);
		cases.add(
			new Case(DECIMAL, "equals(-1.005)", a -> attributeEquals(a, new BigDecimal("-1.005")), SELECTS)
		);
		cases.add(
			new Case(
				DECIMAL, "inSet(1.01,-1.005)",
				a -> attributeInSet(a, new BigDecimal[]{new BigDecimal("1.01"), new BigDecimal("-1.005")}), SELECTS
			)
		);
		cases.add(
			new Case(DECIMAL, "lessThan(1.01)", a -> attributeLessThan(a, new BigDecimal("1.01")), SELECTS)
		);
		cases.add(
			new Case(DECIMAL, "lessThanEquals(1.01)", a -> attributeLessThanEquals(a, new BigDecimal("1.01")), SELECTS)
		);
		cases.add(
			new Case(DECIMAL, "greaterThan(1.0)", a -> attributeGreaterThan(a, new BigDecimal("1.0")), SELECTS)
		);
		cases.add(
			new Case(
				DECIMAL, "greaterThanEquals(1.01)", a -> attributeGreaterThanEquals(a, new BigDecimal("1.01")), SELECTS
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(1.00,1.01)",
				a -> attributeBetween(a, new BigDecimal("1.00"), new BigDecimal("1.01")), SELECTS
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(1.0045,1.0055)",
				a -> attributeBetween(a, new BigDecimal("1.0045"), new BigDecimal("1.0055")), SELECTS
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(null,1.00)", a -> attributeBetween(a, null, new BigDecimal("1.00")),
				SELECTS
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(1.01,null)", a -> attributeBetween(a, new BigDecimal("1.01"), null),
				SELECTS
			)
		);
		cases.add(
			new Case(
				DECIMAL, "between(1.01,1.00) inverted",
				a -> attributeBetween(a, new BigDecimal("1.01"), new BigDecimal("1.00")), inverted("101", "100")
			)
		);
		cases.add(new Case(DECIMAL, "between(0,1) Integer bounds", a -> attributeBetween(a, 0, 1), SELECTS));
		cases.add(new Case(DECIMAL, "between(-2L,0L) Long bounds", a -> attributeBetween(a, -2L, 0L), SELECTS));
		cases.add(new Case(DECIMAL, "equals(1) Integer probe", a -> attributeEquals(a, 1), SELECTS));
		cases.add(
			new Case(
				DECIMAL, "lessThan(21474836.47) largest probe the key holds",
				a -> attributeLessThan(a, new BigDecimal("21474836.47")), SELECTS
			)
		);
		cases.add(new Case(DECIMAL, "lessThan(1) Integer probe", a -> attributeLessThan(a, 1), SELECTS));

		// String: NFD "cafe+U+0301", NFC "caf+U+00E9", "cafe", "bar", "x"+NFD
		cases.add(new Case(STRING, "equals(NFC cafe)", a -> attributeEquals(a, NFC_CAFE), SELECTS));
		cases.add(new Case(STRING, "equals(NFD cafe)", a -> attributeEquals(a, NFD_CAFE), SELECTS));
		cases.add(
			new Case(
				STRING, "inSet(NFC cafe,bar)",
				a -> attributeInSet(a, new String[]{NFC_CAFE, "bar"}), SELECTS
			)
		);
		cases.add(new Case(STRING, "lessThan(NFD cafe)", a -> attributeLessThan(a, NFD_CAFE), SELECTS));
		cases.add(new Case(STRING, "lessThan(NFC cafe)", a -> attributeLessThan(a, NFC_CAFE), SELECTS));
		cases.add(new Case(STRING, "lessThanEquals(NFC cafe)", a -> attributeLessThanEquals(a, NFC_CAFE), SELECTS));
		cases.add(new Case(STRING, "greaterThan(NFC cafe)", a -> attributeGreaterThan(a, NFC_CAFE), SELECTS));
		cases.add(
			new Case(STRING, "greaterThanEquals(NFC cafe)", a -> attributeGreaterThanEquals(a, NFC_CAFE), SELECTS)
		);
		cases.add(new Case(STRING, "between(cafe,NFC cafe)", a -> attributeBetween(a, "cafe", NFC_CAFE), SELECTS));
		cases.add(
			new Case(
				STRING, "between(NFC cafe,xNFC cafe)",
				a -> attributeBetween(a, NFC_CAFE, "x" + NFC_CAFE), SELECTS
			)
		);
		cases.add(
			new Case(
				STRING, "between(null,NFC cafe)",
				a -> attributeBetween(a, null, NFC_CAFE), SELECTS
			)
		);
		cases.add(
			new Case(
				STRING, "between(NFC cafe,null)",
				a -> attributeBetween(a, NFC_CAFE, null), SELECTS
			)
		);
		cases.add(new Case(STRING, "between(z,a) inverted", a -> attributeBetween(a, "z", "a"), inverted("z", "a")));
		cases.add(new Case(STRING, "startsWith(NFC caf+e acute)", a -> attributeStartsWith(a, NFC_CAFE), SELECTS));
		cases.add(new Case(STRING, "startsWith(NFD cafe)", a -> attributeStartsWith(a, NFD_CAFE), SELECTS));
		cases.add(new Case(STRING, "startsWith(ca)", a -> attributeStartsWith(a, "ca"), SELECTS));
		cases.add(new Case(STRING, "endsWith(NFC e acute)", a -> attributeEndsWith(a, "é"), SELECTS));
		cases.add(new Case(STRING, "endsWith(NFD e+acute)", a -> attributeEndsWith(a, "é"), SELECTS));
		cases.add(new Case(STRING, "contains(NFC f e acute)", a -> attributeContains(a, "fé"), SELECTS));
		cases.add(new Case(STRING, "contains(NFD f e+acute)", a -> attributeContains(a, "fé"), SELECTS));
		cases.add(new Case(STRING, "contains(ba)", a -> attributeContains(a, "ba"), SELECTS));

		// OffsetDateTime: 12:00+01:00 and 11:00Z are one instant
		final OffsetDateTime instantPlus = OffsetDateTime.parse("2026-01-01T12:00:00+01:00");
		final OffsetDateTime instantZ = OffsetDateTime.parse("2026-01-01T11:00:00Z");
		cases.add(new Case(OFFSET, "equals(11:00Z)", a -> attributeEquals(a, instantZ), SELECTS));
		cases.add(new Case(OFFSET, "equals(12:00+01:00)", a -> attributeEquals(a, instantPlus), SELECTS));
		cases.add(new Case(OFFSET, "inSet(12:00+01:00,13:00Z)", a -> attributeInSet(a, new OffsetDateTime[]{
			instantPlus, OffsetDateTime.parse("2026-01-01T13:00:00Z")
		}), SELECTS));
		cases.add(new Case(OFFSET, "lessThan(12:00+01:00)", a -> attributeLessThan(a, instantPlus), SELECTS));
		cases.add(
			new Case(
				OFFSET, "lessThanEquals(12:00+01:00)",
				a -> attributeLessThanEquals(a, instantPlus), SELECTS
			)
		);
		cases.add(new Case(OFFSET, "greaterThan(11:00Z)", a -> attributeGreaterThan(a, instantZ), SELECTS));
		cases.add(
			new Case(
				OFFSET, "greaterThanEquals(12:00+01:00)",
				a -> attributeGreaterThanEquals(a, instantPlus), SELECTS
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
				SELECTS
			)
		);
		cases.add(
			new Case(
				OFFSET, "between(null,11:00Z)", a -> attributeBetween(a, null, instantZ), SELECTS
			)
		);
		cases.add(
			new Case(
				OFFSET, "between(13:00Z,11:00Z) inverted",
				a -> attributeBetween(a, OffsetDateTime.parse("2026-01-01T13:00:00Z"), instantZ),
				inverted("2026-01-01T13:00:00Z", "2026-01-01T11:00:00Z")
			)
		);

		// LocalDateTime
		final LocalDateTime eleven = LocalDateTime.parse("2026-01-01T11:00:00");
		cases.add(new Case(LOCAL, "equals(11:00)", a -> attributeEquals(a, eleven), SELECTS));
		cases.add(new Case(LOCAL, "inSet(10:00,12:00)", a -> attributeInSet(a, new LocalDateTime[]{
			LocalDateTime.parse("2026-01-01T10:00:00"), LocalDateTime.parse("2026-01-01T12:00:00")
		}), SELECTS));
		cases.add(new Case(LOCAL, "lessThan(11:00)", a -> attributeLessThan(a, eleven), SELECTS));
		cases.add(new Case(LOCAL, "lessThanEquals(11:00)", a -> attributeLessThanEquals(a, eleven), SELECTS));
		cases.add(
			new Case(
				LOCAL, "greaterThan(10:00)",
				a -> attributeGreaterThan(a, LocalDateTime.parse("2026-01-01T10:00:00")), SELECTS
			)
		);
		cases.add(new Case(LOCAL, "greaterThanEquals(11:00)", a -> attributeGreaterThanEquals(a, eleven), SELECTS));
		cases.add(
			new Case(
				LOCAL, "between(10:00,11:00)",
				a -> attributeBetween(a, LocalDateTime.parse("2026-01-01T10:00:00"), eleven), SELECTS
			)
		);
		cases.add(
			new Case(
				LOCAL, "between(12:00,10:00) inverted",
				a -> attributeBetween(
					a, LocalDateTime.parse("2026-01-01T12:00:00"), LocalDateTime.parse("2026-01-01T10:00:00")
				),
				inverted("2026-01-01T12:00:00Z", "2026-01-01T10:00:00Z")
			)
		);

		// Boolean
		cases.add(new Case(BOOLEAN, "equals(true)", a -> attributeEquals(a, true), SELECTS));
		cases.add(new Case(BOOLEAN, "equals(false)", a -> attributeEquals(a, false), SELECTS));
		cases.add(new Case(BOOLEAN, "inSet(false)", a -> attributeInSet(a, new Boolean[]{false}), SELECTS));
		cases.add(new Case(BOOLEAN, "greaterThan(false)", a -> attributeGreaterThan(a, false), SELECTS));
		cases.add(new Case(BOOLEAN, "lessThan(true)", a -> attributeLessThan(a, true), SELECTS));

		// Integer[]: {1,2,3} {4,5} {} {2,9}
		cases.add(new Case(INTEGER_ARRAY, "equals(2)", a -> attributeEquals(a, 2), SELECTS));
		cases.add(
			new Case(
				INTEGER_ARRAY, "equals(new Integer[]{4,5})",
				a -> attributeEquals(a, new Integer[]{4, 5}), UNSUPPORTED_ARRAY
			)
		);
		cases.add(new Case(INTEGER_ARRAY, "inSet(5,9)", a -> attributeInSet(a, new Integer[]{5, 9}), SELECTS));
		cases.add(new Case(INTEGER_ARRAY, "lessThan(2)", a -> attributeLessThan(a, 2), SELECTS));
		cases.add(new Case(INTEGER_ARRAY, "lessThanEquals(1)", a -> attributeLessThanEquals(a, 1), SELECTS));
		cases.add(new Case(INTEGER_ARRAY, "greaterThan(8)", a -> attributeGreaterThan(a, 8), SELECTS));
		cases.add(new Case(INTEGER_ARRAY, "greaterThanEquals(5)", a -> attributeGreaterThanEquals(a, 5), SELECTS));
		cases.add(new Case(INTEGER_ARRAY, "between(4,5)", a -> attributeBetween(a, 4, 5), SELECTS));
		cases.add(new Case(INTEGER_ARRAY, "between(4L,5L) Long bounds", a -> attributeBetween(a, 4L, 5L), SELECTS));
		cases.add(new Case(INTEGER_ARRAY, "between(5,4) inverted", a -> attributeBetween(a, 5, 4), inverted("5", "4")));

		// String[]: {NFD cafe,x} {NFC cafe} {bar,baz} {}
		cases.add(new Case(STRING_ARRAY, "equals(NFC cafe)", a -> attributeEquals(a, NFC_CAFE), SELECTS));
		cases.add(new Case(STRING_ARRAY, "inSet(x,baz)", a -> attributeInSet(a, new String[]{"x", "baz"}), SELECTS));
		cases.add(new Case(STRING_ARRAY, "lessThan(NFC cafe)", a -> attributeLessThan(a, NFC_CAFE), SELECTS));
		cases.add(new Case(STRING_ARRAY, "greaterThan(bar)", a -> attributeGreaterThan(a, "bar"), SELECTS));
		cases.add(new Case(STRING_ARRAY, "between(bar,baz)", a -> attributeBetween(a, "bar", "baz"), SELECTS));
		cases.add(new Case(STRING_ARRAY, "startsWith(NFC cafe)", a -> attributeStartsWith(a, NFC_CAFE), SELECTS));
		cases.add(new Case(STRING_ARRAY, "endsWith(NFC e acute)", a -> attributeEndsWith(a, "é"), SELECTS));
		cases.add(new Case(STRING_ARRAY, "contains(ba)", a -> attributeContains(a, "ba"), SELECTS));

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
				a -> attributeBetween(a, new BigDecimal("1.004"), new BigDecimal("1.001")), SELECTS
			)
		);

		// the array a client may pass as the value of a scalar constraint
		cases.add(
			new Case(
				INTEGER, "equals(new Integer[]{1,5})",
				a -> attributeEquals(a, new Integer[]{1, 5}), UNSUPPORTED_ARRAY
			)
		);
		cases.add(
			new Case(
				INTEGER, "equals(new Integer[]{10})",
				a -> attributeEquals(a, new Integer[]{10}), UNSUPPORTED_ARRAY
			)
		);
		cases.add(
			new Case(
				INTEGER, "equals(new Integer[0])",
				a -> attributeEquals(a, new Integer[0]), UNSUPPORTED_ARRAY
			)
		);
		cases.add(
			new Case(
				INTEGER, "lessThan(new Integer[]{10})", a -> attributeLessThan(a, new Integer[]{10}), UNSUPPORTED_ARRAY
			)
		);
		cases.add(
			new Case(
				INTEGER, "lessThan(new Integer[0])",
				a -> attributeLessThan(a, new Integer[0]), UNSUPPORTED_ARRAY
			)
		);
		cases.add(
			new Case(
				STRING, "equals(new String[]{NFC cafe,bar})",
				a -> attributeEquals(a, new String[]{NFC_CAFE, "bar"}), NOTHING
			)
		);
		cases.add(new Case(STRING, "equals(new String[0])", a -> attributeEquals(a, new String[0]), NOTHING));
		cases.add(
			new Case(
				STRING, "greaterThan(new String[]{bar})", a -> attributeGreaterThan(a, new String[]{"bar"}),
				expect("[1, 2, 3, 4, 5]")
			)
		);
		cases.add(
			new Case(
				STRING, "greaterThan(new String[0])",
				a -> attributeGreaterThan(a, new String[0]), expect("[1, 2, 3, 4, 5]")
			)
		);

		// an inverted interval over a range attribute matches nothing, whatever the type of the range
		cases.add(new Case(INTEGER_RANGE, "between(10,5) inverted", a -> attributeBetween(a, 10, 5), NOTHING));
		cases.add(new Case(LONG_RANGE, "between(10L,5L) inverted", a -> attributeBetween(a, 10L, 5L), NOTHING));
		cases.add(
			new Case(
				DECIMAL_RANGE, "between(2.0,1.0) inverted",
				a -> attributeBetween(a, new BigDecimal("2.0"), new BigDecimal("1.0")), NOTHING
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "between(2026-02-10,2026-01-10) inverted",
				a -> attributeBetween(
					a, OffsetDateTime.parse("2026-02-10T00:00:00Z"), OffsetDateTime.parse("2026-01-10T00:00:00Z")
				),
				NOTHING
			)
		);

		// number ranges
		addNumberRangeCases(cases, INTEGER_RANGE, new Serializable[]{4, 11, 15}, new Serializable[]{4L, 11L, 15L});
		addNumberRangeCases(cases, LONG_RANGE, new Serializable[]{4, 11, 15}, new Serializable[]{4L, 11L, 15L});
		for (Fixture fixture : List.of(INTEGER_RANGE, LONG_RANGE)) {
			cases.add(
				new Case(
					fixture, "inRange(4.5) BigDecimal probe",
					a -> attributeInRange(a, new BigDecimal("4.5")), SELECTS
				)
			);
			cases.add(
				new Case(
					fixture, "inRange(11.0) BigDecimal probe",
					a -> attributeInRange(a, new BigDecimal("11.0")), SELECTS
				)
			);
			cases.add(
				new Case(
					fixture, "inRange(15.5) BigDecimal probe",
					a -> attributeInRange(a, new BigDecimal("15.5")), SELECTS
				)
			);
		}

		cases.add(new Case(DECIMAL_RANGE, "inRange(Integer 2)", a -> attributeInRange(a, 2), SELECTS));
		cases.add(new Case(DECIMAL_RANGE, "inRange(Integer 3)", a -> attributeInRange(a, 3), SELECTS));
		cases.add(new Case(DECIMAL_RANGE, "inRange(Long 2)", a -> attributeInRange(a, 2L), SELECTS));
		cases.add(new Case(DECIMAL_RANGE, "inRange(Long 3)", a -> attributeInRange(a, 3L), SELECTS));
		for (String probe : new String[]{
			"1.005", "1.006", "1.7", "1.9941", "1.9949", "2.00", "2.001", "2.0049", "2.005", "2.0051", "2.5", "3.004"
		}) {
			cases.add(
				new Case(
					DECIMAL_RANGE, "inRange(" + probe + ")", a -> attributeInRange(a, new BigDecimal(probe)), SELECTS
				)
			);
		}
		cases.add(
			new Case(
				DECIMAL_RANGE, "between(1,2) Integer bounds", a -> attributeBetween(a, 1, 2), SELECTS
			)
		);
		cases.add(
			new Case(
				DECIMAL_RANGE, "between(1.0,2.0) BigDecimal bounds",
				a -> attributeBetween(a, new BigDecimal("1.0"), new BigDecimal("2.0")), SELECTS
			)
		);
		cases.add(
			new Case(
				DECIMAL_RANGE, "equals(range [1.005,2.005])",
				a -> attributeEquals(
					a, BigDecimalNumberRange.between(new BigDecimal("1.005"), new BigDecimal("2.005"))
				),
				SELECTS
			)
		);
		cases.add(
			new Case(
				DECIMAL_RANGE, "equals(range [1.01,2.01]) rescaled",
				a -> attributeEquals(a, BigDecimalNumberRange.between(new BigDecimal("1.01"), new BigDecimal("2.01"))),
				SELECTS
			)
		);

		// date time range
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-01-20T00:00Z)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-01-20T00:00:00Z")), SELECTS
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-01-20T01:00+01:00)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-01-20T01:00:00+01:00")), SELECTS
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-02-05T00:00Z)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-02-05T00:00:00Z")), SELECTS
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-01-31T00:00+01:00)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-01-31T00:00:00+01:00")), SELECTS
			)
		);
		cases.add(
			new Case(
				DATE_RANGE, "inRange(2026-02-01T00:00+01:00)",
				a -> attributeInRange(a, OffsetDateTime.parse("2026-02-01T00:00:00+01:00")), SELECTS
			)
		);

		// a localized attribute read in Czech, where "ch" sorts after "h": the answers follow the collation, not the
		// code points, and the decomposed and the composed accent are one letter
		cases.add(new Case(LOCALIZED_STRING, "equals(NFC cafe)", a -> attributeEquals(a, NFC_CAFE), expect("[5]")));
		cases.add(
			new Case(
				LOCALIZED_STRING, "inSet(ha,NFC cafe)", a -> attributeInSet(a, new String[]{"ha", NFC_CAFE}),
				expect("[3, 5]")
			)
		);
		cases.add(new Case(LOCALIZED_STRING, "lessThan(ha)", a -> attributeLessThan(a, "ha"), expect("[1, 5]")));
		cases.add(
			new Case(LOCALIZED_STRING, "lessThanEquals(ha)", a -> attributeLessThanEquals(a, "ha"), expect("[1, 3, 5]"))
		);
		cases.add(new Case(LOCALIZED_STRING, "greaterThan(ha)", a -> attributeGreaterThan(a, "ha"), expect("[2, 4]")));
		cases.add(
			new Case(
				LOCALIZED_STRING, "greaterThanEquals(cha)", a -> attributeGreaterThanEquals(a, "cha"), expect("[2, 4]")
			)
		);
		cases.add(
			new Case(
				LOCALIZED_STRING, "between(ha,ia)", a -> attributeBetween(a, "ha", "ia"), expect("[2, 3, 4]")
			)
		);
		cases.add(
			new Case(
				LOCALIZED_STRING, "between(NFC cafe,ha)", a -> attributeBetween(a, NFC_CAFE, "ha"), expect("[3, 5]")
			)
		);
		cases.add(
			new Case(
				LOCALIZED_STRING, "between(cha,ha) inverted by the collation",
				a -> attributeBetween(a, "cha", "ha"), inverted("cha", "ha")
			)
		);
		cases.add(new Case(LOCALIZED_STRING, "startsWith(ch)", a -> attributeStartsWith(a, "ch"), expect("[2]")));
		cases.add(
			new Case(
				LOCALIZED_STRING, "startsWith(NFC cafe)",
				a -> attributeStartsWith(a, NFC_CAFE), expect("[5]")
			)
		);
		cases.add(new Case(LOCALIZED_STRING, "endsWith(NFC e acute)", a -> attributeEndsWith(a, "é"), expect("[5]")));
		cases.add(new Case(LOCALIZED_STRING, "contains(h)", a -> attributeContains(a, "h"), expect("[2, 3]")));
		cases.add(
			new Case(
				LOCALIZED_STRING, "contains(NFC f e acute)",
				a -> attributeContains(a, "fé"), expect("[5]")
			)
		);

		// a decimal attribute of the catalog and a unique-only one, compared by equality and by the set; the
		// comparisons of the catalog one have a test of their own
		for (Fixture fixture : List.of(CATALOG_DECIMAL, UNIQUE_DECIMAL)) {
			cases.add(new Case(fixture, "equals(1.50)", a -> attributeEquals(a, new BigDecimal("1.50")), SELECTS));
			cases.add(
				new Case(
					fixture, "inSet(1.01,9.99)",
					a -> attributeInSet(a, new BigDecimal[]{new BigDecimal("1.01"), new BigDecimal("9.99")}), SELECTS
				)
			);
			cases.add(
				new Case(
					fixture, "between(2.00,1.00) inverted",
					a -> attributeBetween(a, new BigDecimal("2.00"), new BigDecimal("1.00")), inverted("200", "100")
				)
			);
		}
		for (BigDecimal probe : new BigDecimal[]{new BigDecimal("2.25"), new BigDecimal("1.50")}) {
			cases.add(
				new Case(UNIQUE_DECIMAL, "lessThan(" + probe + ")", a -> attributeLessThan(a, probe), SELECTS)
			);
			cases.add(
				new Case(
					UNIQUE_DECIMAL, "greaterThanEquals(" + probe + ")", a -> attributeGreaterThanEquals(a, probe),
					SELECTS
				)
			);
		}
		cases.add(
			new Case(
				UNIQUE_DECIMAL, "between(1.00,2.00)",
				a -> attributeBetween(a, new BigDecimal("1.00"), new BigDecimal("2.00")), SELECTS
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
		cases.add(new Case(fixture, "equals(" + second + ")", a -> attributeEquals(a, second), SELECTS));
		cases.add(
			new Case(
				fixture, "inSet(" + first + "," + fourth + ")",
				a -> attributeInSet(a, first, fourth), SELECTS
			)
		);
		cases.add(new Case(fixture, "lessThan(" + third + ")", a -> attributeLessThan(a, third), SELECTS));
		cases.add(
			new Case(
				fixture, "lessThanEquals(" + second + ")",
				a -> attributeLessThanEquals(a, second), SELECTS
			)
		);
		cases.add(new Case(fixture, "greaterThan(" + second + ")", a -> attributeGreaterThan(a, second), SELECTS));
		cases.add(
			new Case(fixture, "greaterThanEquals(" + third + ")", a -> attributeGreaterThanEquals(a, third), SELECTS)
		);
		cases.add(
			new Case(
				fixture, "between(" + second + "," + third + ")",
				a -> attributeBetween(a, second, third), SELECTS
			)
		);
		cases.add(
			new Case(
				fixture, "between(null," + second + ")", a -> attributeBetween(a, null, second), SELECTS
			)
		);
		cases.add(
			new Case(
				fixture, "between(" + third + ",null)", a -> attributeBetween(a, third, null), SELECTS
			)
		);
		cases.add(
			new Case(
				fixture, "between(" + third + "," + second + ") inverted", a -> attributeBetween(a, third, second),
				inverted(third, second)
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
					a -> attributeInRange(a, (Number) probe), SELECTS
				)
			);
		}
		for (Serializable probe : longProbes) {
			cases.add(
				new Case(
					fixture, "inRange(Long " + probe + ")",
					a -> attributeInRange(a, (Number) probe), SELECTS
				)
			);
		}
		cases.add(new Case(fixture, "between(2,11) Integer bounds", a -> attributeBetween(a, 2, 11), SELECTS));
		cases.add(new Case(fixture, "between(2L,11L) Long bounds", a -> attributeBetween(a, 2L, 11L), SELECTS));
	}
}
