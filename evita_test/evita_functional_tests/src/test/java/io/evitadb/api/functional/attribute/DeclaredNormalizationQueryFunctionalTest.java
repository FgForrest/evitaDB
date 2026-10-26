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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeInSet;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesEditor;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator.SUBSTRING_SEARCH;
import static io.evitadb.api.requestResponse.schema.OrderBehaviour.NULLS_LAST;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

/**
 * Public-API route matrix over SINGLE and PAGED value trees, before and after a real restart. The compiler's
 * separately golden-tested function supplies expected keys; no query translator or matcher supplies the oracle.
 * Every declaration is compared with a shared control having no steps; both fold locales are queried in Turkish.
 */
@Tag(ENGINE)
@Tag(ATTRIBUTE)
@Tag(FILTER)
@Tag(ORDER)
@Tag(REFERENCE)
@DisplayName("Declared String normalization query route parity")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@Execution(SAME_THREAD)
class DeclaredNormalizationQueryFunctionalTest implements EvitaTestSupport {
	private static final Locale TR = Locale.forLanguageTag("tr");
	private static final String REF = "targets";
	private static final String[] VALUES = {
		"ǧ", "ᴀ", "I", "TITLE", "tıtle", "title", " Á-BC ", " abc ", "- a -", " Zulu ", "--- ", "", "ǧᴀǧ", "xǧᴀǧy"
	};
	private static final int MISSING = VALUES.length + 1;
	private static final int[] CANDIDATES = IntStream.rangeClosed(1, MISSING).toArray();
	private static final int REFERENCE_OWNER = 1000;
	private static final List<Policy> POLICIES = List.of(
		new Policy("lower", List.of(lowercase())),
		new Policy("ascii", List.of(asciiFolding())),
		new Policy("trimmed", List.of(trim())),
		new Policy("removed", List.of(removeCharacters("-"))),
		new Policy("allSteps", List.of(lowercase(), asciiFolding(), removeCharacters("-"), trim())),
		new Policy("removeTrim", List.of(removeCharacters("-"), trim())),
		new Policy("trimRemove", List.of(trim(), removeCharacters("-"))),
		new Policy("lowerAscii", List.of(lowercase(), asciiFolding())),
		new Policy("asciiLower", List.of(asciiFolding(), lowercase()))
	);
	private final AnalysisStepRegistry registry = new AnalysisStepRegistry();
	private TestPaths paths;
	private Evita evita;

	/**
	 * Descriptions are reused for every physical role; functions are compiled independently for each fold locale.
	 *
	 * @param name short label that also prefixes the names of the attributes declared with this policy
	 * @param steps normalization steps declared, in order, on every attribute of this policy
	 */
	private record Policy(@Nonnull String name, @Nonnull List<AnalysisStep> steps) {
		/**
		 * These chains can change an already produced key, so reload must preserve every route verbatim.
		 *
		 * @return `true` when applying the chain to its own output can still change the key
		 */
		boolean unstable() {
			return this.name.equals("allSteps") || this.name.equals("trimRemove") ||
				this.name.equals("lowerAscii") || this.name.equals("asciiLower");
		}
	}

	/**
	 * A filter plus its expected predicate over canonical keys.
	 *
	 * @param name label shown in the failing assertion
	 * @param constraint builds the filter constraint for the given attribute name
	 * @param predicate expected match of a canonical (already normalized) key
	 */
	private record FilterCase(
		@Nonnull String name, @Nonnull Function<String, FilterConstraint> constraint,
			@Nonnull Predicate<String> predicate
	) { }

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("DeclaredNormalizationQueryFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute("lookup", String.class, a -> a.uniqueGlobally().nullable())
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Target").withoutGeneratedPrimaryKey().updateVia(session);
			for (int pk = 1; pk <= 300; pk++) {
				session.createNewEntity("Target", pk).upsertVia(session);
			}
			fill(session, "Single", MISSING);
			fill(session, "Paged", 300);
			session.goLiveAndClose();
		});
	}

	@AfterAll
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/** Read phases finish all their children before the next phase can close or mutate the shared engine. */
	@Nested
	@Order(1)
	@DisplayName("Before restart")
	class BeforeRestart {
		@TestFactory
		@Execution(CONCURRENT)
		@DisplayName("All query routes, policies, locales, controls and page modes")
		Stream<DynamicTest> shouldAnswerWithDeclaredKeys() {
			return matrix(false);
		}
	}

	/** Reload checks share the original fixture, and run only after every pre-restart reader has finished. */
	@Nested
	@Order(2)
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	@DisplayName("After restart")
	class AfterRestart {
		@BeforeAll
		void restart() {
			DeclaredNormalizationQueryFunctionalTest.this.evita.close();
			DeclaredNormalizationQueryFunctionalTest.this.evita = new Evita(
				newTestEvitaConfigurationBuilder(DeclaredNormalizationQueryFunctionalTest.this.paths).build()
			);
			DeclaredNormalizationQueryFunctionalTest.this.evita.waitUntilFullyInitialized();
		}

		@TestFactory
		@Execution(CONCURRENT)
		@DisplayName("Reload binds every policy and preserves unstable chains on every route")
		Stream<DynamicTest> shouldAnswerWithTheSameKeysAfterRestart() {
			return matrix(true);
		}
	}

	/** Promotion and demotion mutate the fixture only after both read phases finish. */
	@Nested
	@Order(3)
	@DisplayName("Virtual partition transitions")
	class VirtualPartitionTransitions {
		@TestFactory
		@DisplayName("Virtual partitions agree through promotion and demotion")
		Stream<DynamicTest> shouldKeepVirtualPartitionQueryKeys() {
			return Stream.of("Single", "Paged").map(type -> DynamicTest.dynamicTest(type, () -> {
				DeclaredNormalizationQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG,
					session -> { session.createNewEntity(type, 2000)
					.setAttribute("marker", TR, "present")
					.setReference("virtual", 1, r -> populate(r, VALUES[0])).upsertVia(session); });
				// the shared fixture must lose the extra entity even when an assertion fails, or the next storage
				// mode and any rerun in the same engine fail on its leftover
				try {
					for (final Policy policy : POLICIES) {
						for (final boolean localized : List.of(false, true)) {
							final String name = attributeName(policy, localized, false);
							DeclaredNormalizationQueryFunctionalTest.this.evita.queryCatalog(TEST_CATALOG, session -> {
								final EvitaResponse<EntityReference> matches = session.query(query(collection(type),
									filterBy(entityPrimaryKeyInSet(1, 2000), entityLocaleEquals(TR), referenceHaving(
										"virtual", entityPrimaryKeyInSet(1),
										attributeInSet(name, VALUES[0], VALUES[0])))),
									EntityReference.class);
								assertArrayEquals(new int[]{1, 2000}, ids(matches));
								return null;
							});
						}
					}
				} finally {
					DeclaredNormalizationQueryFunctionalTest.this.evita.updateCatalog(TEST_CATALOG,
						session -> { session.deleteEntity(type, 2000); });
				}
				final UnaryOperator<String> key = DeclaredNormalizationQueryFunctionalTest.this.registry.compile(
					List.of(lowercase(), asciiFolding()), null
				);
				for (final PlanPreference route : PlanPreference.values()) {
					assertArrayEquals(sorted(key, comparator(false)), queryIds(type, route, null,
						referenceProperty("virtual", attributeNatural("lowerAsciiRoot"))));
				}
			}));
		}

	}

	/**
	 * Builds individually named tests so the failing operator, locale, control and storage mode are visible.
	 *
	 * @param reloaded `true` for the reduced matrix run against the catalog reloaded from disk, `false` for the full
	 *     pre-restart matrix
	 * @return one named dynamic test per policy, locale, control and entity type combination
	 */
	@Nonnull
	private Stream<DynamicTest> matrix(boolean reloaded) {
		final List<DynamicTest> tests = new ArrayList<>();
		for (final String type : List.of("Single", "Paged")) {
			for (final Policy policy : POLICIES) {
				for (final boolean localized : List.of(false, true)) {
					for (final boolean control : List.of(false, true)) {
						// The full cross product is checked before restart. Reload repeats each policy once,
						// plus both fold locales and representative route probes for unstable chains.
						if (reloaded && (control || !policy.unstable() && localized)) {
							continue;
						}
						final String name = attributeName(policy, localized, control);
						final UnaryOperator<String> key = this.registry.compile(control ? List.of() : policy.steps(),
							localized ? TR : null);
						final Comparator<String> comparator = comparator(localized);
						final String label = type + "/" + policy.name() + "/" + name;
						for (final FilterCase filter : filters(key, comparator)) {
							if (reloaded && (!policy.unstable() || !(filter.name().equals("inSet") ||
								filter.name().equals("contains/ǧᴀǧ") || filter.name().equals("contains/ǧᴀǧFast")))) {
								continue;
							}
							tests.add(DynamicTest.dynamicTest(label + "/" + filter.name(), () -> {
								assertFilter(type, name, filter, key);
								if (!filter.name().endsWith("Fast")) {
									assertFilter(type, name + "Array", filter, key);
								}
							}));
						}
						tests.add(DynamicTest.dynamicTest(label + "/equality probes and arrays", () -> {
							for (final String probe : VALUES) {
								final FilterCase equality = new FilterCase("equals", a -> attributeEquals(a, probe),
									value -> value.equals(key.apply(probe)));
								assertFilter(type, name, equality, key);
								assertArrayEquals(expected(equality.predicate(), key), queryIds(type,
									PlanPreference.PREFETCH, attributeEquals(name + "Array", probe), null));
							}
						}));
						for (final String sorted : List.of(name, name + "Compound")) {
							tests.add(DynamicTest.dynamicTest(label + "/order/" + sorted, () -> {
								final int[] expected = sorted(key, comparator);
								for (final PlanPreference route : PlanPreference.values()) {
									assertArrayEquals(expected, queryIds(type, route, null, attributeNatural(sorted)));
									final int[] descending = IntStream.concat(
										IntStream.range(0, VALUES.length).map(i -> expected[VALUES.length - 1 - i]),
										IntStream.of(MISSING)).toArray();
									assertArrayEquals(descending, queryIds(type, route, null,
										attributeNatural(sorted, DESC)));
									assertArrayEquals(descending, queryIds(type, route, null,
										referenceProperty(REF, attributeNatural(sorted, DESC))));
									assertArrayEquals(descending, queryIds(type, route, null,
										referenceProperty("virtual", attributeNatural(sorted, DESC))));
									assertArrayEquals(descending, queryIds(type, route, null, referenceProperty(REF,
										traverseByEntityProperty(entityPrimaryKeyNatural(ASC)),
										attributeNatural(sorted, DESC))));
									assertArrayEquals(expected, queryIds(type, route, null,
										referenceProperty(REF, attributeNatural(sorted))));
									assertArrayEquals(expected, queryIds(type, route, null,
										referenceProperty("virtual", attributeNatural(sorted))));
									assertArrayEquals(expected, queryIds(type, route, null, referenceProperty(REF,
										traverseByEntityProperty(entityPrimaryKeyNatural(ASC)),
											attributeNatural(sorted))));
								}
								assertReferenceOrder(type, sorted, expected);
							}));
						}
						if (reloaded && !policy.unstable()) {
							continue;
						}
						tests.add(DynamicTest.dynamicTest(label + "/exact order collisions", () ->
							assertExactOrder(type, name, key)));
						if (reloaded) {
							continue;
						}
						tests.add(DynamicTest.dynamicTest(label + "/presence and stored spelling", () -> {
							for (final PlanPreference route : PlanPreference.values()) {
								assertArrayEquals(IntStream.rangeClosed(1, VALUES.length).toArray(),
									queryIds(type, route, attributeIsNotNull(name), null));
								assertArrayEquals(new int[]{MISSING}, queryIds(type, route, attributeIsNull(name),
									null));
							}
							this.evita.queryCatalog(TEST_CATALOG, session -> {
								for (int pk = 1; pk <= VALUES.length; pk++) {
									final SealedEntity entity = session.getEntity(type, pk,
										attributeContent(name), dataInLocales(TR)).orElseThrow();
									assertEquals(VALUES[pk - 1], localized ? entity.getAttribute(name, TR) :
										entity.getAttribute(name));
								}
								return null;
							});
						}));
						tests.add(DynamicTest.dynamicTest(label + "/normalized bound order", () -> {
							final String from = "Zulu";
							final String to = "abc";
							for (final PlanPreference route : PlanPreference.values()) {
								if (comparator.compare(key.apply(from), key.apply(to)) > 0) {
									assertThrows(EvitaInvalidUsageException.class, () ->
										queryIds(type, route, attributeBetween(name, from, to), null));
								} else {
									assertDoesNotThrow(() -> queryIds(type, route, attributeBetween(name, from, to),
										null));
								}
							}
						}));
					}
				}
			}
			for (final boolean control : List.of(false, true)) {
				final String name = control ? "mixedControl" : "mixed";
				tests.add(DynamicTest.dynamicTest(type + "/" + name, () -> {
					final UnaryOperator<String> root = this.registry.compile(
						control ? List.of() : List.of(lowercase()), null
					);
					final UnaryOperator<String> local = this.registry.compile(control ? List.of() :
						List.of(asciiFolding(), lowercase()), TR);
					final Comparator<String> comparator = comparator(true);
					final int[] expected = IntStream.rangeClosed(1, MISSING).boxed().sorted((a, b) -> {
						if (a == MISSING || b == MISSING) {
							return Integer.compare(a, b);
						}
						final int first = comparator.compare(root.apply(VALUES[a - 1]), root.apply(VALUES[b - 1]));
						final int second = comparator.compare(local.apply(VALUES[a - 1]), local.apply(VALUES[b - 1]));
						return first != 0 ? first : second != 0 ? second : Integer.compare(a, b);
					}).mapToInt(Integer::intValue).toArray();
					for (final PlanPreference route : PlanPreference.values()) {
						assertArrayEquals(expected, queryIds(type, route, null, attributeNatural(name)));
						assertArrayEquals(expected, queryIds(type, route, null, referenceProperty(REF,
							attributeNatural(name))));
						assertArrayEquals(expected, queryIds(type, route, null, referenceProperty(REF,
							traverseByEntityProperty(entityPrimaryKeyNatural(ASC)), attributeNatural(name))));
					}
					assertReferenceOrder(type, name, expected);
				}));
			}
		}
		return tests.stream();
	}

	/**
	 * Every filter is exercised by index, prefetch, reference row, reduced reference index and catalog filtering.
	 *
	 * @param type entity collection (`Single` or `Paged`) the filter is run against
	 * @param name name of the normalized attribute the filter targets
	 * @param filter filter and the predicate the matching keys must satisfy
	 * @param key normalization function of the attribute, applied to raw fixture values to compute the expectation
	 */
	private void assertFilter(
		@Nonnull String type, @Nonnull String name, @Nonnull FilterCase filter, @Nonnull UnaryOperator<String> key
	) {
		final int[] expected = expected(filter.predicate(), key);
		for (final PlanPreference route : PlanPreference.values()) {
			assertArrayEquals(expected, queryIds(type, route, filter.constraint().apply(name), null), route.toString());
		}
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final EvitaResponse<SealedEntity> rows = session.query(query(collection(type),
				filterBy(entityPrimaryKeyInSet(CANDIDATES), entityLocaleEquals(TR)),
				require(page(1, 1000), entityFetch(referenceContentWithAttributes(REF,
					filterBy(filter.constraint().apply(name)),
					attributeContentAll())))), SealedEntity.class);
			assertArrayEquals(expected, rows.getRecordData().stream().filter(e -> !e.getReferences(REF).isEmpty())
				.mapToInt(SealedEntity::getPrimaryKeyOrThrowException).toArray(), "reference row");
			final EvitaResponse<EntityReference> references = session.query(query(collection(type),
				filterBy(entityPrimaryKeyInSet(CANDIDATES), entityLocaleEquals(TR),
					referenceHaving(REF, filter.constraint().apply(name))), require(page(1, 1000))),
						EntityReference.class);
			assertArrayEquals(expected, ids(references), "reference index");
			final EvitaResponse<EntityReference> catalog = session.query(query(
				filterBy(attributeInSet("lookup", Arrays.stream(CANDIDATES).mapToObj(pk -> type + pk)
					.toArray(String[]::new)), entityLocaleEquals(TR), filter.constraint().apply(name)),
				require(page(1, 1000))), EntityReference.class);
			assertArrayEquals(expected, ids(catalog), "catalog entity filtering");
			return null;
		});
	}

	/**
	 * Telemetry proves the requested route for constraints with an alternative. Typed equality and set lookups read
	 * the index on both plans; their matcher semantics are checked by the row and collection-less arms of assertFilter.
	 *
	 * @param type entity collection (`Single` or `Paged`) to query
	 * @param route plan preference forcing index or prefetch evaluation
	 * @param filter extra filter constraint, `null` for none
	 * @param order ordering constraint, `null` for primary-key order
	 * @return primary keys of the matching entities in result order
	 */
	@Nonnull
	private int[] queryIds(
		@Nonnull String type, @Nonnull PlanPreference route, FilterConstraint filter, OrderConstraint order
	) {
		return this.evita.queryCatalog(TEST_CATALOG, session -> {
			final EvitaResponse<EntityReference> response = session.query(query(collection(type),
				filterBy(route.filter(CANDIDATES, entityPrimaryKeyInSet(CANDIDATES), entityLocaleEquals(TR), filter)),
				orderBy(order), require(page(1, 1000), queryTelemetry(), route.debug())), EntityReference.class);
			if (route == PlanPreference.INDEX_SCAN || order != null ||
				!(filter instanceof AttributeEquals || filter instanceof AttributeInSet)) {
				route.assertTaken(response);
			}
			return ids(response);
		});
	}

	/**
	 * Normalized collisions keep the first probe's rank, including across page offsets and the in-filter variant.
	 *
	 * @param type entity collection (`Single` or `Paged`) to query
	 * @param name name of the normalized attribute used for the probes and the ordering
	 * @param key normalization function of the attribute, applied to the probes and fixture values to compute the
	 *     expectation
	 */
	private void assertExactOrder(@Nonnull String type, @Nonnull String name, @Nonnull UnaryOperator<String> key) {
		final String[] probes = {" abc ", "ABC", " abc ", "ǧ", "ᴀ", "I", "TITLE", "title", "", "--- ", "- a -"};
		final List<String> keys = Arrays.stream(probes).map(key).distinct().toList();
		final int[] exact = IntStream.rangeClosed(1, VALUES.length).boxed()
			.filter(pk -> keys.contains(key.apply(VALUES[pk - 1])))
			.sorted(Comparator.<Integer>comparingInt(pk -> keys.indexOf(key.apply(VALUES[pk - 1])))
				.thenComparingInt(Integer::intValue)).mapToInt(Integer::intValue).toArray();
		for (final PlanPreference route : PlanPreference.values()) {
			assertArrayEquals(exact, queryIds(type, route, attributeInSet(name, probes), attributeSetExact(name,
				probes)));
			assertArrayEquals(exact, queryIds(type, route, attributeInSet(name, probes), attributeSetInFilter(name)));
			assertArrayEquals(exact, queryIds(type, route,
				and(attributeInSet(name, probes), referenceHaving(REF, entityPrimaryKeyInSet(1))),
				referenceProperty(REF, attributeSetExact(name, probes))));
			this.evita.queryCatalog(TEST_CATALOG, session -> {
				final EvitaResponse<EntityReference> response = session.query(query(collection(type),
					filterBy(route.filter(CANDIDATES, entityPrimaryKeyInSet(CANDIDATES), entityLocaleEquals(TR),
						attributeInSet(name, probes))), orderBy(attributeSetExact(name, probes)),
					require(page(2, 2), queryTelemetry(), route.debug())), EntityReference.class);
				route.assertTaken(response);
				assertArrayEquals(Arrays.copyOfRange(exact, 2, Math.min(4, exact.length)), ids(response));
				return null;
			});
		}
	}

	/**
	 * References themselves compare raw values through the same scalar or compound function.
	 *
	 * @param type entity collection (`Single` or `Paged`) owning the references
	 * @param name name of the normalized reference attribute to order by
	 * @param expected owner primary keys in expected order; `MISSING` entries have no reference and are skipped
	 */
	private void assertReferenceOrder(@Nonnull String type, @Nonnull String name, @Nonnull int[] expected) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity owner = session.queryOne(query(collection(type),
				filterBy(entityPrimaryKeyInSet(REFERENCE_OWNER), entityLocaleEquals(TR)),
				require(entityFetch(referenceContentWithAttributes(REF, orderBy(attributeNatural(name)),
					attributeContentAll())))),
				SealedEntity.class).orElseThrow();
			assertArrayEquals(Arrays.stream(expected).filter(pk -> pk != MISSING).toArray(), owner.getReferences(REF)
				.stream().mapToInt(ReferenceContract::getReferencedPrimaryKey).toArray());
			return null;
		});
	}

	/**
	 * Includes empty raw and normalized patterns, non-stable chains, comparisons and presence.
	 *
	 * @param key normalization function of the attribute, applied to probe values to compute the expectation
	 * @param comparator index comparator ordering normalized keys
	 * @return filter cases with the keys they are expected to match
	 */
	@Nonnull
	private static List<FilterCase> filters(@Nonnull UnaryOperator<String> key,
		@Nonnull Comparator<String> comparator) {
		final List<FilterCase> cases = new ArrayList<>();
		cases.add(new FilterCase("inSet", a -> attributeInSet(a, "ǧ", "ᴀ", "I", "TITLE", "TITLE"),
			value -> Stream.of("ǧ", "ᴀ", "I", "TITLE").map(key).anyMatch(value::equals)));
		cases.add(new FilterCase("less", a -> attributeLessThan(a, "I"), v -> comparator.compare(v,
			key.apply("I")) < 0));
		cases.add(new FilterCase("lessEqual", a -> attributeLessThanEquals(a, "I"),
			v -> comparator.compare(v, key.apply("I")) <= 0));
		cases.add(new FilterCase("greater", a -> attributeGreaterThan(a, "ǧ"),
			v -> comparator.compare(v, key.apply("ǧ")) > 0));
		cases.add(new FilterCase("greaterEqual", a -> attributeGreaterThanEquals(a, "ǧ"),
			v -> comparator.compare(v, key.apply("ǧ")) >= 0));
		final String from = comparator.compare(key.apply("ǧ"), key.apply("TITLE")) <= 0 ? "ǧ" : "TITLE";
		final String to = from.equals("ǧ") ? "TITLE" : "ǧ";
		cases.add(new FilterCase("between", a -> attributeBetween(a, from, to),
			v -> comparator.compare(v, key.apply(from)) >= 0 && comparator.compare(v, key.apply(to)) <= 0));
		for (final String probe : List.of("ǧ", "ǧᴀǧ", "TITLE", "--- ", "")) {
			for (final boolean accelerated : List.of(false, true)) {
				final String suffix = accelerated ? "Fast" : "";
				cases.add(new FilterCase("starts/" + probe + suffix,
					a -> attributeStartsWith(a + suffix, probe), v -> v.startsWith(key.apply(probe))));
				cases.add(new FilterCase("contains/" + probe + suffix,
					a -> attributeContains(a + suffix, probe), v -> v.contains(key.apply(probe))));
				cases.add(new FilterCase("ends/" + probe + suffix,
					a -> attributeEndsWith(a + suffix, probe), v -> v.endsWith(key.apply(probe))));
			}
		}
		return cases;
	}

	/**
	 * Expected IDs are calculated from raw fixture bodies, never from a query or an index result.
	 *
	 * @param test predicate over a normalized key
	 * @param key normalization function applied to each raw fixture value
	 * @return primary keys of the fixture entities whose normalized value satisfies the predicate, ascending
	 */
	@Nonnull
	private static int[] expected(@Nonnull Predicate<String> test, @Nonnull UnaryOperator<String> key) {
		return IntStream.rangeClosed(1, VALUES.length).filter(pk -> test.test(key.apply(VALUES[pk - 1]))).toArray();
	}

	/**
	 * Equal keys use ascending owner IDs, and the missing attribute is handed to the final primary-key sorter.
	 *
	 * @param key normalization function applied to each raw fixture value
	 * @param comparator index comparator ordering normalized keys
	 * @return primary keys in expected ascending order, the entity without the attribute last
	 */
	@Nonnull
	private static int[] sorted(@Nonnull UnaryOperator<String> key, @Nonnull Comparator<String> comparator) {
		return IntStream.rangeClosed(1, MISSING).boxed().sorted((a, b) -> {
			if (a == MISSING || b == MISSING) {
				return Integer.compare(a, b);
			}
			final int comparison = comparator.compare(key.apply(VALUES[a - 1]), key.apply(VALUES[b - 1]));
			return comparison == 0 ? Integer.compare(a, b) : comparison;
		}).mapToInt(Integer::intValue).toArray();
	}

	/**
	 * The index comparator's locale is schema-masked, independently of the Turkish locale in every query.
	 *
	 * @param localized `true` to build the comparator for the localized attribute, `false` for the global one
	 * @return comparator the attribute's filter index uses for its keys
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	private static Comparator<String> comparator(boolean localized) {
		return (Comparator) FilterIndex.getComparator(new AttributeIndexKey(null, "value", localized ? TR : null),
			String.class);
	}

	/**
	 * Defines identical values on the entity, a shared reduced partition and single-owner virtual partitions.
	 *
	 * @param session read-write session used to define the schema and upsert the entities
	 * @param type name of the entity collection to define and fill
	 * @param count number of entities to create, with primary keys `1..count`
	 */
	private void fill(@Nonnull EvitaSessionContract session, @Nonnull String type, int count) {
		EntitySchemaBuilder schema = session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withLocale(TR)
			.withGlobalAttribute("lookup").withAttribute("tie", Integer.class, a -> a.nullable())
			.withAttribute("marker", String.class, a -> a.localized().nullable());
		for (final Policy policy : POLICIES) {
			for (final boolean localized : List.of(false, true)) {
				for (final boolean control : List.of(false, true)) {
					// All policy controls have the same NFD-only schema and raw values; one tree is sufficient.
					if (control && policy != POLICIES.getFirst()) {
						continue;
					}
					final String name = attributeName(policy, localized, control);
					final AnalysisStep[] steps = control ? new AnalysisStep[0] :
						policy.steps().toArray(AnalysisStep[]::new);
					schema = schema.withAttribute(name, String.class, a -> configure(a, localized, steps).sortable())
						.withAttribute(name + "Fast", String.class, a -> configure(a, localized, steps)
							.acceleratedFor(SUBSTRING_SEARCH))
						.withAttribute(name + "Array", String[].class, a -> configure(a, localized, steps))
						.withSortableAttributeCompound(name + "Compound", element(name), element("tie"));
				}
			}
		}
		schema.withSortableAttributeCompound("mixed", element("lowerRoot"), element("asciiLowerTr"))
			.withSortableAttributeCompound("mixedControl", element("controlRoot"), element("controlTr"))
			.withReferenceToEntity(REF, "Target", Cardinality.ZERO_OR_MORE,
			r -> referenceSchema(r.indexedForFilteringAndPartitioning()))
			.withReferenceToEntity("virtual", "Target", Cardinality.ZERO_OR_MORE,
				r -> referenceSchema(r.indexedForFilteringAndPartitioning())).updateVia(session);
		for (int pk = 1; pk <= count; pk++) {
			final EntityBuilder entity = session.createNewEntity(type, pk).setAttribute("lookup", type + pk)
				.setAttribute("marker", TR, "present");
			if (pk != MISSING) {
				final String raw = pk <= VALUES.length ? VALUES[pk - 1] : "zzpadding" + pk;
				populate(entity, raw);
				entity.setReference(REF, 1, r -> populate(r, raw));
				entity.setReference("virtual", pk, r -> populate(r, raw));
			}
			entity.upsertVia(session);
		}
		final EntityBuilder owner = session.createNewEntity(type, REFERENCE_OWNER);
		for (int pk = 1; pk <= VALUES.length; pk++) {
			final String raw = VALUES[pk - 1];
			owner.setReference(REF, pk, r -> populate(r, raw));
		}
		owner.upsertVia(session);
	}

	/**
	 * The same normalizer declaration is used by reduced and virtual reference indexes.
	 *
	 * @param reference reference schema editor to declare the normalized attributes and compounds on
	 * @return the same editor, for chaining
	 */
	@Nonnull
	private static <T extends ReferenceSchemaEditor<T>> T referenceSchema(@Nonnull T reference) {
		reference.withAttribute("tie", Integer.class, a -> a.nullable());
		for (final Policy policy : POLICIES) {
			for (final boolean localized : List.of(false, true)) {
				for (final boolean control : List.of(false, true)) {
					// All policy controls have the same NFD-only schema and raw values; one tree is sufficient.
					if (control && policy != POLICIES.getFirst()) {
						continue;
					}
					final String name = attributeName(policy, localized, control);
					final AnalysisStep[] steps = control ? new AnalysisStep[0] :
						policy.steps().toArray(AnalysisStep[]::new);
					reference.withAttribute(name, String.class, a -> configure(a, localized, steps).sortable())
						.withAttribute(name + "Fast", String.class, a -> configure(a, localized, steps))
						.withAttribute(name + "Array", String[].class, a -> configure(a, localized, steps))
						.withSortableAttributeCompound(name + "Compound", element(name), element("tie"));
				}
			}
		}
		return reference.withSortableAttributeCompound("mixed", element("lowerRoot"), element("asciiLowerTr"))
			.withSortableAttributeCompound("mixedControl", element("controlRoot"), element("controlTr"));
	}

	/**
	 * Raw values, including empty Strings, are left intact in the entity body and both references.
	 *
	 * @param editor entity or reference attributes editor to fill
	 * @param raw raw value written to every normalized attribute
	 */
	private static void populate(@Nonnull AttributesEditor<?, ?> editor, @Nonnull String raw) {
		editor.setAttribute("tie", 0);
		for (final Policy policy : POLICIES) {
			for (final boolean localized : List.of(false, true)) {
				for (final boolean control : List.of(false, true)) {
					// All policy controls have the same NFD-only schema and raw values; one tree is sufficient.
					if (control && policy != POLICIES.getFirst()) {
						continue;
					}
					final String name = attributeName(policy, localized, control);
					if (localized) {
						editor.setAttribute(name, TR, raw).setAttribute(name + "Fast", TR, raw)
							.setAttribute(name + "Array", TR, new String[]{raw, raw});
					} else {
						editor.setAttribute(name, raw).setAttribute(name + "Fast", raw)
							.setAttribute(name + "Array", new String[]{raw, raw});
					}
				}
			}
		}
	}

	/**
	 * Configures the normalized and control schemas with identical indexing and localization.
	 *
	 * @param attribute attribute schema editor to configure
	 * @param localized whether the attribute is localized
	 * @param steps normalization steps to declare, empty for the control attribute
	 * @return the same editor, for chaining
	 */
	@Nonnull
	private static <T extends AttributeSchemaEditor<T>> T configure(
		@Nonnull T attribute, boolean localized, @Nonnull AnalysisStep[] steps
	) {
		attribute.filterable().nullable().normalized(steps);
		if (localized) {
			attribute.localized();
		}
		return attribute;
	}

	/**
	 * Each policy is paired with the shared legacy control having the same indexing and localization.
	 *
	 * @param policy policy whose name prefixes the attribute
	 * @param localized whether the attribute is the localized (`Tr`) variant
	 * @param control whether to name the shared control attribute without normalization
	 * @return attribute name for the given combination
	 */
	@Nonnull
	private static String attributeName(@Nonnull Policy policy, boolean localized, boolean control) {
		return (control ? "control" : policy.name()) + (localized ? "Tr" : "Root");
	}

	/**
	 * Makes a compound element with the same direction as the scalar ordering.
	 *
	 * @param name name of the attribute forming the compound element
	 * @return ascending element with nulls last
	 */
	@Nonnull
	private static AttributeElement element(@Nonnull String name) {
		return new AttributeElement(name, ASC, NULLS_LAST);
	}

	/**
	 * Extracts result IDs without depending on fetched or indexed attribute representations.
	 *
	 * @param response query response to extract the identifiers from
	 * @return primary keys of the returned references in result order
	 */
	@Nonnull
	private static int[] ids(@Nonnull EvitaResponse<EntityReference> response) {
		return response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray();
	}
}
