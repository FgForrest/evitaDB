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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.ReferenceSearchMode.COMPOSITION;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.removeCharacters;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** Real, shared products and localized content blocks: schema-only composition never writes owner text indexes. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(QUERY)
@Tag(REFERENCE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Query-time composition of referenced fulltext places")
class FulltextCompositionFunctionalTest implements EvitaTestSupport {
	private static final String PRODUCT = "Product";
	private static final String BLOCK = "ContentBlock";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final Scope[] BOTH = {Scope.LIVE, Scope.ARCHIVED};
	private final Map<Integer, Product> products = CollectionUtils.createLinkedHashMap(16);
	private final Map<Integer, Block> blocks = CollectionUtils.createLinkedHashMap(16);
	private TestPaths paths;
	private Evita evita;

	/** Builds one realistic catalog shared by all cases; mutating cases roll back to this fixture. */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("FulltextCompositionFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BLOCK).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH, CZECH)
				.withAttribute("title", String.class, a -> a.localized().nullable().searchable())
				.withAssociatedData("content", String[].class,
					a -> a.localized().nullable().searchableInScope(BOTH).lengthPivot(100))
				.withReferenceTo("tags", "Tag", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringInScope(BOTH).withAttribute("note", String.class,
						a -> a.localized().nullable().searchableInScope(BOTH).searchWeight(2f)))
				.updateVia(session);
			for (final String type : new String[]{PRODUCT, "Plain", "EmptyOwner"}) {
				final EntitySchemaBuilder schema = session.defineEntitySchema(type).withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, CZECH)
					.withAttribute("name", String.class, a -> {
						a.localized().nullable().lengthPivot(100);
						if (!type.equals("EmptyOwner")) {
							a.searchableInScope(BOTH);
						}
					})
					.withReferenceToEntity("blocks", BLOCK, Cardinality.ZERO_OR_MORE, r -> {
						r.indexedForFilteringInScope(BOTH);
						if (!type.equals("EmptyOwner")) {
							r.withAttribute("note", String.class,
								a -> a.localized().nullable().searchableInScope(BOTH));
						}
						if (!type.equals("Plain")) {
							r.searchableThroughInScope(COMPOSITION, 1f, BOTH);
						}
					});
				if (!type.equals("EmptyOwner")) {
					schema.withAssociatedData("description", String[].class,
						a -> a.localized().nullable().searchableInScope(BOTH).lengthPivot(100));
				}
				schema.updateVia(session);
			}
			session.getEntitySchemaOrThrow(BLOCK).openForWrite()
				.withReflectedReferenceToEntity("products", PRODUCT, "blocks", r -> r.indexedForFilteringInScope(BOTH))
				.updateVia(session);
			addBlock(session, 101, "tailored", Scope.LIVE, "jacket");
			addBlock(session, 102, "seasonal", Scope.LIVE, "winter");
			addBlock(session, 103, "combined", Scope.LIVE, "jacket", "winter");
			addBlock(session, 104, "archivedtitle", Scope.ARCHIVED, "wool");
			addBlock(session, 105, "hidden", Scope.ARCHIVED, "archive");
			addBlock(session, 106, null, Scope.LIVE, "quartz");
			addBlock(session, 108, null, Scope.LIVE, "jackez");
			addBlock(session, 109, null, Scope.LIVE, "silver");
			addBlock(session, 110, null, Scope.LIVE, "violet");
			addBlock(session, 111, null, Scope.LIVE, "silver", "violet");
			addProduct(session, 1, "leather", null, Scope.LIVE, 101, 102);
			addProduct(session, 2, "coat", null, Scope.LIVE, 101);
			addProduct(session, 3, "leather", null, Scope.LIVE, 103);
			addProduct(session, 4, "leather", new String[]{"jacket", "winter"}, Scope.LIVE);
			addProduct(session, 5, "linen", null, Scope.LIVE, 104);
			addProduct(session, 6, "linen", null, Scope.ARCHIVED, 101);
			addProduct(session, 7, "jackez", null, Scope.LIVE);
			addProduct(session, 8, "plain", null, Scope.LIVE, 101);
			addProduct(session, 9, "jacket", null, Scope.LIVE);
			addProduct(session, 10, "plain", null, Scope.LIVE, 108);
			addProduct(session, 11, "quartz", null, Scope.LIVE);
			addProduct(session, 12, "plain", null, Scope.LIVE, 106);
			addProduct(session, 13, "plain", null, Scope.LIVE, 109, 110);
			addProduct(session, 14, "plain", null, Scope.LIVE, 111);
			addProduct(session, 15, "plain", null, Scope.LIVE, 105);
			addProduct(session, 16, "plain", null, Scope.LIVE);

			for (final String type : new String[]{"Plain", "EmptyOwner"}) {
				session.upsertEntity(session.createNewEntity(type, 1).setAttribute("name", Locale.ENGLISH, "leather")
					.setReference("blocks", 101));
			}
			final String type = "SelectedAttributes";
			session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("a", String.class, a -> a.localized().nullable().searchable())
				.withAttribute("b", String.class, a -> a.localized().nullable().searchable())
				.withReferenceToEntity("blocks", BLOCK, Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering().searchableThrough(COMPOSITION))
				.updateVia(session);
			session.upsertEntity(session.createNewEntity(type, 1).setAttribute("a", Locale.ENGLISH, "jacket"));
			session.upsertEntity(session.createNewEntity(type, 2).setAttribute("b", Locale.ENGLISH, "jacket"));
			session.upsertEntity(session.createNewEntity(type, 3)
				.setAttribute("a", Locale.ENGLISH, "jacket").setAttribute("b", Locale.ENGLISH, "jacket"));
			session.upsertEntity(session.createNewEntity(type, 4)
				.setAttribute("a", Locale.ENGLISH, "plain").setReference("blocks", 101));
			defineOneLevelFixture(session);
			definePlaceIdentityFixture(session);
			session.goLiveAndClose();
		});
	}

	/** Releases persistent storage after every consumer has finished. */
	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** The engine of the shared fixture; nested cases reach it here because a restart replaces it. */
	private Evita evita() {
		return this.evita;
	}

	/** Closes the engine and opens a new one over the same storage, as a server restart does. */
	private void reopen() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/** Which owners match, through which surfaces, scopes and locales. */
	@Nested
	@Order(1)
	@DisplayName("Membership and query surfaces")
	class Membership {
		/** Computes every product's membership independently of the engine, including negation and disjunction. */
		@Test
		@DisplayName("Intersects words at the owner and agrees with a brute-force oracle under NOT and OR")
		void shouldMatchOracleAcrossPlacesAndBooleanContainers() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				for (final Scope[] scopes : new Scope[][]{{Scope.LIVE}, BOTH}) {
					for (final String text : new String[]{"leather winter", "winter jacket", "quartz", "silver violet",
						"tailored", "wool", "archive", "hidden", "nonexistent"}) {
						final Set<Integer> expected = oracle(text, scopes);
						assertArrayEquals(sorted(expected), ids(session, PRODUCT, textMatches(text + " "), scopes));
						final Set<Integer> complement = eligible(scopes);
						complement.removeAll(expected);
						assertArrayEquals(sorted(complement),
							ids(session, PRODUCT, not(textMatches(text + " ")), scopes));
						expected.add(16);
						assertArrayEquals(sorted(expected), ids(session, PRODUCT,
							or(textMatches(text + " "), entityPrimaryKeyInSet(16)), scopes));
					}
				}
			});
		}

		/**
		 * The automatic union is limited to plain entity text; existing nested and selector surfaces retain meaning.
		 */
		@Test
		@DisplayName("Keeps selected searchable attributes and direct and nested reference text distinct")
		void shouldRespectQuerySurfaces() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				assertArrayEquals(new int[]{1, 3, 4},
					ids(session, PRODUCT, textMatches("leather jacket "), Scope.LIVE));
				assertArrayEquals(new int[]{1, 3, 4}, ids(session, PRODUCT, textMatches("winter jacket "), Scope.LIVE));
				assertArrayEquals(new int[0], ids(session, "Plain", textMatches("leather jacket "), Scope.LIVE));
				assertArrayEquals(new int[0], ids(session, PRODUCT, textMatches("leather winter ", inAttribute("name")),
					Scope.LIVE));
				assertArrayEquals(new int[]{7, 9}, ids(session, PRODUCT, textMatches("jacket ", inAttribute("name")),
					Scope.LIVE));
				assertArrayEquals(new int[0], ids(session, PRODUCT,
					referenceHaving("blocks", textMatches("winter ")), Scope.LIVE));
				assertArrayEquals(new int[]{1, 2, 3, 8, 10}, ids(session, PRODUCT,
					referenceHaving("blocks", entityHaving(textMatches("jacket "))), Scope.LIVE));
				assertArrayEquals(new int[]{1}, ids(session, "EmptyOwner", textMatches("jacket "), Scope.LIVE));
			});
		}

		/** Both attributes contribute independently; explicit selection also excludes composed-only owners. */
		@Test
		@DisplayName("Unions several searchable attributes with identical scores and order and disables composition")
		void shouldUnionSelectedAttributesWithoutComposingTargets() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				final String type = "SelectedAttributes";

				assertArrayEquals(new int[]{1, 2, 3, 4}, ids(session, type, textMatches("jacket "), Scope.LIVE));
				assertArrayEquals(new int[]{1, 3},
					ids(session, type, textMatches("jacket ", inAttribute("a")), Scope.LIVE));
				assertArrayEquals(new int[]{2, 3},
					ids(session, type, textMatches("jacket ", inAttribute("b")), Scope.LIVE));
				final int[] candidates = {1, 2, 3};
				final FilterConstraint separate = textMatches("jacket ", inAttribute("a"), inAttribute("b"));
				final long[] expectedScores = plannedFormula(session, type, separate)
					.getFulltextScores(candidates).composites();
				final int[] expectedOrder = rankedIds(session, type, separate);
				for (final FilterConstraint selector : new FilterConstraint[]{
					textMatches("jacket ", inAttribute("a", "b")),
					textMatches("jacket ", inAttribute("a", "b", "a", "b")),
					textMatches("jacket ", inAttribute("a", "b"), inAttribute("b"), inAttribute("a", "b"))
				}) {
					assertArrayEquals(candidates, ids(session, type, selector, Scope.LIVE));
					assertArrayEquals(expectedOrder, rankedIds(session, type, selector));
					assertArrayEquals(expectedScores, plannedFormula(session, type, selector)
						.getFulltextScores(candidates).composites());
				}
				assertTrue(expectedScores[1] > 0, "The second attribute must contribute a score");
			});
		}

		/** Duplicate selectors must share the expansion work of a single selected attribute. */
		@Test
		@DisplayName("Plans duplicate entity attribute names as one fulltext expansion")
		void shouldDeduplicateSelectedAttributeFormulas() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				final Map<Locale, FulltextIndex> indexes = field(global("SelectedAttributes", Scope.LIVE),
					"fulltextIndexes");
				final FulltextIndex original = indexes.get(Locale.ENGLISH);
				final FulltextIndex observed = spy(original);
				final AtomicInteger lookups = new AtomicInteger();
				// The spy only observes work: exact-term collection itself also deduplicates field contributions.
				doAnswer(invocation -> {
					lookups.incrementAndGet();
					return invocation.callRealMethod();
				}).when(observed).termLookup(anyInt());
				indexes.put(Locale.ENGLISH, observed);
				try {
					final FulltextFormula single = plannedFormula(session, "SelectedAttributes",
						textMatches("jacket ", inAttribute("a")));
					assertEquals(1, single.getTokens().length);
					assertEquals(1, single.getTokens()[0].length, "The selected attribute must expand a real term");
					final int expectedLookups = lookups.get();
					assertTrue(expectedLookups > 0, "The real planner must read the selected attribute");
					for (final FilterConstraint duplicate : new FilterConstraint[]{
						textMatches("jacket ", inAttribute("a", "a")),
						textMatches("jacket ", inAttribute("a"), inAttribute("a"))
					}) {
						lookups.set(0);
						final FulltextFormula formula = plannedFormula(session, "SelectedAttributes", duplicate);
						assertEquals(single.getTokens().length, formula.getTokens().length);
						assertEquals(single.getTokens()[0].length, formula.getTokens()[0].length);
						assertEquals(expectedLookups, lookups.get(), "Repeated names must not expand a field twice");
					}
				} finally {
					indexes.put(Locale.ENGLISH, original);
				}
			});
		}

		/** Target scope and owner scope are separate axes; localized target text never leaks across locales. */
		@Test
		@DisplayName("Uses query locale and every processed target scope with owner partitions in their own scope")
		void shouldRespectLocalesAndScopes() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				assertArrayEquals(new int[0], ids(session, PRODUCT, textMatches("wool "), Scope.LIVE));
				assertArrayEquals(new int[]{5}, ids(session, PRODUCT, textMatches("wool "), BOTH));
				assertArrayEquals(new int[]{1, 2, 3, 4, 6, 7, 8, 9, 10},
					ids(session, PRODUCT, textMatches("jacket "), BOTH));
				assertArrayEquals(new int[0], ids(session, PRODUCT, textMatches("hidden "), BOTH));
				assertArrayEquals(new int[0], ids(session, PRODUCT, textMatches("bunda "), Scope.LIVE));
				assertArrayEquals(new int[]{1, 2, 8}, session.query(query(collection(PRODUCT),
					filterBy(entityLocaleEquals(CZECH), textMatches("bunda ")), require(page(1, 100))),
					EntityReference.class).getRecordData().stream().mapToInt(EntityReference::getPrimaryKey)
					.sorted().toArray());
			});
		}

		/** No composition recursively traverses its target's declarations, including a target in its own collection. */
		@Test
		@DisplayName("Follows exactly one level for a chain, mutual declarations and self references")
		void shouldComposeExactlyOneLevel() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				assertArrayEquals(new int[0], ids(session, "A", textMatches("terminal "), Scope.LIVE));
				assertArrayEquals(new int[]{1}, ids(session, "B", textMatches("terminal "), Scope.LIVE));
				assertArrayEquals(new int[]{1}, ids(session, "A", referenceHaving("next",
					entityHaving(textMatches("terminal "))), Scope.LIVE));
				assertArrayEquals(new int[]{2, 3}, ids(session, "Node", textMatches("terminal "), Scope.LIVE));
				assertArrayEquals(new int[]{1, 2, 8}, ids(session, PRODUCT,
					textMatches("craftsmanship "), Scope.LIVE));
				assertTrue(FulltextScoringPolicy.strength(scores(session, "craftsmanship ", 1)[0]) > 0);
			});
		}

		/** Missing collections and empty target schemas count as declared capability, yet contribute no term. */
		@Test
		@DisplayName("Allows declarations without own places, missing target collections or searchable target places")
		void shouldAllowEmptyTargets() {
			rollback(session -> {
				session.defineEntitySchema("Unavailable").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("label", String.class, a -> a.localized())
					.withReferenceToEntity("missing", "Future", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering().searchableThrough(COMPOSITION)).updateVia(session);
				session.upsertEntity(session.createNewEntity("Unavailable", 1)
					.setAttribute("label", Locale.ENGLISH, "plain").setReference("missing", 1));
				assertArrayEquals(new int[0], ids(session, "Unavailable", textMatches("winter "), Scope.LIVE));
				session.defineEntitySchema("Future").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("label", String.class, a -> a.localized()).updateVia(session);
				session.upsertEntity(session.createNewEntity("Future", 1)
					.setAttribute("label", Locale.ENGLISH, "winter"));
				assertArrayEquals(new int[0], ids(session, "Unavailable", textMatches("winter "), Scope.LIVE));
			});
		}

		/** The declaration of an archived owner is independent of the scopes containing its target. */
		@Test
		@DisplayName("Composes only references declared in the owner's current scope")
		void shouldUseOwnerScopeDeclaration() {
			rollback(session -> {
				assertArrayEquals(new int[]{1, 2, 6, 8}, ids(session, PRODUCT, textMatches("tailored "), BOTH),
					"The archived owner 6 reaches the live block 101 while its own scope declares composition");
				session.getEntitySchemaOrThrow(PRODUCT).openForWrite().withReferenceToEntity("blocks", BLOCK,
					Cardinality.ZERO_OR_MORE, r -> r.nonSearchableThroughInScope(Scope.ARCHIVED)).updateVia(session);
				// the archived owner stops matching once its own scope no longer declares composition;
				// live owners keep matching
				assertArrayEquals(new int[]{1, 2, 8}, ids(session, PRODUCT, textMatches("tailored "), BOTH));
			});
		}
	}

	/** How composed places weigh and score against own places. */
	@Nested
	@Order(2)
	@DisplayName("Ranking and weights")
	class Ranking {
		/** An exact word always beats a typo through either route, regardless of the effective place weight. */
		@Test
		@DisplayName("Ranks quality ahead of weight for own and composed hits")
		void shouldRankQualityBeforeWeight() {
			rollback(session -> {
				weight(session, 0f);
				assertOrder(session, "jacket ", new int[]{7, 8}, 8, 7);
				weight(session, 100f);
				assertOrder(session, "jacket ", new int[]{9, 10}, 9, 10);
			});
		}

		/**
		 * Weight-only changes affect scores immediately and leave both field generations and posting data untouched.
		 */
		@Test
		@DisplayName("Applies weights at query time, including zero, with no index generation or rebuild")
		void shouldRankFlatPlacesAndApplyLiveWeights() {
			rollback(session -> {
				final GlobalEntityIndex global = global(PRODUCT, Scope.LIVE);
				final int fields = global.getFulltextIndex(Locale.ENGLISH).getFieldCount();
				final int nameId = global.getFulltextIndex(Locale.ENGLISH)
					.getFieldId(FulltextFieldKey.attribute("name"));
				final long indexId = global.getFulltextIndex(Locale.ENGLISH).getId();
				final long[] equal = scores(session, "quartz ", 11, 12);
				assertEquals(equal[0], equal[1], "A target weight of one acts like an own place of weight one");
				final long[] flat = scores(session, "silver violet ", 13, 14);
				assertEquals(flat[0], flat[1], "Targets are items of one flat content place");
				weight(session, 0.5f);
				assertOrder(session, "quartz ", new int[]{11, 12}, 11, 12);
				weight(session, 2f);
				assertOrder(session, "quartz ", new int[]{11, 12}, 12, 11);
				weight(session, 0f);
				final long[] zero = scores(session, "quartz ", 11, 12);
				assertEquals(0, FulltextScoringPolicy.strength(zero[1]));
				assertArrayEquals(new int[]{11, 12}, ids(session, PRODUCT, textMatches("quartz "), Scope.LIVE));
				final GlobalEntityIndex current = global(PRODUCT, Scope.LIVE);
				assertEquals(indexId, current.getFulltextIndex(Locale.ENGLISH).getId());
				assertEquals(fields, current.getFulltextIndex(Locale.ENGLISH).getFieldCount());
				assertEquals(nameId, current.getFulltextIndex(Locale.ENGLISH)
					.getFieldId(FulltextFieldKey.attribute("name")));
			});
		}

		/** Composed places retain their target schema weight, including reference-attribute places and overflow. */
		@Test
		@DisplayName("Multiplies each target place's own weight and clamps finite overflow")
		void shouldKeepTargetPlaceWeights() {
			rollback(session -> {
				final FulltextFormula before = plannedFormula(session, PRODUCT, "craftsmanship ");
				assertEquals(2f, before.getTokens()[0][0].weight());
				session.getEntitySchemaOrThrow(BLOCK).openForWrite().withReferenceTo("tags", "Tag",
					Cardinality.ZERO_OR_MORE, r -> r.withAttribute("note", String.class,
						a -> a.searchWeight(0.25f))).updateVia(session);
				assertEquals(0.25f, plannedFormula(session, PRODUCT, "craftsmanship ").getTokens()[0][0].weight());
				weight(session, Float.MAX_VALUE);
				session.getEntitySchemaOrThrow(BLOCK).openForWrite().withReferenceTo("tags", "Tag",
					Cardinality.ZERO_OR_MORE, r -> r.withAttribute("note", String.class,
						a -> a.searchWeight(Float.MAX_VALUE))).updateVia(session);
				assertEquals(Float.MAX_VALUE,
					plannedFormula(session, PRODUCT, "craftsmanship ").getTokens()[0][0].weight());
			});
		}

		/** A nested entityHaving remains a distinct default-half-weight source beside automatic composition. */
		@Test
		@DisplayName("Keeps nested entityHaving as its separate score source beside automatic composition")
		void shouldKeepNestedScoreSource() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				final int[] order = session.query(query(collection(PRODUCT),
					filterBy(entityLocaleEquals(Locale.ENGLISH),
					entityPrimaryKeyInSet(11, 12), or(textMatches("quartz "), referenceHaving("blocks",
						entityHaving(textMatches("quartz "))))), orderBy(relevanceText()), require(page(1, 100))),
					EntityReference.class).getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray();
				assertArrayEquals(new int[]{12, 11}, order);
			});
		}

		/** Equal physical ids from two collections must still score as two independent places. */
		@Test
		@DisplayName("Keeps composed and own places distinct even when physical field ids collide")
		void shouldKeepComposedPlaceSeparateFromOwnPhysicalField() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				assertEquals(global("PlaceOwner", Scope.LIVE).getFulltextIndex(Locale.ENGLISH)
					.getFieldId(FulltextFieldKey.attribute("text")),
					global("PlaceTarget", Scope.LIVE).getFulltextIndex(Locale.ENGLISH)
						.getFieldId(FulltextFieldKey.attribute("text")));
				final FulltextFormula formula = plannedFormula(session, "PlaceOwner", "silver violet ");
				final long[] scores = formula.getFulltextScores(new int[]{1, 2}).composites();
				assertTrue(FulltextScoringPolicy.strength(scores[0]) < FulltextScoringPolicy.strength(scores[1]),
					"Two places use best + t * other; one place sums both words: " + Arrays.toString(scores));
			});
		}

		/** The same logical target field stays flat even when scope indexes assign different physical ids. */
		@Test
		@DisplayName("Shares one composed place across live and archived target scopes")
		void shouldKeepOneComposedPlaceAcrossTargetScopes() {
			evita().queryCatalog(TEST_CATALOG, session -> {
				assertNotEquals(global("PlaceTarget", Scope.LIVE).getFulltextIndex(Locale.ENGLISH)
					.getFieldId(FulltextFieldKey.attribute("text")),
					global("PlaceTarget", Scope.ARCHIVED).getFulltextIndex(Locale.ENGLISH)
						.getFieldId(FulltextFieldKey.attribute("text")));
				final long[] scores = plannedFormula(session, "PlaceOwner", "silver violet ", BOTH)
					.getFulltextScores(new int[]{2, 3}).composites();
				assertEquals(scores[0], scores[1], "A target field is one place across both target scopes");
			});
		}
	}

	/** Target edits reach owner queries without any owner write. */
	@Nested
	@Order(3)
	@DisplayName("Live target data")
	class LiveData {
		/** Shared targets, physical deletion and reference edits are observed without rewriting any other owner. */
		@Test
		@DisplayName("Observes shared block edits, target creation/deletion and reference changes immediately")
		void shouldFollowLiveDataWithoutOwnerWrites() {
			rollback(session -> {
				final FulltextFormula before = plannedFormula(session, PRODUCT, "jacket ");
				session.upsertEntity(session.getEntity(BLOCK, 101, entityFetchAllContent()).orElseThrow().openForWrite()
					.mutateAssociatedData(new UpsertAssociatedDataMutation("content", Locale.ENGLISH,
						new String[]{"velvet"})));
				assertArrayEquals(new int[]{1, 2, 8}, ids(session, PRODUCT, textMatches("velvet "), Scope.LIVE));
				assertArrayEquals(new int[]{3, 4, 7, 9, 10},
					ids(session, PRODUCT, textMatches("jacket "), Scope.LIVE));
				final FulltextFormula after = plannedFormula(session, PRODUCT, "jacket ");
				assertNotEquals(before.getHash(), after.getHash());
				assertFalse(Arrays.equals(before.gatherTransactionalIds(), after.gatherTransactionalIds()));
				session.upsertEntity(session.getEntity(PRODUCT, 16, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setReference("blocks", 999));
				assertArrayEquals(new int[0], ids(session, PRODUCT, textMatches("cashmere "), Scope.LIVE));
				session.upsertEntity(session.createNewEntity(BLOCK, 999)
					.setAssociatedData("content", Locale.ENGLISH, new String[]{"cashmere"}));
				assertArrayEquals(new int[]{16}, ids(session, PRODUCT, textMatches("cashmere "), Scope.LIVE));
				final FulltextFormula referenced = plannedFormula(session, PRODUCT, "cashmere ");
				session.upsertEntity(session.getEntity(PRODUCT, 2, entityFetchAllContent()).orElseThrow().openForWrite()
					.setReference("blocks", 999));
				final FulltextFormula added = plannedFormula(session, PRODUCT, "cashmere ");
				assertNotEquals(referenced.getHash(), added.getHash());
				assertFalse(Arrays.equals(referenced.gatherTransactionalIds(), added.gatherTransactionalIds()));
				assertArrayEquals(new int[]{2, 16}, ids(session, PRODUCT, textMatches("cashmere "), Scope.LIVE));
				session.upsertEntity(session.getEntity(PRODUCT, 16, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.removeReference("blocks", 999));
				final FulltextFormula removed = plannedFormula(session, PRODUCT, "cashmere ");
				assertNotEquals(added.getHash(), removed.getHash());
				assertFalse(Arrays.equals(added.gatherTransactionalIds(), removed.gatherTransactionalIds()));
				assertArrayEquals(new int[]{2}, ids(session, PRODUCT, textMatches("cashmere "), Scope.LIVE));
				session.deleteEntity(BLOCK, 999);
				assertArrayEquals(new int[0], ids(session, PRODUCT, textMatches("cashmere "), Scope.LIVE));
			});
			evita().queryCatalog(TEST_CATALOG, session -> {
				assertArrayEquals(new int[0], ids(session, PRODUCT, textMatches("velvet "), Scope.LIVE));
			});
		}

		/**
		 * The target's committed version is read afresh by a separate owner query; owner entity versions stay fixed.
		 */
		@Test
		@DisplayName("Observes committed shared-block edits in a new session without an owner write")
		void shouldObserveCommittedTargetEdits() {
			final int[] versions = evita().queryCatalog(TEST_CATALOG, session -> new int[]{
				session.getEntity(PRODUCT, 1).orElseThrow().version(),
				session.getEntity(PRODUCT, 2).orElseThrow().version(),
				session.getEntity(PRODUCT, 8).orElseThrow().version()
			});
			try {
				evita().updateCatalog(TEST_CATALOG, session -> {
					session.upsertEntity(session.getEntity(BLOCK, 101, entityFetchAllContent()).orElseThrow()
						.openForWrite()
						.mutateAssociatedData(new UpsertAssociatedDataMutation("content", Locale.ENGLISH,
							new String[]{"suede"})));
				});
				evita().queryCatalog(TEST_CATALOG, session -> {
					assertArrayEquals(new int[]{1, 2, 8}, ids(session, PRODUCT, textMatches("suede "), Scope.LIVE));
					assertArrayEquals(versions, new int[]{session.getEntity(PRODUCT, 1).orElseThrow().version(),
						session.getEntity(PRODUCT, 2).orElseThrow().version(),
						session.getEntity(PRODUCT, 8).orElseThrow().version()});
				});
			} finally {
				evita().updateCatalog(TEST_CATALOG, session -> {
					session.upsertEntity(session.getEntity(BLOCK, 101, entityFetchAllContent()).orElseThrow()
						.openForWrite()
						.mutateAssociatedData(new UpsertAssociatedDataMutation("content", Locale.ENGLISH,
							new String[]{"jacket"})));
				});
			}
		}

		/** Real shared objects exercise the normal query path and compare ranked and filter-only membership. */
		@Test
		@DisplayName("Keeps common shared-block membership deterministic with and without relevance sorting")
		void shouldSearchManySharedBlocks() {
			rollback(session -> {
				final String[] words = new String[255];
				for (int word = 0; word < words.length - 1; word++) {
					words[word] = "lex" + (char) ('a' + word / 16) + (char) ('a' + word % 16) + "qq";
				}
				words[254] = "mahogany";
				for (int block = 1_000; block < 1_080; block++) {
					session.upsertEntity(session.createNewEntity(BLOCK, block)
						.setAttribute("title", Locale.ENGLISH, String.join(" ", words))
						.setAssociatedData("content", Locale.ENGLISH, words));
				}
				final int[] candidates = IntStream.range(1_000, 1_100).toArray();
				for (final int owner : candidates) {
					final EntityBuilder builder = session.createNewEntity(PRODUCT, owner)
						.setAttribute("name", Locale.ENGLISH, "display");
					for (int block = 1_000; block < 1_080; block++) {
						builder.setReference("blocks", block);
					}
					session.upsertEntity(builder);
				}
				final String[] typoWords = words.clone();
				typoWords[254] = "mahoganz";
				session.upsertEntity(session.createNewEntity(BLOCK, 2_000)
					.setAttribute("title", Locale.ENGLISH, String.join(" ", typoWords))
					.setAssociatedData("content", Locale.ENGLISH, typoWords));
				session.upsertEntity(session.createNewEntity(PRODUCT, 2_000)
					.setAttribute("name", Locale.ENGLISH, "display").setReference("blocks", 2_000));
				// The typo control matches under the ordinary allowance, but not after reserving 4m exact owner edges.
				assertArrayEquals(new int[]{2_000}, ids(session, PRODUCT,
					and(entityPrimaryKeyInSet(2_000), textMatches("mahogany ")), Scope.LIVE));
				final String common = String.join(" ", words) + " ";
				assertArrayEquals(new int[0], ids(session, PRODUCT,
					and(entityPrimaryKeyInSet(2_000), textMatches(common)), Scope.LIVE));
				for (final String text : new String[]{"mahogany ", "maho", "mahoganz ", common}) {
					final int[] filtered = ids(session, PRODUCT,
						and(entityPrimaryKeyInSet(candidates), textMatches(text)), Scope.LIVE);
					assertArrayEquals(candidates, filtered);
					assertArrayEquals(filtered, session.query(query(collection(PRODUCT), filterBy(
						entityLocaleEquals(Locale.ENGLISH), entityPrimaryKeyInSet(candidates), textMatches(text)),
						orderBy(relevanceText()), require(page(1, 100))), EntityReference.class).getRecordData()
						.stream().mapToInt(EntityReference::getPrimaryKey).sorted().toArray());
					assertArrayEquals(filtered, ids(session, PRODUCT,
						and(entityPrimaryKeyInSet(candidates), textMatches(text)), Scope.LIVE));
				}
			});
		}
	}

	/** Reflected declarations, bound or still waiting for their original. */
	@Nested
	@Order(4)
	@DisplayName("Reflected references")
	class ReflectedReferences {
		/**
		 * A reflected declaration is independent, and the same read resolver serves its materialized and virtual rows.
		 */
		@Test
		@DisplayName("Composes a reflected reference only through its own declaration and terminates mutual modes")
		void shouldComposeIndependentReflectedReference() {
			rollback(session -> {
				assertArrayEquals(new int[0], ids(session, BLOCK, textMatches("leather "), Scope.LIVE));
				session.getEntitySchemaOrThrow(BLOCK).openForWrite().withReflectedReferenceToEntity("products", PRODUCT,
					"blocks", r -> r.searchableThroughInScope(COMPOSITION, 1f, BOTH)).updateVia(session);
				assertArrayEquals(new int[]{101, 102, 103}, ids(session, BLOCK, textMatches("leather "), Scope.LIVE));
				final QueryPlanningContext context = context(session, BLOCK, "leather ");
				final EntitySchemaContract schema = session.getEntitySchemaOrThrow(BLOCK);
				final ReferenceSchemaContract reflected = schema.getReferenceOrThrowException("products");
				assertInstanceOf(ReducedEntityIndex.class,
					context.resolvePartitionsOfTargetForRead(schema, reflected, Scope.LIVE, 1)[0]);
				assertInstanceOf(VirtualReferencePartition.class,
					context.resolvePartitionsOfTargetForRead(schema, reflected, Scope.LIVE, 3)[0]);
				session.getEntitySchemaOrThrow(PRODUCT).openForWrite().withReferenceToEntity("blocks", BLOCK,
					Cardinality.ZERO_OR_MORE, r -> r.nonSearchableThrough()).updateVia(session);
				assertArrayEquals(new int[]{101, 102, 103}, ids(session, BLOCK, textMatches("leather "), Scope.LIVE));
				assertArrayEquals(new int[]{4}, ids(session, PRODUCT, textMatches("leather winter "), Scope.LIVE));
			});
		}

		/** A reflected reference may temporarily inherit indexing that does not support its own declaration. */
		@Test
		@DisplayName(
			"Skips a temporarily invalid inherited reflected composition instead of opening missing partitions"
		)
		void shouldSkipInvalidInheritedReflectedIndexing() {
			rollback(session -> {
				session.defineEntitySchema("IncompleteBlock").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("name", String.class, a -> a.localized().searchableInScope(BOTH)).updateVia(session);
				session.defineEntitySchema("IncompleteOwner").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("name", String.class, a -> a.localized().searchableInScope(BOTH))
					.withReferenceToEntity("blocks", "IncompleteBlock", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(BOTH)).updateVia(session);
				session.getEntitySchemaOrThrow("IncompleteBlock").openForWrite()
					.withReflectedReferenceToEntity("owners", "IncompleteOwner", "blocks",
						r -> r.searchableThroughInScope(COMPOSITION, Scope.ARCHIVED)).updateVia(session);
				session.upsertEntity(session.createNewEntity("IncompleteBlock", 1).setScope(Scope.ARCHIVED)
					.setAttribute("name", Locale.ENGLISH, "plain"));
				session.upsertEntity(session.createNewEntity("IncompleteOwner", 1)
					.setAttribute("name", Locale.ENGLISH, "leather").setReference("blocks", 1));
				assertArrayEquals(new int[]{1}, ids(session, "IncompleteBlock", textMatches("leather "), BOTH));
				session.getEntitySchemaOrThrow("IncompleteOwner").openForWrite()
					.withReferenceToEntity("blocks", "IncompleteBlock", Cardinality.ZERO_OR_MORE,
						r -> r.nonIndexed(Scope.ARCHIVED)).updateVia(session);
				assertArrayEquals(new int[0], ids(session, "IncompleteBlock", textMatches("leather "), BOTH));
			});
		}

		/**
		 * An unresolved reflected original contributes no inherited place and awaits normal session-close validation.
		 */
		@Test
		@DisplayName("Treats an unbound reflected declaration as an empty contribution during planning")
		void shouldSkipUnboundReflectedReference() {
			rollback(session -> {
				session.defineEntitySchema("Unbound").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("name", String.class, a -> a.localized().searchable())
					.withReflectedReferenceToEntity("owners", "FutureOwner", "blocks",
						r -> r.searchableThrough(COMPOSITION)).updateVia(session);
				final QueryPlanningContext context = context(session, "Unbound", "winter ");
				final FilterByVisitor visitor = FilterByVisitor.createConstraintCheckVisitor(context);
				filterBy(textMatches("winter ")).accept(visitor);
				assertArrayEquals(new int[0], ids(session, "Unbound", textMatches("winter "), Scope.LIVE));
			});
		}

		/** Local declarations remain searchable while inherited attributes await their original reference. */
		@Test
		@DisplayName("Retains local searchable attributes of an unbound inherited reflection")
		void shouldRetainLocalAttributesOfUnboundInheritedReflection() {
			assertLocalReflection(false, false);
		}

		/** Weight discovery uses the same locally available places as field discovery. */
		@Test
		@DisplayName("Retains local reflection weights during unbound inheritance")
		void shouldKeepLocalReflectionWeightsDuringUnboundInheritance() {
			assertLocalReflection(true, false);
		}

		/** Disabling inheritance keeps local places available without asking for the missing original. */
		@Test
		@DisplayName("Discovers local attributes when reflection inheritance is disabled")
		void shouldRetainLocalAttributesWithInheritanceDisabled() {
			assertLocalReflection(false, true);
		}

		/** A named unresolved inherited attribute must neither hide nor contaminate a local place. */
		@Test
		@DisplayName("Discovers local attributes beside explicitly inherited unresolved attributes")
		void shouldRetainLocalAttributesBesideUnresolvedInheritedAttributes() {
			assertLocalReflection(true, true);
		}

		/**
		 * Checks field and weight discovery against a real, temporarily unbound reflected schema.
		 * `declaresOwnField` adds an own searchable attribute beside the reflection; `specifiesInheritance` names the
		 * inherited attributes explicitly (or disables inheritance without an own field) instead of inheriting all.
		 */
		private void assertLocalReflection(boolean declaresOwnField, boolean specifiesInheritance) {
			rollback(session -> {
				final EntitySchemaBuilder builder = session.defineEntitySchema("LocalReflection")
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH);
				if (declaresOwnField) {
					builder.withAttribute("own", String.class, a -> a.localized().searchable());
				}
				builder.withReflectedReferenceToEntity("owners", "FutureOwner", "blocks", r -> {
					r.withAttribute("localText", String.class,
						a -> a.localized().nullable().searchable().searchWeight(2f));
					if (specifiesInheritance) {
						if (declaresOwnField) {
							r.withAttributesInherited("inheritedText");
						} else {
							r.withoutAttributesInherited();
						}
					} else {
						r.withAttributesInherited();
					}
				}).updateVia(session);
				final EntitySchemaContract schema = session.getEntitySchemaOrThrow("LocalReflection");
				final ReflectedReferenceSchema reference =
					(ReflectedReferenceSchema) schema.getReferenceOrThrowException("owners");
				assertFalse(reference.isReflectedReferenceAvailable());
				assertEquals(Set.of("localText"), reference.getDeclaredAttributes().keySet());
				final QueryPlanningContext context = context(session, "LocalReflection", "winter ");
				final FilterByVisitor visitor = FilterByVisitor.createConstraintCheckVisitor(context);
				assertDoesNotThrow(() -> filterBy(textMatches("winter ")).accept(visitor));
				assertEquals(2f, context.getFulltextScoringPolicy(schema)
					.weight(FulltextFieldKey.referenceAttribute("owners", "localText")));
				if (specifiesInheritance && !declaresOwnField) {
					// Enabling inheritance must preserve exactly the same locally available place.
					session.getEntitySchemaOrThrow("LocalReflection").openForWrite()
						.withReflectedReferenceToEntity("owners", "FutureOwner", "blocks",
							r -> r.withAttributesInherited()).updateVia(session);
					final QueryPlanningContext inherited = context(session, "LocalReflection", "winter ");
					assertDoesNotThrow(() -> filterBy(textMatches("winter "))
						.accept(FilterByVisitor.createConstraintCheckVisitor(inherited)));
				}
			});
		}
	}

	/** Analyzer compilation, the shared split and index reads during planning. */
	@Nested
	@Order(5)
	@DisplayName("Planning")
	class Planning {
		/** Heterogeneous target query-text steps force the same raw split a heterogeneous own place would force. */
		@Test
		@DisplayName("Includes target analyzers in the shared query text splitting decision")
		void shouldShareSplitAcrossOwnAndTargetAnalyzers() {
			rollback(session -> {
				session.getCatalogSchema().openForWrite().withFulltextAnalyzer("joined", a ->
					a.text(removeCharacters("-")).words(lowercase())).updateVia(session);
				session.defineEntitySchema("SplitTarget").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("text", String.class, a -> a.localized().searchable()).updateVia(session);
				session.defineEntitySchema("SplitOwner").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("text", String.class, a -> a.localized().searchable().analyzer("joined"))
					.withReferenceToEntity("target", "SplitTarget", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering()).updateVia(session);
				session.upsertEntity(session.createNewEntity("SplitOwner", 1)
					.setAttribute("text", Locale.ENGLISH, "bright-winter"));
				assertArrayEquals(new int[]{1}, ids(session, "SplitOwner", textMatches("bright-winter "), Scope.LIVE));
				session.getEntitySchemaOrThrow("SplitOwner").openForWrite()
					.withReferenceToEntity("target", "SplitTarget", Cardinality.ZERO_OR_MORE,
						r -> r.searchableThrough(COMPOSITION)).updateVia(session);
				assertArrayEquals(new int[0], ids(session, "SplitOwner", textMatches("bright-winter "), Scope.LIVE));
			});
		}

		/** A composition declared only in another scope changes the split, so an `inScope` sibling plans its own. */
		@Test
		@DisplayName("Plans an equal constraint separately when the processed scopes change the shared split")
		void shouldNotShareFormulaWhenProcessedScopesChangeTheSplit() {
			rollback(session -> {
				session.getCatalogSchema().openForWrite().withFulltextAnalyzer("joined", a ->
					a.text(removeCharacters("-")).words(lowercase())).updateVia(session);
				session.defineEntitySchema("SplitTarget").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("text", String.class, a -> a.localized().searchable()).updateVia(session);
				session.defineEntitySchema("SplitOwner").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("text", String.class, a -> a.localized().searchable().analyzer("joined"))
					.withReferenceToEntity("target", "SplitTarget", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(BOTH).searchableThroughInScope(COMPOSITION, Scope.ARCHIVED))
					.updateVia(session);
				session.upsertEntity(session.createNewEntity("SplitOwner", 1)
					.setAttribute("text", Locale.ENGLISH, "bright-winter"));
				final FilterConstraint live = inScope(Scope.LIVE, textMatches("bright-winter "));
				assertArrayEquals(new int[]{1}, ids(session, "SplitOwner", live, BOTH),
					"Live alone declares no composition, so the joining text step keeps the word whole");
				assertArrayEquals(new int[0], ids(session, "SplitOwner", textMatches("bright-winter "), BOTH),
					"The archived composition adds the default target analyzer and forces the raw split");

				final int[] liveFirst = ids(session, "SplitOwner", and(live, not(textMatches("bright-winter "))), BOTH);
				final int[] liveLast = ids(session, "SplitOwner", and(not(textMatches("bright-winter ")), live), BOTH);

				assertArrayEquals(new int[]{1}, liveFirst, "The negated sibling must use the split of both scopes");
				assertArrayEquals(new int[]{1}, liveLast, "The live-only sibling must use the split of its own scope");
			});
		}

		/** Checks compile target analyzers before index reads even when only validating a constraint. */
		@Test
		@DisplayName("Compiles all analyzers on the check-only path without opening a data index")
		void shouldCheckTargetAnalyzersWithoutReadingData() {
			rollback(session -> {
				session.getEntitySchemaOrThrow(BLOCK).openForWrite().withAttribute("title", String.class,
					a -> a.analyzer("html-text")).updateVia(session);
				final QueryPlanningContext context = spy(context(session, PRODUCT, "jacket "));
				doThrow(new AssertionError("A check must not read a data index"))
					.when(context).getGlobalEntityIndexIfExists(anyString(), any(Scope.class));
				final FulltextAnalyzerRegistry registry = spy(context.getFulltextAnalyzerRegistry());
				doReturn(registry).when(context).getFulltextAnalyzerRegistry();
				final FilterByVisitor visitor = FilterByVisitor.createConstraintCheckVisitor(context);
				filterBy(textMatches("jacket ")).accept(visitor);
				verify(registry).getCompiledAnalyzer(any(), eq("html-text"), eq(Locale.ENGLISH));
			});
		}

		/** Plain multi-analyzer queries resolve their scope indexes once and expand against shared own weights. */
		@Test
		@DisplayName("Reads each plain-query scope's global and fulltext indexes only once")
		void shouldResolvePlainQueryIndexesOnce() {
			rollback(session -> {
				session.getEntitySchemaOrThrow("Plain").openForWrite()
					.withAssociatedData("description", String[].class, a -> a.analyzer("html-text"))
					.updateVia(session);
				final QueryPlanningContext context = spy(context(session, "Plain", "jacket "));
				final GlobalEntityIndex index = spy(global("Plain", Scope.LIVE));
				doReturn(Optional.of(index)).when(context).getGlobalEntityIndexIfExists("Plain", Scope.LIVE);
				final TargetIndexes<GlobalEntityIndex> target =
					new TargetIndexes<>("plain", GlobalEntityIndex.class, List.of(index));
				final FilterByVisitor visitor = new FilterByVisitor(context, List.of(target), target);
				clearInvocations(context, index);
				filterBy(textMatches("jacket ")).accept(visitor);
				assertTrue(visitor.getFormula().compute().isEmpty());
				verify(context).getGlobalEntityIndexIfExists("Plain", Scope.LIVE);
				verify(index).getFulltextIndex(Locale.ENGLISH);
			});
		}
	}

	/** Runs last: reopening replaces the shared engine every other case uses. */
	@Nested
	@Order(6)
	@DisplayName("Catalog restart")
	class Restart {
		/** The persisted setting rebuilds no owner places and yields exactly the same matches after reopen. */
		@Test
		@DisplayName("Preserves composition settings and results across a catalog restart")
		void shouldSurviveRestart() {
			reopen();
			evita().queryCatalog(TEST_CATALOG, session -> {
				assertEquals(COMPOSITION, session.getEntitySchemaOrThrow(PRODUCT).getReferenceOrThrowException("blocks")
					.getSearchableThrough(Scope.LIVE));
				assertEquals(1f, session.getEntitySchemaOrThrow(PRODUCT).getReferenceOrThrowException("blocks")
					.getSearchableThroughWeight());
				assertArrayEquals(sorted(oracle("leather winter", BOTH)),
					ids(session, PRODUCT, textMatches("leather winter "), BOTH));
			});
		}
	}

	/** Creates colliding own/target ids and deliberately different target ids across scopes. */
	private static void definePlaceIdentityFixture(EvitaSessionContract session) {
		session.defineEntitySchema("PlaceTarget").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
			.withAttribute("filler", String.class, a -> a.localized().nullable().searchableInScope(Scope.ARCHIVED))
			.withAttribute("text", String[].class,
				a -> a.localized().nullable().searchableInScope(BOTH).lengthPivot(100))
			.updateVia(session);
		session.defineEntitySchema("PlaceOwner").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
			.withAttribute("text", String[].class, a -> a.localized().searchableInScope(BOTH).lengthPivot(100))
			.withReferenceToEntity("blocks", "PlaceTarget", Cardinality.ZERO_OR_MORE,
				r -> r.indexedForFilteringInScope(BOTH).searchableThroughInScope(COMPOSITION, 1f, BOTH))
			.updateVia(session);
		session.upsertEntity(session.createNewEntity("PlaceTarget", 4).setScope(Scope.ARCHIVED)
			.setAttribute("filler", Locale.ENGLISH, "unrelated"));
		session.upsertEntity(session.createNewEntity("PlaceTarget", 1)
			.setAttribute("text", Locale.ENGLISH, new String[]{"violet"}));
		session.upsertEntity(session.createNewEntity("PlaceTarget", 2)
			.setAttribute("text", Locale.ENGLISH, new String[]{"silver", "violet"}));
		session.upsertEntity(session.createNewEntity("PlaceTarget", 3).setScope(Scope.ARCHIVED)
			.setAttribute("text", Locale.ENGLISH, new String[]{"silver"}));
		for (int id = 1; id <= 3; id++) {
			final EntityBuilder owner = session.createNewEntity("PlaceOwner", id)
				.setAttribute("text", Locale.ENGLISH, new String[]{id == 1 ? "silver" : "unrelated"})
				.setReference("blocks", id == 2 ? 2 : 1);
			if (id == 3) {
				owner.setReference("blocks", 3);
			}
			session.upsertEntity(owner);
		}
	}

	/** Writes an original localized block and retains independent fixture data for the oracle. */
	private void addBlock(EvitaSessionContract session, int id, String title, Scope scope, String... content) {
		this.blocks.put(id, new Block(title, content, scope));
		final EntityBuilder builder = session.createNewEntity(BLOCK, id).setScope(scope)
			.setAssociatedData("content", Locale.ENGLISH, content);
		if (title != null) {
			builder.setAttribute("title", Locale.ENGLISH, title);
		}
		if (id == 101) {
			builder.setAssociatedData("content", CZECH, new String[]{"bunda"})
				.setReference("tags", 1, r -> r.setAttribute("note", Locale.ENGLISH, "craftsmanship"));
		}
		session.upsertEntity(builder);
	}

	/** Writes an owner with a Czech locale as well, so target locale filtering can be tested independently. */
	private void addProduct(EvitaSessionContract session, int id, String name, String[] description,
		Scope scope, int... targets) {
		this.products.put(id, new Product(name, description, targets, scope));
		final EntityBuilder builder = session.createNewEntity(PRODUCT, id).setScope(scope)
			.setAttribute("name", Locale.ENGLISH, name).setAttribute("name", CZECH, "výrobek");
		if (description != null) {
			builder.setAssociatedData("description", Locale.ENGLISH, description);
		}
		for (final int target : targets) {
			builder.setReference("blocks", target);
		}
		session.upsertEntity(builder);
	}

	/** Defines a chain and a self-referencing collection using real indexed references. */
	private static void defineOneLevelFixture(EvitaSessionContract session) {
		for (final String type : new String[]{"C", "B", "A", "Node"}) {
			final EntitySchemaBuilder schema = session.defineEntitySchema(type).withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH).withAttribute("name", String.class, a -> a.localized().searchable());
			if (!type.equals("C")) {
				final String target = type.equals("A") ? "B" : type.equals("B") ? "C" : "Node";
				schema.withReferenceToEntity("next", target, Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering().searchableThrough(COMPOSITION));
			}
			schema.updateVia(session);
		}
		session.upsertEntity(session.createNewEntity("C", 1).setAttribute("name", Locale.ENGLISH, "terminal"));
		session.upsertEntity(session.createNewEntity("B", 1).setAttribute("name", Locale.ENGLISH, "middle")
			.setReference("next", 1));
		session.upsertEntity(session.createNewEntity("A", 1).setAttribute("name", Locale.ENGLISH, "root")
			.setReference("next", 1));
		for (int id = 1; id <= 3; id++) {
			session.upsertEntity(session.createNewEntity("Node", id)
				.setAttribute("name", Locale.ENGLISH, id == 3 ? "terminal" : "middle")
				.setReference("next", id == 3 ? 1 : id + 1));
		}
	}

	/** Brute-force word-to-owner union, then intersection, independent of formula or index internals. */
	private Set<Integer> oracle(String text, Scope[] scopes) {
		final Set<Integer> result = eligible(scopes);
		final Set<Scope> requested = Set.copyOf(Arrays.asList(scopes));
		for (final String word : text.split(" ")) {
			result.removeIf(id -> {
				final Product product = this.products.get(id);
				if (contains(product.name(), word) || contains(product.description(), word)) {
					return false;
				}
				for (final int target : product.targets()) {
					final Block block = this.blocks.get(target);
					if (requested.contains(block.scope()) && (contains(block.content(), word) ||
						block.scope() == Scope.LIVE && contains(block.title(), word))) {
						return false;
					}
				}
				return true;
			});
		}
		return result;
	}

	/** Returns owners in the processed scopes. */
	private Set<Integer> eligible(Scope[] scopes) {
		final Set<Scope> requested = Set.copyOf(Arrays.asList(scopes));
		final Set<Integer> result = CollectionUtils.createHashSet(this.products.size());
		for (final Map.Entry<Integer, Product> product : this.products.entrySet()) {
			if (requested.contains(product.getValue().scope())) {
				result.add(product.getKey());
			}
		}
		return result;
	}

	/** Literal fixture words deliberately avoid stem ambiguities in the oracle cases. */
	private static boolean contains(String text, String word) {
		return text != null && Arrays.asList(text.split(" ")).contains(word);
	}

	/** A place may contain several independent items. */
	private static boolean contains(String[] texts, String word) {
		return texts != null && Arrays.stream(texts).anyMatch(text -> contains(text, word));
	}

	/** Orders oracle primary keys in the same ascending order as the membership helper. */
	private static int[] sorted(Set<Integer> ids) {
		return ids.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	/** Runs ordinary production queries without relevance, sorting ids only for deterministic comparisons. */
	private static int[] ids(EvitaSessionContract session, String type, FilterConstraint filter, Scope... scopes) {
		return session.query(query(collection(type),
			filterBy(scope(scopes), entityLocaleEquals(Locale.ENGLISH), filter),
			require(page(1, 100))), EntityReference.class).getRecordData().stream()
			.mapToInt(EntityReference::getPrimaryKey).sorted().toArray();
	}

	/** Runs a case in a real transaction and always rolls it back, preserving the shared fixture. */
	private void rollback(Consumer<EvitaSessionContract> test) {
		final boolean[] completed = {false};
		assertThrows(RollbackException.class, () -> this.evita.updateCatalog(TEST_CATALOG, session -> {
			try {
				test.accept(session);
				completed[0] = true;
			} finally {
				session.setRollbackOnly();
			}
		}));
		assertTrue(completed[0], "Every assertion must finish before the expected rollback");
	}

	/** Updates only query-time metadata of the existing managed reference. */
	private static void weight(EvitaSessionContract session, float weight) {
		session.getEntitySchemaOrThrow(PRODUCT).openForWrite().withReferenceToEntity("blocks", BLOCK,
			Cardinality.ZERO_OR_MORE, r -> r.searchableThroughInScope(COMPOSITION, weight, BOTH)).updateVia(session);
	}

	/** Compares real relevance ordering restricted to the intended fixture candidates. */
	private static void assertOrder(EvitaSessionContract session, String text, int[] candidates, int... expected) {
		assertArrayEquals(expected, session.query(query(collection(PRODUCT), filterBy(
			entityLocaleEquals(Locale.ENGLISH), entityPrimaryKeyInSet(candidates), textMatches(text)),
			orderBy(relevanceText()), require(page(1, 100))), EntityReference.class).getRecordData().stream()
			.mapToInt(EntityReference::getPrimaryKey).toArray());
	}

	/** Reads the real relevance order for a selector form. */
	private static int[] rankedIds(EvitaSessionContract session, String type, FilterConstraint constraint) {
		return session.query(query(collection(type), filterBy(entityLocaleEquals(Locale.ENGLISH), constraint),
			orderBy(relevanceText()), require(page(1, 100))), EntityReference.class).getRecordData().stream()
			.mapToInt(EntityReference::getPrimaryKey).toArray();
	}

	/** Reads scores from the real planned formula; no score implementation is duplicated by the tests. */
	private long[] scores(EvitaSessionContract session, String text, int... candidates) {
		return plannedFormula(session, PRODUCT, text).getFulltextScores(candidates).composites();
	}

	/** Extracts the one plain text score source from a real production planner context. */
	private FulltextFormula plannedFormula(EvitaSessionContract session, String type, String text, Scope... scopes) {
		return plannedFormula(session, type, textMatches(text), scopes);
	}

	/** Extracts actual scores for arbitrary selector forms through the production planner. */
	private FulltextFormula plannedFormula(
		EvitaSessionContract session, String type, FilterConstraint constraint, Scope... scopes
	) {
		final QueryPlanningContext context = context(session, type, constraint, scopes);
		final QueryPlan plan = QueryPlanner.planQuery(context);
		final Collection<FulltextFormula> formulas = FormulaFinder.find(
			plan.getFilter(), FulltextFormula.class,
			FormulaFinder.LookUp.SHALLOW);
		assertEquals(1, formulas.size(), "Composition belongs to the owner's single text source");
		return formulas.iterator().next();
	}

	/** Builds the production planning context without asking for any data index. */
	private QueryPlanningContext context(EvitaSessionContract session, String type, String text, Scope... scopes) {
		return context(session, type, textMatches(text), scopes);
	}

	/** Builds the production context with the supplied fulltext selector forms. */
	private QueryPlanningContext context(
		EvitaSessionContract session, String type, FilterConstraint constraint, Scope... scopes
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

	/** Reads the global index without changing it, to pin index-neutral metadata changes. */
	private GlobalEntityIndex global(String type, Scope scope) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntityOrThrowException(type);
		final Map<EntityIndexKey, EntityIndex> indexes = field(collection, "indexes");
		return (GlobalEntityIndex) indexes.get(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
	}

	/** Inspects planner data unavailable through the public query response. */
	@SuppressWarnings("unchecked")
	private static <T> T field(Object instance, String name) {
		try {
			final Field field = instance.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(instance);
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}

	/** Independent fixture data for the brute-force membership oracle. */
	private record Product(String name, String[] description, int[] targets, Scope scope) { }
	/** Independent fixture data for target locale/scope membership. */
	private record Block(String title, String[] content, Scope scope) { }
}
