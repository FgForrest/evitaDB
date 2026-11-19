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

package io.evitadb.api.functional.fulltext;

import java.math.BigDecimal;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.TextMatchesEntitySpecificationFilterConstraint;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.math.BigDecimal.valueOf;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.ReferenceSearchMode.COMPOSITION;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/** Differential membership and scoring over real own, reference and composed places, including name collisions. */
@Tag(ENGINE)
@Tag(FILTER)
@Tag(QUERY)
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Fulltext place selector planning and scoring")
class FulltextPlaceSelectorsFunctionalTest implements EvitaTestSupport {
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private static final String[] TYPES = {
		"AllPlaces", "AttributeOnly", "DataOnly", "ReferenceOnly", "TargetAttribute", "TargetData", "TargetReference"
	};
	private TestPaths paths;
	private Evita evita;

	/** Builds one full fixture and six copies restricted by schema to one scoring place each. */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("FulltextPlaceSelectorsFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			for (final String type : TYPES) {
				final boolean all = type.equals("AllPlaces");
				final String target = type + "Block";
				session.defineEntitySchema(target).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("shared", String.class, a -> {
						a.localized().nullable().lengthPivot(50).searchWeight(valueOf(2));
						if (all || type.equals("TargetAttribute")) a.searchableInScope(BOTH);
					})
					.withAttribute("hidden", String.class, a -> a.localized().nullable())
					.withAttribute("liveOnly", String.class, a -> {
						a.localized().nullable();
						if (all) a.searchable();
					})
					.withAssociatedData("liveOnly", String.class, a -> {
						a.localized().nullable();
						if (all) a.searchable();
					})
					.withAssociatedData("shared", String.class, a -> {
						a.localized().nullable().lengthPivot(50).searchWeight(valueOf(3));
						if (all || type.equals("TargetData")) a.searchableInScope(BOTH);
					})
					.withAssociatedData("hidden", String.class, a -> a.localized().nullable())
					.withReferenceTo("tags", "Tag", Cardinality.ZERO_OR_MORE, r ->
						r.indexedForFilteringInScope(BOTH).withAttribute("shared", String.class, a -> {
							a.localized().nullable().lengthPivot(50).searchWeight(valueOf(4));
							if (all || type.equals("TargetReference")) a.searchableInScope(BOTH);
						}))
					.updateVia(session);
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("shared", String.class, a -> {
						a.localized().nullable().lengthPivot(50).searchWeight(valueOf(2));
						if (all || type.equals("AttributeOnly")) a.searchableInScope(BOTH);
					})
					.withAttribute("hidden", String.class, a -> a.localized().nullable())
					.withAttribute("liveOnly", String.class, a -> {
						a.localized().nullable();
						if (all) a.searchable();
					})
					.withAssociatedData("shared", String.class, a -> {
						a.localized().nullable().lengthPivot(50).searchWeight(valueOf(3));
						if (all || type.equals("DataOnly")) a.searchableInScope(BOTH);
					})
					.withAssociatedData("other", String.class, a -> {
						a.localized().nullable();
						if (all) a.searchableInScope(BOTH);
					})
					.withAssociatedData("hidden", String.class, a -> a.localized().nullable())
					.withAssociatedData("liveOnly", String.class, a -> {
						a.localized().nullable();
						if (all) a.searchable();
					})
					.withReferenceToEntity("blocks", target, Cardinality.ZERO_OR_MORE, r -> {
						r.indexedForFilteringInScope(BOTH)
							.withAttribute("shared", String.class, a -> {
								a.localized().nullable().lengthPivot(50).searchWeight(valueOf(4));
								if (all || type.equals("ReferenceOnly")) a.searchableInScope(BOTH);
							})
							.withAttribute("other", String.class, a -> {
								a.localized().nullable();
								if (all) a.searchableInScope(BOTH);
							})
							.withAttribute("hidden", String.class, a -> a.localized().nullable())
							.withAttribute("liveOnly", String.class, a -> {
								a.localized().nullable();
								if (all) a.searchable();
							});
						if (all || type.startsWith("Target")) {
							r.searchableThroughInScope(COMPOSITION, valueOf(0.4), BOTH);
						}
					})
					.withReferenceToEntity("raw", target, Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(BOTH))
					.withReferenceToEntity("liveBlocks", target, Cardinality.ZERO_OR_MORE, r ->
						r.indexedForFilteringInScope(BOTH).searchableThrough(COMPOSITION))
					.updateVia(session);
				for (int id = 101; id <= 104; id++) {
					final EntityBuilder block = session.createNewEntity(target, id)
						.setAttribute("shared", Locale.ENGLISH, id == 101 || id == 104 ? "quartz" : "plain")
						.setAssociatedData("shared", Locale.ENGLISH, id == 102 || id == 104 ? "quartz" : "plain");
					if (id == 103 || id == 104) block.setReference("tags", 1,
						r -> r.setAttribute("shared", Locale.ENGLISH, "quartz"));
					session.upsertEntity(block);
				}
				for (int id = 1; id <= 9; id++) {
					final boolean combined = id == 7 || id == 9;
					final EntityBuilder owner = session.createNewEntity(type, id)
						.setAttribute("shared", Locale.ENGLISH, id == 1 || combined ? "quartz" : "plain")
						.setAssociatedData("shared", Locale.ENGLISH, id == 2 || combined ? "quartz" : "plain");
					if (id == 3 || combined) owner.setReference("blocks", 101,
						r -> r.setAttribute("shared", Locale.ENGLISH, "quartz"));
					if (id >= 4 && id <= 6) owner.setReference("blocks", id + 97);
					if (combined) owner.setReference("blocks", 104);
					if (id == 8) {
						owner.setAssociatedData("other", Locale.ENGLISH, "quartz");
						owner.setReference("blocks", 101, r -> r.setAttribute("other", Locale.ENGLISH, "quartz"));
					}
					session.upsertEntity(owner);
					if (id == 9) session.archiveEntity(type, id);
				}
			}
			session.defineEntitySchema("ScopedBlock").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("text", String.class, a -> a.localized().searchable()).updateVia(session);
			session.defineEntitySchema("ScopedOwner").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("caption", String.class, a -> a.localized())
				.withReferenceToEntity("blocks", "ScopedBlock", Cardinality.ZERO_OR_MORE, r ->
					r.indexedForFilteringInScope(BOTH).searchableThroughInScope(COMPOSITION, valueOf(0.4), BOTH))
				.updateVia(session);
			session.upsertEntity(session.createNewEntity("ScopedBlock", 1)
				.setAttribute("text", Locale.ENGLISH, "quartz"));
			for (int id = 1; id <= 2; id++) {
				session.upsertEntity(session.createNewEntity("ScopedOwner", id)
					.setAttribute("caption", Locale.ENGLISH, "plain").setReference("blocks", 1));
			}
			session.archiveEntity("ScopedOwner", 2);
			// Every place kind is searchable in LIVE, only associated data in ARCHIVED.
			session.defineEntitySchema("SparseOwner").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("caption", String.class, a -> a.localized().nullable().searchable())
				.withAssociatedData("body", String.class, a -> a.localized().nullable().searchableInScope(BOTH))
				.withReferenceTo("notes", "Note", Cardinality.ZERO_OR_MORE, r -> r.indexedForFilteringInScope(BOTH)
					.withAttribute("note", String.class, a -> a.localized().nullable().searchable()))
				.updateVia(session);
			session.upsertEntity(session.createNewEntity("SparseOwner", 1)
				.setAttribute("caption", Locale.ENGLISH, "quartz"));
			session.upsertEntity(session.createNewEntity("SparseOwner", 2)
				.setAssociatedData("body", Locale.ENGLISH, "quartz"));
			session.upsertEntity(session.createNewEntity("SparseOwner", 3)
				.setReference("notes", 1, r -> r.setAttribute("note", Locale.ENGLISH, "quartz")));
			session.archiveEntity("SparseOwner", 2);
			defineAnalyzerFixtures(session);
			session.goLiveAndClose();
		});
	}

	/** Shares identical data between full schemas and copies exposing only the selected places. */
	private void defineAnalyzerFixtures(@Nonnull EvitaSessionContract session) {
		session.getCatalogSchema().openForWrite()
			.withFulltextResource("selectorStops", wordList("the"))
			.withFulltextAnalyzer("selectorLiteral", a -> a.words(lowercase()))
			.withFulltextAnalyzer("selectorStop", a -> a.words(lowercase(), stopWords(resource("selectorStops"))))
			.withFulltextAnalyzer("selectorJoined", a -> a.text(removeCharacters("-")).words(lowercase()))
			.updateVia(session);
		for (final String shape : new String[]{"Stop", "Split", "Cap"}) {
			for (final boolean restricted : new boolean[]{false, true}) {
				final String type = "Selected" + shape + (restricted ? "Only" : "Full");
				final String analyzer = switch (shape) {
					case "Stop" -> "selectorStop";
					case "Split" -> "selectorJoined";
					case "Cap" -> "selectorLiteral";
					default -> throw new AssertionError("Unknown analyzer fixture: " + shape);
				};
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("literal", String.class, a -> {
						a.localized().nullable().analyzer("selectorLiteral").lengthPivot(50);
						if (!restricted) a.searchable();
					})
					.withAssociatedData("body", String.class,
						a -> a.localized().nullable().searchable().analyzer(analyzer).lengthPivot(50))
					.updateVia(session);
				if (shape.equals("Cap")) {
					for (int id = 1; id <= 100; id++) {
						final int term = id - 1;
						session.upsertEntity(session.createNewEntity(type, id).setAttribute("literal", Locale.ENGLISH,
							"quartza" + (char) ('a' + term / 26) + (char) ('a' + term % 26)));
					}
					session.upsertEntity(session.createNewEntity(type, 101)
						.setAttribute("literal", Locale.ENGLISH, "plain")
						.setAssociatedData("body", Locale.ENGLISH, "quartzzzz"));
				} else {
					final String body = shape.equals("Stop") ? "quartz" : "USB-TYPEC";
					for (int id = 1; id <= 2; id++) {
						session.upsertEntity(session.createNewEntity(type, id)
							.setAttribute("literal", Locale.ENGLISH, shape.equals("Split") ? "red" : "plain")
							.setAssociatedData("body", Locale.ENGLISH, id == 1 ? body : body + " " + body));
					}
				}
			}
		}
		for (final boolean restricted : new boolean[]{false, true}) {
			final String owner = restricted ? "SelectedComposedOnly" : "SelectedComposedFull";
			final String target = owner + "Block";
			session.defineEntitySchema(target).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("literal", String.class, a -> {
					a.localized().nullable().analyzer("selectorLiteral");
					if (!restricted) a.searchable();
				})
				.withAssociatedData("body", String.class,
					a -> a.localized().searchable().analyzer("selectorStop").lengthPivot(50)).updateVia(session);
			session.defineEntitySchema(owner).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("literal", String.class, a -> {
					a.localized().analyzer("selectorLiteral");
					if (!restricted) a.searchable();
				})
				.withReferenceToEntity("blocks", target, Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering().searchableThrough(COMPOSITION, valueOf(0.4))).updateVia(session);
			for (int id = 1; id <= 2; id++) {
				session.upsertEntity(session.createNewEntity(target, id)
					.setAttribute("literal", Locale.ENGLISH, "plain")
					.setAssociatedData("body", Locale.ENGLISH, id == 1 ? "quartz" : "quartz quartz"));
				session.upsertEntity(session.createNewEntity(owner, id)
					.setAttribute("literal", Locale.ENGLISH, "plain").setReference("blocks", id));
			}
		}
	}

	/** Releases the fixture after all read-only comparisons. */
	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** These single-analyzer places restrict membership; empty targets include target reference attributes. */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"attribute", "associatedData", "reference", "entity"})
	@DisplayName("Restricts each selector kind in the single-analyzer fixture")
	void shouldRestrictEachKind(@Nonnull String kind) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			switch (kind) {
				case "attribute" -> {
					assertIds(session, new int[]{1, 7}, inAttribute("shared"));
					assertIds(session, new int[]{1, 7}, inAttributeAll());
				}
				case "associatedData" -> {
					assertIds(session, new int[]{2, 7}, inAssociatedData("shared"));
					assertIds(session, new int[]{2, 7, 8}, inAssociatedDataAll());
					assertIds(session, new int[]{2, 7, 8}, inAssociatedData("shared", "other"));
					assertIds(session, new int[]{1, 2, 7}, inAttribute("shared"), inAssociatedData("shared"));
				}
				case "reference" -> {
					assertIds(session, new int[]{3, 7, 8}, inReference("blocks"));
					assertIds(session, new int[]{3, 7, 8}, inReference("blocks", inAttributeAll()));
					assertIds(session, new int[]{3, 7}, inReference("blocks", inAttribute("shared")));
					assertIds(session, new int[]{3, 7, 8}, inReference("blocks", inAttribute("shared", "other")));
				}
				case "entity" -> {
					assertIds(session, new int[]{3, 4, 5, 6, 7, 8}, inReference("blocks", inEntity()));
					assertIds(session, new int[]{3, 4, 7, 8}, inReference("blocks", inEntity(inAttributeAll())));
					assertIds(session, new int[]{5, 7}, inReference("blocks", inEntity(inAssociatedDataAll())));
					assertIds(session, new int[]{3, 4, 5, 7, 8},
						inReference("blocks", inEntity(inAttributeAll(), inAssociatedDataAll())));
				}
				default -> throw new AssertionError("Unknown selector kind: " + kind);
			}
		});
	}

	/**
	 * Naming every place keeps plain-search membership, order and scores. Strict subsets below are a property of
	 * this single-analyzer fixture with no competing dictionary expansions, not the general selector contract.
	 */
	@Test
	@DisplayName("Matches plain search exactly when all places are selected")
	void shouldMatchPlainSearchWithAllSelectors() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint plain = textMatches("quartz ");
			final FilterConstraint selected = textMatches("quartz ", inAttributeAll(), inAssociatedDataAll(),
				inReference("blocks", inAttributeAll(), inEntity()), inReference("liveBlocks", inEntity()));
			assertArrayEquals(ids(session, "AllPlaces", plain), ids(session, "AllPlaces", selected));
			assertArrayEquals(rankedIds(session, plain), rankedIds(session, selected));
			final int[] candidates = ids(session, "AllPlaces", plain);
			assertArrayEquals(scores(session, "AllPlaces", plain, candidates),
				scores(session, "AllPlaces", selected, candidates));
			for (final TextMatchesEntitySpecificationFilterConstraint selector :
				new TextMatchesEntitySpecificationFilterConstraint[]{
				inAttributeAll(), inAssociatedDataAll(), inReference("blocks"), inReference("blocks", inEntity())
			}) {
				final int[] subset = ids(session, "AllPlaces", textMatches("quartz ", selector));
				assertTrue(subset.length < candidates.length);
				for (final int id : subset) assertTrue(Arrays.binarySearch(candidates, id) >= 0);
			}
		});
	}

	/** A selected single place retains the score obtained when the schema makes it the only searchable place. */
	@Test
	@DisplayName("Keeps each selected place's plain-search score and composition weight")
	void shouldKeepSinglePlaceScores() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final TextMatchesEntitySpecificationFilterConstraint[] selectors = {
				inAttribute("shared"), inAssociatedData("shared"), inReference("blocks", inAttribute("shared")),
				inReference("blocks", inEntity(inAttribute("shared"))),
				inReference("blocks", inEntity(inAssociatedData("shared"))), inReference("blocks", inEntity())
			};
			for (int kind = 0; kind < selectors.length - 1; kind++) {
				final FilterConstraint selected = textMatches("quartz ", selectors[kind]);
				final FilterConstraint plain = textMatches("quartz ");
				final String restricted = TYPES[kind + 1];
				final int[] candidates = ids(session, restricted, plain);
				assertArrayEquals(candidates, ids(session, "AllPlaces", selected), restricted);
				assertArrayEquals(scores(session, restricted, plain, candidates),
					scores(session, "AllPlaces", selected, candidates), restricted);
			}
			// Only the empty target form can name target reference attributes. Owner 6 holds just that target place.
			assertArrayEquals(scores(session, "TargetReference", textMatches("quartz "), new int[]{6}),
				scores(session, "AllPlaces", textMatches("quartz ", selectors[5]), new int[]{6}));
		});
	}

	/** Repeated names, overlapping wildcards, references and targets are searched and scored once. */
	@Test
	@DisplayName("Merges repeated reference selectors and ignores duplicate places")
	void shouldMergeDuplicateSelectors() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint selected = textMatches("quartz ", inAttributeAll(), inAssociatedDataAll(),
				inReference("blocks", inAttributeAll(), inEntity()));
			final FilterConstraint duplicates = textMatches("quartz ", inAttribute("shared", "shared"),
				inAttributeAll(), inAssociatedData("shared", "other", "shared"), inAssociatedDataAll(),
				inReference("blocks", inAttribute("shared", "shared")), inReference("blocks", inAttributeAll()),
				inReference("blocks", inEntity(inAttribute("shared"), inAssociatedData("shared"))),
				inReference("blocks", inEntity()), inReference("blocks", inEntity()));
			final int[] candidates = ids(session, "AllPlaces", selected);
			assertArrayEquals(candidates, ids(session, "AllPlaces", duplicates));
			assertArrayEquals(rankedIds(session, selected), rankedIds(session, duplicates));
			assertArrayEquals(scores(session, "AllPlaces", selected, candidates),
				scores(session, "AllPlaces", duplicates, candidates));
			assertNotEquals(
				plannedFormula(session, "AllPlaces", textMatches("quartz ", inAttribute("shared")))
					.getHash(),
				plannedFormula(session, "AllPlaces", textMatches("quartz ", inAssociatedData("shared")))
					.getHash());
		});
	}

	/** Schema errors name the offending place and are raised even when the query locale has no data. */
	@Test
	@DisplayName("Refuses unknown, non-searchable and empty selector results before data shortcuts")
	void shouldRefuseInvalidPlaces() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
				for (final TextMatchesEntitySpecificationFilterConstraint selector :
				new TextMatchesEntitySpecificationFilterConstraint[]{
					inAttribute("shared", "missing"), inAssociatedData("shared", "missing"), inReference("missing"),
					inReference("blocks", inAttribute("shared", "missing")),
					inReference("blocks", inEntity(inAttribute("missing"))),
					inReference("blocks", inEntity(inAssociatedData("missing")))
				}) {
					final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class, () ->
						session.query(query(collection("AllPlaces"), filterBy(entityLocaleEquals(locale),
							textMatches("quartz ", selector))), EntityReference.class));
					assertTrue(error.getMessage().contains("missing"), error.getMessage());
					assertTrue(error instanceof AttributeNotFoundException ||
						error instanceof AssociatedDataNotFoundException ||
						error instanceof ReferenceNotFoundException, error.toString());
				}
			}
			for (final TextMatchesEntitySpecificationFilterConstraint selector :
				new TextMatchesEntitySpecificationFilterConstraint[]{
				inAttribute("hidden"), inAssociatedData("hidden"), inReference("blocks", inAttribute("hidden")),
				inReference("blocks", inEntity(inAttribute("hidden"))),
				inReference("blocks", inEntity(inAssociatedData("hidden")))
			}) {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> ids(session, "AllPlaces", textMatches("quartz ", selector)));
				assertTrue(error.getMessage().contains("hidden"), error.getMessage());
			}
			// The refusal names the selector the query states, never the wildcard a childless reference stands for.
			final EvitaInvalidUsageException noAttribute = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "AllPlaces", textMatches("quartz ", inReference("raw"))));
			assertTrue(noAttribute.getMessage().contains("inReference('raw')"), noAttribute.getMessage());
			assertFalse(noAttribute.getMessage().contains("inAttributeAll"), noAttribute.getMessage());
			final EvitaInvalidUsageException noComposition = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "AllPlaces", textMatches("quartz ", inReference("raw", inEntity()))));
			assertTrue(noComposition.getMessage().contains("raw"), noComposition.getMessage());
			final EvitaInvalidUsageException emptyTarget = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "AttributeOnly", textMatches("quartz ", inReference("liveBlocks", inEntity()))));
			assertTrue(emptyTarget.getMessage().contains("liveBlocks"), emptyTarget.getMessage());
			// A target wildcard empty in every processed scope is refused even beside a sibling that selects places.
			final EvitaInvalidUsageException emptyTargetWildcard = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "TargetAttribute", textMatches("quartz ",
					inReference("blocks", inEntity(inAttributeAll(), inAssociatedDataAll())))));
			assertTrue(emptyTargetWildcard.getMessage().contains("inAssociatedDataAll"),
				emptyTargetWildcard.getMessage());
			assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "AttributeOnly", textMatches("quartz ", inAssociatedDataAll())));
			assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "DataOnly", textMatches("quartz ", inAttributeAll())));
		});
	}

	/** Attribute-only reference-body searching remains valid, while other selectors and stray forms are refused. */
	@Test
	@DisplayName("Refuses misplaced selectors and non-attribute selectors in referenceHaving")
	void shouldRefuseMisplacedSelectors() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final TextMatchesEntitySpecificationFilterConstraint selector :
				new TextMatchesEntitySpecificationFilterConstraint[]{
				inAssociatedDataAll(), inAssociatedData("shared"), inReference("blocks"),
				inReference("blocks", inEntity())
			}) {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class, () ->
					ids(session, "AllPlaces", referenceHaving("blocks", textMatches("quartz ", selector))));
				assertTrue(error.getMessage().contains("referenceHaving"), error.getMessage());
			}
			assertArrayEquals(new int[]{3, 7, 8}, ids(session, "AllPlaces",
				referenceHaving("blocks", textMatches("quartz ", inAttributeAll()))));
			for (final FilterConstraint selector : new FilterConstraint[]{
				inAttributeAll(), inAssociatedDataAll(), inReference("blocks"), inEntity(),
				inReference("blocks", inEntity(inAssociatedDataAll()))
			}) {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> ids(session, "AllPlaces", selector));
				assertTrue(error.getMessage().contains("textMatches"), error.getMessage());
			}
		});
	}

	/**
	 * Wildcards expand within each processed scope and need a searchable place in at least one of them; named places
	 * and composition must be permitted in all of them.
	 */
	@Test
	@DisplayName("Validates every selector kind in all processed scopes")
	void shouldRespectProcessedScopes() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertArrayEquals(new int[]{1, 7, 9},
				ids(session, "AllPlaces", textMatches("quartz ", inAttributeAll()), BOTH));
			assertArrayEquals(new int[]{2, 7, 8, 9},
				ids(session, "AllPlaces", textMatches("quartz ", inAssociatedDataAll()), BOTH));
			assertArrayEquals(new int[]{3, 7, 8, 9},
				ids(session, "AllPlaces", textMatches("quartz ", inReference("blocks")), BOTH));
			assertArrayEquals(new int[]{3, 4, 5, 6, 7, 8, 9}, ids(session, "AllPlaces",
				textMatches("quartz ", inReference("blocks", inEntity())), BOTH));
			for (final TextMatchesEntitySpecificationFilterConstraint selector :
				new TextMatchesEntitySpecificationFilterConstraint[]{
				inAttribute("liveOnly"), inAssociatedData("liveOnly"), inReference("blocks", inAttribute("liveOnly")),
				inReference("liveBlocks", inEntity()),
				inReference("blocks", inEntity(inAttribute("liveOnly"))),
				inReference("blocks", inEntity(inAssociatedData("liveOnly")))
			}) {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> ids(session, "AllPlaces", textMatches("quartz ", selector), BOTH));
				assertTrue(error.getMessage().contains("ARCHIVED"), error.getMessage());
				assertTrue(error.getMessage().contains(
					selector.toString().contains("liveBlocks") ? "liveBlocks" : "liveOnly"));
			}
		});
	}

	/**
	 * Empty targets and target wildcards preserve composition's skipping of target scopes with no searchable places:
	 * the only target attribute is searchable in LIVE alone, so the ARCHIVED target scope is skipped, not refused.
	 */
	@Test
	@DisplayName("Keeps empty and wildcard target selections equal to composition across sparse searchable scopes")
	void shouldPreserveEmptyTargetsAcrossScopes() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint plain = textMatches("quartz ");
			final FilterConstraint selected = textMatches("quartz ", inReference("blocks", inEntity()));
			assertArrayEquals(new int[]{1, 2}, ids(session, "ScopedOwner", plain, BOTH));
			assertArrayEquals(new int[]{1, 2}, ids(session, "ScopedOwner", selected, BOTH));
			assertArrayEquals(new int[]{1, 2}, ids(session, "ScopedOwner",
				textMatches("quartz ", inReference("blocks", inEntity(inAttributeAll()))), BOTH));
		});
	}

	/**
	 * A wildcard empty in one processed scope is skipped there, as plain search skips a place a scope cannot search,
	 * while a scope whose whole selection is empty is still refused.
	 */
	@Test
	@DisplayName("Accepts own wildcards selecting places in only some processed scopes")
	void shouldAcceptOwnWildcardsEmptyInSomeScopes() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final int[] plain = ids(session, "SparseOwner", textMatches("quartz "), BOTH);
			assertArrayEquals(new int[]{1, 2, 3}, plain);
			assertArrayEquals(plain, ids(session, "SparseOwner",
				textMatches("quartz ", inAttributeAll(), inAssociatedDataAll(), inReference("notes")), BOTH));
			assertArrayEquals(plain, ids(session, "SparseOwner", textMatches("quartz ", inAttributeAll(),
				inAssociatedDataAll(), inReference("notes", inAttributeAll())), BOTH));
			for (final TextMatchesEntitySpecificationFilterConstraint selector :
				new TextMatchesEntitySpecificationFilterConstraint[]{inAttributeAll(), inReference("notes")}) {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> ids(session, "SparseOwner", textMatches("quartz ", selector), BOTH));
				assertTrue(error.getMessage().contains("ARCHIVED"), error.getMessage());
			}
		});
	}

	/** A reference body keeps strict per-scope validation: each processed scope needs a searchable attribute. */
	@Test
	@DisplayName("Refuses a reference body in a scope where the reference has no searchable attribute")
	void shouldRefuseReferenceBodyWithoutSearchableAttributeInScope() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertArrayEquals(new int[]{3},
				ids(session, "SparseOwner", referenceHaving("notes", textMatches("quartz "))));
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "SparseOwner", referenceHaving("notes", textMatches("quartz ")), BOTH));
			assertTrue(error.getMessage().contains("declares no searchable attribute"), error.getMessage());
			assertTrue(error.getMessage().contains("ARCHIVED"), error.getMessage());
			assertFalse(error.getMessage().contains("inAttributeAll"), error.getMessage());
		});
	}

	/** Selected stop-word analyzers alone decide which words remain mandatory. */
	@Test
	@DisplayName("Matches the restricted schema when selected analyzers drop a mandatory stop word")
	void shouldUseSelectedAnalyzersForStopWords() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertArrayEquals(new int[0], ids(session, "SelectedStopFull", textMatches("the quartz ")));
			assertSelectedLikeSchemaCopy(session, "SelectedStopFull", "SelectedStopOnly", "the quartz ",
				new int[]{1, 2}, inAssociatedData("body"));
		});
	}

	/** Homogeneous selected text phases run before splitting, as on the restricted schema copy. */
	@Test
	@DisplayName("Matches the restricted schema when selected text phases join query boundaries")
	void shouldUseSelectedAnalyzersForTextSplitting() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertArrayEquals(new int[0], ids(session, "SelectedSplitFull", textMatches("USB-TYPEC ")));
			assertSelectedLikeSchemaCopy(session, "SelectedSplitFull", "SelectedSplitOnly", "USB-TYPEC ",
				new int[]{1, 2}, inAssociatedData("body"));
		});
	}

	/** Unselected dictionary terms do not consume selected places' bounded prefix expansions. */
	@Test
	@DisplayName("Matches the restricted schema when selection changes the bounded expansion pool")
	void shouldFillExpansionCapFromSelectedPlacesOnly() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final int[] plain = ids(session, "SelectedCapFull", textMatches("quartz"));
			assertEquals(100, plain.length);
			assertTrue(Arrays.binarySearch(plain, 101) < 0);
			assertSelectedLikeSchemaCopy(session, "SelectedCapFull", "SelectedCapOnly", "quartz",
				new int[]{101}, inAssociatedData("body"));
		});
	}

	/** Owner and unselected target analyzers do not keep words mandatory in a target-only search. */
	@Test
	@DisplayName("Matches the restricted owner and target schemas for selected composed places")
	void shouldUseSelectedAnalyzersForComposedTargets() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertArrayEquals(new int[0], ids(session, "SelectedComposedFull", textMatches("the quartz ")));
			assertSelectedLikeSchemaCopy(session, "SelectedComposedFull", "SelectedComposedOnly", "the quartz ",
				new int[]{1, 2}, inReference("blocks", inEntity(inAssociatedData("body"))));
		});
	}

	/** Supplies plain and selected searches on the same unresolved inherited reflection. */
	@Nonnull
	static Stream<FilterConstraint> unboundReflectionQueries() {
		return Stream.of(
			textMatches("quartz "), textMatches("quartz ", inReference("owners")),
			textMatches("quartz ", inReference("owners", inAttribute("localText"))),
			textMatches("quartz ", inReference("owners", inAttributeAll())));
	}

	/** Supplies reference-body forms whose inherited indexing metadata is unavailable already at HEAD. */
	@Nonnull
	static Stream<FilterConstraint> unboundReferenceBodyQueries() {
		return Stream.of(referenceHaving("owners", textMatches("quartz ")),
			referenceHaving("owners", textMatches("quartz ", inAttribute("localText"))),
			referenceHaving("owners", textMatches("quartz ", inAttributeAll())));
	}

	/** Available local attributes can be planned without reopening unavailable inherited attributes. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("unboundReflectionQueries")
	@DisplayName("Plans local text on an unresolved inherited reflected reference")
	void shouldPlanLocalAttributesOfUnboundReflection(@Nonnull FilterConstraint constraint) {
		withUnboundReflection(session -> {
			final QueryPlanningContext context = context(session, "UnboundSelector", constraint);
			assertDoesNotThrow(
				() -> filterBy(constraint).accept(FilterByVisitor.createConstraintCheckVisitor(context)));
			assertArrayEquals(new int[0], ids(session, "UnboundSelector", constraint));
		});
	}

	/** An inherited index must be available before a reference body can reach fulltext field selection. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("unboundReferenceBodyQueries")
	@DisplayName("Retains the pre-existing refusal of unresolved inherited reference-body indexing")
	void shouldKeepPreExistingUnboundReferenceBodyRefusal(@Nonnull FilterConstraint constraint) {
		withUnboundReflection(session -> {
			final QueryPlanningContext context = context(session, "UnboundSelector", constraint);
			final EvitaInvalidUsageException planning = assertThrows(EvitaInvalidUsageException.class,
				() -> filterBy(constraint).accept(FilterByVisitor.createConstraintCheckVisitor(context)));
			assertTrue(planning.getMessage().contains("indexed property"), planning.getMessage());
			assertTrue(planning.getMessage().contains("not available"), planning.getMessage());
			final EvitaInvalidUsageException query = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "UnboundSelector", constraint));
			assertTrue(query.getMessage().contains("indexed property"), query.getMessage());
		});
	}

	/** Unknown and non-searchable local names keep their reference-aware diagnostics while inheritance is pending. */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"missing", "hidden"})
	@DisplayName("Names invalid reflected reference attributes before consulting indexes")
	void shouldRejectInvalidAttributesOfUnboundReflection(@Nonnull String name) {
		withUnboundReflection(session -> {
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, "UnboundSelector",
					textMatches("quartz ", inReference("owners", inAttribute(name)))));
			assertTrue(error.getMessage().contains("owners"), error.getMessage());
			assertTrue(error.getMessage().contains(name), error.getMessage());
			if (name.equals("missing")) assertInstanceOf(AttributeNotFoundException.class, error);
		});
	}

	/** Compares actual membership, public order and every composite score bit with the restricted schema oracle. */
	private void assertSelectedLikeSchemaCopy(
		@Nonnull EvitaSessionContract session, @Nonnull String full, @Nonnull String restricted,
		@Nonnull String text, @Nonnull int[] expected, @Nonnull TextMatchesEntitySpecificationFilterConstraint... in
	) {
		final FilterConstraint plain = textMatches(text);
		final FilterConstraint selected = textMatches(text, in);
		assertArrayEquals(expected, ids(session, restricted, plain));
		assertArrayEquals(expected, ids(session, full, selected));
		assertArrayEquals(rankedIds(session, restricted, plain), rankedIds(session, full, selected));
		assertArrayEquals(scores(session, restricted, plain, expected), scores(session, full, selected, expected));
		assertFalse(Arrays.equals(expected, ids(session, full, plain)),
			"Selection must visibly differ from plain search");
	}

	/** Rolls back an intentionally intermediate schema after observing both available and guarded attribute views. */
	private void withUnboundReflection(@Nonnull Consumer<EvitaSessionContract> assertions) {
		final boolean[] completed = {false};
		assertThrows(RollbackException.class, () -> this.evita.updateCatalog(TEST_CATALOG, session -> {
			try {
				session.defineEntitySchema("UnboundSelector").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("own", String.class, a -> a.localized().searchable())
					.withReflectedReferenceToEntity("owners", "FutureOwner", "blocks", r -> r.withAttributesInherited()
						.withAttribute("localText", String.class, a -> a.localized().nullable().searchable())
						.withAttribute("hidden", String.class, a -> a.localized().nullable())).updateVia(session);
				final ReferenceSchemaContract reference =
					session.getEntitySchemaOrThrow("UnboundSelector").getReferenceOrThrowException("owners");
				// These are the guarded getters HEAD's reference-body resolver calls before constructing fields.
				assertThrows(EvitaInvalidUsageException.class, reference::getAttributes);
				assertThrows(EvitaInvalidUsageException.class, () -> reference.getAttribute("localText"));
				assertThrows(EvitaInvalidUsageException.class, () -> reference.isIndexedInScope(Scope.LIVE));
				assertions.accept(session);
				completed[0] = true;
			} finally {
				session.setRollbackOnly();
			}
		}));
		assertTrue(completed[0], "All assertions must finish before the expected rollback");
	}

	/** Compares sorted owner ids for a selector union against independently known fixture membership. */
	private void assertIds(
		@Nonnull EvitaSessionContract session, @Nonnull int[] expected,
		@Nonnull TextMatchesEntitySpecificationFilterConstraint... in
	) {
		assertArrayEquals(expected, ids(session, "AllPlaces", textMatches("quartz ", in)), Arrays.toString(in));
	}

	/** Reads membership independently of ranking. */
	@Nonnull
	private int[] ids(
		@Nonnull EvitaSessionContract session, @Nonnull String type, @Nonnull FilterConstraint constraint,
		@Nonnull Scope... scopes
	) {
		return session.query(query(collection(type), filterBy(entityLocaleEquals(Locale.ENGLISH),
			scope(scopes.length == 0 ? new Scope[]{Scope.LIVE} : scopes), constraint), require(page(1, 200))),
			EntityReference.class).getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray();
	}

	/** Reads the actual public relevance ordering. */
	@Nonnull
	private int[] rankedIds(@Nonnull EvitaSessionContract session, @Nonnull FilterConstraint constraint) {
		return rankedIds(session, "AllPlaces", constraint);
	}

	/** Reads public relevance ordering on either a full schema or its restricted schema copy. */
	@Nonnull
	private int[] rankedIds(
		@Nonnull EvitaSessionContract session, @Nonnull String type, @Nonnull FilterConstraint constraint
	) {
		return session.query(query(collection(type), filterBy(entityLocaleEquals(Locale.ENGLISH), constraint),
			orderBy(relevanceText()), require(page(1, 200))), EntityReference.class).getRecordData().stream()
			.mapToInt(EntityReference::getPrimaryKey).toArray();
	}

	/** Reads the scorer's actual composite values instead of duplicating scoring arithmetic. */
	@Nonnull
	private long[] scores(
		@Nonnull EvitaSessionContract session, @Nonnull String type, @Nonnull FilterConstraint constraint,
		@Nonnull int[] candidates
	) {
		return plannedFormula(session, type, constraint).getFulltextScores(candidates, Map.of()).composites();
	}
	/** Extracts actual scores for arbitrary selector forms through the production planner. */
	@Nonnull
	private FulltextFormula plannedFormula(
		@Nonnull EvitaSessionContract session, @Nonnull String type, @Nonnull FilterConstraint constraint,
		@Nonnull Scope... scopes
	) {
		final QueryPlanningContext context = context(session, type, constraint, scopes);
		final QueryPlan plan = QueryPlanner.planQuery(context);
		final Collection<FulltextFormula> formulas = FormulaFinder.find(
			plan.getFilter(), FulltextFormula.class,
			FormulaFinder.LookUp.SHALLOW);
		assertEquals(1, formulas.size(), "Composition belongs to the owner's single text source");
		return formulas.iterator().next();
	}

	/** Builds the production context with the supplied fulltext selector forms. */
	@Nonnull
	private QueryPlanningContext context(
		@Nonnull EvitaSessionContract session, @Nonnull String type, @Nonnull FilterConstraint constraint,
		@Nonnull Scope... scopes
	) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntityOrThrowException(type);
		final EvitaSessionContract actual = Proxy.isProxyClass(session.getClass()) ?
			field(Proxy.getInvocationHandler(session), "evitaSession") : session;
		final Query request = query(collection(type), filterBy(
			scope(scopes.length == 0 ? new Scope[]{Scope.LIVE} : scopes),
			entityLocaleEquals(Locale.ENGLISH), constraint));
		return new QueryPlanningContext(catalog, collection, actual,
			new EvitaRequest(request, OffsetDateTime.now(), EntityReference.class, null),
			QueryTelemetry.root(QueryPhase.OVERALL),
			field(collection, "indexes"), field(collection, "indexesByPrimaryKey"),
			NoCacheSupervisor.INSTANCE);
	}

	/** Inspects planner data unavailable through the public query response. */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static <T> T field(@Nonnull Object instance, @Nonnull String name) {
		try {
			final Field field = instance.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(instance);
		} catch (final ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}

}
