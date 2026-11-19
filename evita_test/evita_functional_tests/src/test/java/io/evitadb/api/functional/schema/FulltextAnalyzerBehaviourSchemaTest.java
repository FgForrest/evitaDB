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


package io.evitadb.api.functional.schema;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaEditor;
import io.evitadb.api.requestResponse.schema.analysis.SynonymGroup;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowLocaleInEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerBehaviourCheck;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.fulltext.analysis.IndexedTermConsumer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.*;
import static io.evitadb.test.utils.ReflectionUtils.getNonnullFieldValue;
import static io.evitadb.test.utils.ReflectionUtils.setFieldValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real-session coverage of bounded analyzer behavior checks, publication refusal and legacy failing schemas. */
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(FULLTEXT)
@Tag(SESSION)
@DisplayName("Final analyzer behavior validation")
class FulltextAnalyzerBehaviourSchemaTest implements EvitaTestSupport {
	/** Isolated storage, also used for restart and WAL snapshots. */
	@Nonnull private TestPaths paths;
	/** Embedded engine with real catalogs, transactions and installed analysis providers. */
	@Nonnull private Evita engine;

	/** Opens a fresh empty warm-up catalog. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextAnalyzerBehaviour");
		this.engine = new Evita(configuration(this.paths));
		this.engine.defineCatalog(TEST_CATALOG);
	}

	/** Closes any surviving engine and removes isolated storage. */
	@AfterEach
	void tearDown() {
		if (this.engine != null && this.engine.isActive()) {
			this.engine.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Keeps transactional mutations in WAL long enough to prove recovery uses replay.
	 * @param paths storage and temporary directories
	 * @return configuration with a one-hour checkpoint interval
	 */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths paths) {
		final EvitaConfiguration base = newTestEvitaConfigurationBuilder(paths).build();
		return EvitaConfiguration.builder(base).transaction(TransactionOptions.builder(base.transaction())
			.checkpointIntervalInMillis(3_600_000L).build()).build();
	}

	/** The four rules use actual compiled chains, including resource preparation through preceding words steps. */
	@Nested
	@DisplayName("Rules and diagnostics")
	class Rules {
		/** A chain preserving case cannot find the lower-cased author sample, even when the accent is unchanged. */
		@Test
		@DisplayName("Rule 1 checks typed and lower-cased author samples")
		void shouldCheckSelfMatch() {
			goLive();
			assertRefused(b -> b.withFulltextAnalyzer("plain", a -> a.words().samples("ÉTAGE")),
				"plain", "root", "lower-cased", "ÉTAGE", "index terms [ÉTAGE]", "search alternatives [étage]");
			change(b -> b.withFulltextAnalyzer("plain", a -> a.words(lowercase()).samples("ÉTAGE")));
		}

		/** An author sample removed entirely by the index side has its own self-match diagnostic. */
		@Test
		@DisplayName("Rule 1 explains samples with empty index terms")
		void shouldRefuseEmptyIndexSample() {
			goLive();
			assertRefused(b -> b.withFulltextResource("stops", wordList("and"))
				.withFulltextAnalyzer("empty", a -> a.words(stopWords(resource("stops"))).samples("and")),
				"empty", "rule 1", "empty index terms", "and", "index terms []", "search alternatives []");
		}

		/** A lower-cased query with no surviving word cannot match its indexed author sample. */
		@Test
		@DisplayName("Rule 1 refuses a stopped lower-cased author sample")
		void shouldRefuseEmptySearchSample() {
			goLive();
			assertRefused(b -> b.withFulltextResource("stops", wordList("upper"))
				.withFulltextAnalyzer("plain", a -> a.words(stopWords(resource("stops"))).samples("Upper")),
				"plain", "root", "rule 1", "lower-cased", "empty search alternatives", "Upper",
				"index terms [Upper]", "search alternatives []");
		}

		/** The built-in list may include normally indexed words that the lower-cased query drops. */
		@Test
		@DisplayName("Rule 1 skips a built-in sample whose lower-cased search has no word")
		void shouldSkipEmptyBuiltInSearch() {
			goLive();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite().withFulltextResource("stops", wordList("cup"))
					.withFulltextAnalyzer("plain", a -> a.words(stopWords(resource("stops")))).updateVia(session);
				session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
					.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("plain"))
					.updateVia(session);
				session.upsertEntity(session.createNewEntity("Item", 1).setAttribute("name", Locale.ENGLISH, "Cup"));
			});
			engine.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(1, session.queryList(query(collection("Item"),
					filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("Cup "))), EntityReference.class).size());
				assertTrue(session.queryList(query(collection("Item"),
					filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("cup "))),
					EntityReference.class).isEmpty());
				return null;
			});
		}

		/** The sample and a closed query share the count before any query words are dropped. */
		@Test
		@DisplayName("Rule 1 uses the textMatches word limit for author samples")
		void shouldRefuseSamplePastQueryWordLimit() {
			goLive();
			final int limit = FulltextPhaseOneScorer.MAX_QUERY_TOKENS;
			final String accepted = "aa ".repeat(limit).stripTrailing();
			assertRefused(b -> b.withFulltextAnalyzer("plain", a -> a.words(lowercase()).samples(accepted + " aa")),
				"plain", "rule 1", "at most " + limit + " words", "index terms [aa]", "search alternatives [aa]");
			change(b -> b.withFulltextAnalyzer("plain", a -> a.words(lowercase()).samples(accepted)));
		}

		/** Prefix preparation makes stemming-before-stop valid; token splitting of a multiword entry still fails. */
		@Test
		@DisplayName("Rule 2 uses actual index output, accepting prepared stop words after stemming")
		void shouldCheckStopWords() {
			goLive();
			assertRefusedForField("stopped", b -> b.withFulltextResource("stops", wordList("running rooms"))
				.withFulltextAnalyzer("stopped", a -> a.words(lowercase(), englishStemming(),
					stopWords(resource("stops")))),
				"stopped", "rule 2", "running rooms", "index terms [run, room]", "search alternatives [run, room]");
			changeForField("stopped", b -> b.withFulltextResource("stops", wordList("running"))
				.withFulltextAnalyzer("stopped", a -> a.words(lowercase(), englishStemming(),
					stopWords(resource("stops")))));
		}

		/** Text transformations precede words at runtime, but resource preparation excludes those transformations. */
		@Test
		@DisplayName("Rule 3 compares protected words with the naming stemmer leaving them unstemmed")
		void shouldCheckProtectedWords() {
			goLive();
			assertRefusedForField("guarded", b -> b.withFulltextResource("protected", wordList("runn&#105;ng"))
				.withFulltextAnalyzer("guarded", a -> a.text(htmlStrip()).words(lowercase(),
					englishStemming(protectedWords(resource("protected"))))),
				"guarded", "rule 3", "runn&#105;ng", "without stemmer: [running]", "index terms [run]",
				"search alternatives [runn, 105, ng]");
			changeForField("guarded", b -> b.withFulltextResource("protected", wordList("running"))
				.withFulltextAnalyzer("guarded", a -> a.words(lowercase(),
					englishStemming(protectedWords(resource("protected"))))));
		}

		/**
		 * A stemmer's own normalization still applies to a protected word, so index and search agree on its form.
		 * @param stemmer language of the stemming step whose normalization changes the word
		 * @param word protected word carrying characters that normalization changes
		 */
		@ParameterizedTest(name = "{0}")
		@CsvSource({"czech, Kočka", "german, Bücher"})
		@DisplayName("Rule 3 accepts a protected word changed by the stemmer's own normalization")
		void shouldAcceptProtectedWordNormalizedByStemmer(@Nonnull String stemmer, @Nonnull String word) {
			goLive();
			changeForField("guarded", b -> b.withFulltextResource("protected", wordList(word))
				.withFulltextAnalyzer("guarded", a -> a.words(lowercase(), switch (stemmer) {
					case "czech" -> czechStemming(protectedWords(resource("protected")));
					case "german" -> germanStemming(protectedWords(resource("protected")));
					default -> throw new IllegalArgumentException(stemmer);
				})));
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.upsertEntity(session.createNewEntity("Item", 1).setAttribute("name", Locale.FRENCH, word));
			});
			engine.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(1, session.queryList(query(collection("Item"), filterBy(entityLocaleEquals(Locale.FRENCH),
					textMatches(word.toLowerCase(Locale.ROOT) + " "))), EntityReference.class).size());
			});
		}

		/** Synonyms prepared after stemming meet; a text transformation outside resource preparation can prevent it. */
		@Test
		@DisplayName("Rule 4 checks ordered synonym pairs using query-word alternatives")
		void shouldCheckSynonyms() {
			goLive();
			assertRefusedForField("related",
				b -> b.withFulltextResource("equivalents", synonymList(synonymGroup("&eacute;", "walking")))
				.withFulltextAnalyzer("related", a -> a.text(htmlStrip()).words(lowercase(), englishStemming(),
					synonyms(resource("equivalents")))),
				"related", "rule 4", "&eacute; -> walking", "index terms [walk]", "search alternatives [eacut]");
			changeForField("related",
				b -> b.withFulltextResource("equivalents", synonymList(synonymGroup("running", "walking")))
				.withFulltextAnalyzer("related", a -> a.words(lowercase(), englishStemming(),
					synonyms(resource("equivalents")))));
		}

		/** The left member disappearing must fail before its reverse direction is evaluated. */
		@Test
		@DisplayName("Rule 4 refuses the direction whose synonym query drops every word")
		void shouldRefuseEmptySynonymSearch() {
			goLive();
			assertRefusedForField("related",
				b -> b.withFulltextResource("stops", wordList("upper"))
					.withFulltextResource("equivalents", synonymList(synonymGroup("upper", "other")))
					.withFulltextAnalyzer("related", a -> a.words(lowercase(), stopWords(resource("stops")),
						synonyms(resource("equivalents")))),
				"related", "rule 4", "upper -> other", "index terms [other]", "search alternatives []");
		}

		/** Both diagnostic sides retain at most ten terms even when a failing sample emits more. */
		@Test
		@DisplayName("Diagnostic index and search terms are capped at ten each")
		void shouldBoundDiagnosticTerms() {
			goLive();
			final String sample = IntStream.range(0, 20).mapToObj(i -> "WORD" + i)
				.collect(Collectors.joining(" "));
			final String message = assertRefused(b -> b.withFulltextAnalyzer("many", a -> a.words().samples(sample)),
				"rule 1").getMessage();
			final String index = message.substring(message.indexOf("index terms "));
			assertTrue(index.contains("WORD9"));
			assertTrue(index.contains("word9"));
			assertFalse(index.contains("WORD10"));
			assertFalse(index.contains("word10"));
		}

		/** All pairs are visited after ten failures, while one diagnostic per pair and ten overall are retained. */
		@Test
		@DisplayName("Aggregates bounded diagnostics with truncated samples and terms")
		void shouldBoundDiagnosticsAndEvaluateAllPairs() {
			goLive();
			final AtomicInteger analyzedPairs = new AtomicInteger();
			final String sample = "É" + "tage ".repeat(30);
			try (final MockedStatic<io.evitadb.core.query.filter.translator.fulltext.TextMatchesTranslator> observer =
				mockStatic(io.evitadb.core.query.filter.translator.fulltext.TextMatchesTranslator.class, invocation -> {
					if (invocation.getMethod().getName().equals("analyzeAlternatives")) {
						analyzedPairs.incrementAndGet();
					}
					return invocation.callRealMethod();
				})) {
				final InvalidSchemaMutationException failure = assertRefusal(() ->
					engine.updateCatalog(TEST_CATALOG, session -> {
						final CatalogSchemaBuilder builder = session.getCatalogSchema().openForWrite();
						for (int i = 0; i < 12; i++) {
							builder.withFulltextAnalyzer("plain" + i, a -> a.words().samples(sample, "SECOND"));
						}
						builder.updateVia(session);
						final var collection = session.defineEntitySchema("Item")
							.withLocale(Locale.ENGLISH, Locale.FRENCH);
						for (int i = 0; i < 12; i++) {
							final String analyzer = "plain" + i;
							collection.withAttribute("field" + i, String.class,
								a -> a.localized().searchable().analyzer(analyzer));
						}
						collection.updateVia(session);
					}), "rule 1", sample.substring(0, 80));
				assertEquals(10, failure.getMessage().lines().count());
				assertFalse(failure.getMessage().contains(sample.substring(0, 81)));
				assertFalse(failure.getMessage().contains("SECOND"));
				assertEquals(48, analyzedPairs.get());
			}
		}
	}

	/** Field usage and sample origin decide which checks apply. */
	@Nested
	@DisplayName("Field languages and sample origin")
	class FieldLanguages {
		/** An unused analyzer checks its author samples in root and leaves resource behavior unchecked. */
		@Test
		@DisplayName("Checks unused author samples without folding or evaluating unused resources and overrides")
		void shouldAcceptUnusedAnalyzerWithAccentedSample() {
			goLive();
			change(b -> b.withFulltextLanguage("cs", l -> l.wordNumberSplit())
				.withFulltextResource("stops", wordList("running rooms"))
				.withFulltextAnalyzer("unused", a -> a.words(lowercase(), stopWords(resource("stops")))
					.language("en", l -> l.words()).samples("ÉTAGE")));
		}

		/** A checked Czech profile may drop a built-in word but must still process the mixed-case words it retains. */
		@Test
		@DisplayName("Skips a dropped built-in sample in a used language")
		void shouldSkipDroppedBuiltInWord() {
			goLive();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite().withFulltextResource("stops", wordList("židle"))
					.withFulltextAnalyzer("custom", a -> a.words(lowercase())
						.language("cs", l -> l.words(lowercase(), stopWords(resource("stops")), asciiFolding()))
						.samples("Shelf")).updateVia(session);
				session.defineEntitySchema("Item").withLocale(Locale.forLanguageTag("cs"))
					.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("custom"))
					.updateVia(session);
			});
		}

		/** A used English chain is allowed to preserve diacritics in an author sample. */
		@Test
		@DisplayName("Lower-cases a used English author sample without stripping its diacritics")
		void shouldKeepDiacriticsInUsedAuthorSample() {
			goLive();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("custom", a -> a.words(lowercase()).samples("ÉTAGE")).updateVia(session);
				session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
					.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("custom"))
					.updateVia(session);
			});
		}

		/** First field use makes a custom analyzer subject to the language's case checks. */
		@Test
		@DisplayName("First use checks the mixed-case sample of the field's language")
		void shouldRefuseFirstUseOfCaseSensitiveAnalyzer() {
			goLive();
			change(b -> b.withFulltextAnalyzer("caseSensitive", a -> a.words()));
			assertRefusal(() -> engine.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
					.withAssociatedData("name", String.class,
						a -> a.localized().searchable().analyzer("caseSensitive")).updateVia(session);
			}), "`caseSensitive`", "language `en`", "lower-cased", "Cup");
		}

		/** Scope-free language declarations and unused overrides cannot trigger refusal for an English field. */
		@Test
		@DisplayName("Only the language of a field using the analyzer participates")
		void shouldIgnoreUnusedLanguageOverrides() {
			goLive();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite().withFulltextLanguage("cs", l -> l.wordNumberSplit())
					.withFulltextAnalyzer("custom", a -> a.words(lowercase()).language("cs", l -> l.words()))
					.updateVia(session);
				session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
					.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("custom"))
					.updateVia(session);
			});
		}

		/**
		 * A data upsert can extend a collection's locales without editing its analyzer.
		 * @param alive whether the write runs after go-live
		 */
		@ParameterizedTest(name = "alive={0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Data upserts may add a locale to an untouched analyzer")
		void shouldAllowUpsertAddingLocale(boolean alive) {
			prepareLocaleSensitiveAnalyzer();
			if (alive) {
				goLive();
			}
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.upsertEntity(session.createNewEntity("Item", 1)
					.setAttribute("name", Locale.forLanguageTag("cs"), "hrnek"));
			});
			reopen();
			engine.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(1, session.queryList(query(collection("Item"),
					filterBy(entityLocaleEquals(Locale.forLanguageTag("cs")), textMatches("hrnek "))),
					EntityReference.class).size());
			});
		}

		/**
		 * The locale change alone remains admissible whether a later mutation in the batch succeeds or fails.
		 * @param failLater whether to fail a following catalog mutation without reverting the entity schema
		 */
		@ParameterizedTest(name = "failLater={0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("AllowLocale leaves its analyzer untouched even when a later mutation fails")
		void shouldAllowLocaleOnlyChange(boolean failLater) {
			prepareLocaleSensitiveAnalyzer();
			final Locale czech = Locale.forLanguageTag("cs");
			final ModifyEntitySchemaMutation addLocale =
				new ModifyEntitySchemaMutation("Item", new AllowLocaleInEntitySchemaMutation(czech));
			engine.updateCatalog(TEST_CATALOG, session -> {
				if (failLater) {
					assertThrows(EntityTypeAlreadyPresentInCatalogSchemaException.class,
						() -> session.updateCatalogSchema(addLocale, new CreateEntitySchemaMutation("Item")));
				} else {
					session.updateCatalogSchema(addLocale);
				}
				assertTrue(session.getEntitySchemaOrThrowException("Item").getLocales().contains(czech));
			});
			reopen();
			assertTrue(catalog().getSchema().getEntitySchema("Item").orElseThrow().getLocales().contains(czech));
		}

		/** A new field in an already-used locale must check its assignment independently of other fields. */
		@Test
		@DisplayName("Checks a new assignment even when another field already uses the analyzer in that locale")
		void shouldCheckNewFieldInPreviouslyUsedLocale() {
			prepareLocaleSensitiveAnalyzer();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getEntitySchemaOrThrowException("Item").openForWrite().withLocale(Locale.forLanguageTag("cs"))
					.updateVia(session);
			});
			goLive();
			engine.updateCatalog(TEST_CATALOG, session -> {
				assertTrue(session.renameCollection("Item", "Renamed"));
			});
			assertRefusal(() -> engine.updateCatalog(TEST_CATALOG, session -> {
				session.getEntitySchemaOrThrowException("Renamed").openForWrite()
					.withAttribute("second", String.class, a -> a.localized().searchable().analyzer("localeSensitive"))
					.updateVia(session);
			}), "localeSensitive", "language `cs`", "Hrnek");
		}

		/** The retained field binding of a failed warm-up batch is still an explicit author edit. */
		@Test
		@DisplayName("Final validation checks a retained analyzer binding after a failed warm-up batch")
		void shouldRefuseRetainedFieldBindingAfterFailedBatch() {
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("caseSensitive", a -> a.words()).updateVia(session);
				session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
					.withAttribute("name", String.class, a -> a.localized().searchable()).updateVia(session);
			});
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			final ModifyEntitySchemaMutation attach = session.getEntitySchemaOrThrowException("Item").openForWrite()
				.withAttribute("name", String.class, a -> a.analyzer("caseSensitive")).toMutation().orElseThrow();
			assertThrows(EntityTypeAlreadyPresentInCatalogSchemaException.class,
				() -> session.updateCatalogSchema(attach, new CreateEntitySchemaMutation("Item")));
			assertEquals("caseSensitive", session.getEntitySchemaOrThrowException("Item")
				.getAttribute("name").orElseThrow().getAnalyzer());
			final InvalidSchemaMutationException refusal =
				CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
					engine, TEST_CATALOG, InvalidSchemaMutationException.class, session::close
				);
			for (final String fragment : List.of("`caseSensitive`", "language `en`", "lower-cased", "Cup")) {
				assertTrue(refusal.getMessage().contains(fragment), refusal.getMessage());
			}
			reopen();
			assertEquals("text", catalog().getSchema().getEntitySchema("Item").orElseThrow()
				.getAttribute("name").orElseThrow().getAnalyzer());
		}

		/** Replacing a base actually used by an inherited phase changes its declaration dependency. */
		@Test
		@DisplayName("Checks an inherited phase when an intermediate basedOn link changes")
		void shouldCheckChangedInheritedBaseLink() {
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("firstBase", a -> a.words(lowercase()).language("cs", l -> l.words()))
					.withFulltextAnalyzer("secondBase", a -> a.words(lowercase()).language("cs", l -> l.words()))
					.withFulltextAnalyzer("middle", a -> a.basedOn("firstBase"))
					.withFulltextAnalyzer("leaf", a -> a.basedOn("middle")).updateVia(session);
				session.defineEntitySchema("Item").withLocale(Locale.FRENCH)
					.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("leaf"))
					.updateVia(session);
			});
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getEntitySchemaOrThrowException("Item").openForWrite().withLocale(Locale.forLanguageTag("cs"))
					.updateVia(session);
			});
			goLive();
			assertRefused(b -> b.withFulltextAnalyzer("middle", a -> a.basedOn("secondBase")),
				"leaf", "language `cs`", "Hrnek");
		}

		/** A used Czech chain must find a built-in sample typed without its diacritics. */
		@Test
		@DisplayName("Checks built-in samples of a used language without their diacritics")
		void shouldRefuseUsedChainKeepingBuiltInDiacritics() {
			goLive();
			final Locale czech = Locale.forLanguageTag("cs");
			assertRefusal(() -> changeForField("custom", czech,
				b -> b.withFulltextAnalyzer("custom", a -> a.words(lowercase()))),
				"`custom`", "language `cs`", "rule 1", "without diacritics", "židle");
			changeForField("custom", czech,
				b -> b.withFulltextAnalyzer("custom", a -> a.words(lowercase(), asciiFolding())));
		}

		/** A searchable attribute of a reference is a field of its analyzer like an attribute of the entity. */
		@Test
		@DisplayName("Checks an analyzer attached to a searchable reference attribute")
		void shouldCheckAnalyzerOfReferenceAttribute() {
			goLive();
			change(b -> b.withFulltextAnalyzer("caseSensitive", a -> a.words())
				.withFulltextAnalyzer("lowerCased", a -> a.words(lowercase())));
			assertRefusal(() -> attachToReferenceAttribute("caseSensitive"),
				"`caseSensitive`", "language `en`", "lower-cased", "Cup");
			attachToReferenceAttribute("lowerCased");
		}
	}

	/** Warm-up checks can postpone publication and recovery can load older failing declarations. */
	@Nested
	@DisplayName("Lifecycle boundaries")
	class Lifecycle {
		/** DDL can append and retire files while the last published schema still excludes the failing change. */
		@Test
		@DisplayName("Create, rename and delete cannot publish a failing analyzer before the session closes")
		void shouldKeepFailingAnalyzerOutOfPublishedWarmUpState() {
			prepareCollections();
			final Catalog before = catalog();
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			declareFailingAnalyzer(session);
			mutateCollections(session);
			CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(engine, TEST_CATALOG,
				InvalidSchemaMutationException.class, session::close);
			assertFalse(session.isActive());
			assertFalse(before.isPublishable());
			CatalogUnpublishableBarrierAssertions.activateWithConflictRetry(engine, TEST_CATALOG);
			assertOriginalPublication();
			reopen();
			assertOriginalPublication();
		}

		/** A failed session drain can reach termination with a writer still registered. */
		@Test
		@DisplayName("Termination with a registered author leaves the previous bootstrap intact")
		void shouldKeepPublishedStateWhenTerminationCannotDrainWriter() throws IOException {
			prepareCollections();
			final Catalog original = catalog();
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			try {
				declareFailingAnalyzer(session);
				assertTrue(session.isActive());
				original.terminate();
				assertTrue(original.isTerminated());
				assertStorageSnapshotStillOriginal();
			} finally {
				if (session.isActive()) {
					assertThrows(RuntimeException.class, session::close);
				}
			}
		}

		/**
		 * A healthy writer's pending analyzer, field and data survive an orderly shutdown or a failed drain.
		 * @param failDrain whether the registry gives up before draining the real writer
		 */
		@ParameterizedTest(name = "failDrain={0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Shutdown publishes the healthy open writer's changes")
		void shouldPublishHealthyWriterOnShutdown(boolean failDrain) {
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema("Item").withLocale(Locale.ENGLISH).updateVia(session);
			});
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			session.getCatalogSchema().openForWrite()
				.withFulltextAnalyzer("plain", a -> a.words(lowercase()).samples("Upper")).updateVia(session);
			session.getEntitySchemaOrThrowException("Item").openForWrite()
				.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("plain"))
				.updateVia(session);
			session.upsertEntity(session.createNewEntity("Item", 1).setAttribute("name", Locale.ENGLISH, "Upper"));
			if (failDrain) {
				failSessionDrain();
			}

			reopen();
			assertTrue(catalog().getSchema().getFulltextAnalyzer("plain").isPresent());
			engine.queryCatalog(TEST_CATALOG, reader -> {
				assertEquals(1, reader.queryList(query(collection("Item"),
					filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("upper "))),
					EntityReference.class).size());
			});
		}

		/** A failed drain keeps the previous bootstrap when the writer's schema fails and logs the reason. */
		@Test
		@DisplayName("Shutdown warns when a failing writer prevents publication")
		void shouldWarnOnRejectedShutdownPublication() {
			prepareCollections();
			declareFailingAnalyzer(engine.createReadWriteSession(TEST_CATALOG));
			failSessionDrain();
			final List<ILoggingEvent> warnings = captureCatalogWarnings(() -> reopen());
			assertOriginalPublication();
			assertTrue(warnings.stream().anyMatch(event -> event.getFormattedMessage().contains(TEST_CATALOG) &&
				event.getFormattedMessage().contains("rule 1")));
		}

		/** The final refusal remains binding when the writer leaves while termination is checking admission. */
		@Test
		@DisplayName("Termination sees a refusal raised just before the writer leaves the registry")
		void shouldKeepPublishedStateWhenWriterRefusesDuringTermination() {
			prepareCollections();
			final Catalog original = catalog();
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			declareFailingAnalyzer(session);
			final Map<String, SessionRegistry> registries = getNonnullFieldValue(engine, "catalogSessionRegistries");
			final SessionRegistry observed = spy(registries.get(TEST_CATALOG));
			doAnswer(invocation -> {
				assertThrows(InvalidSchemaMutationException.class, session::close);
				assertFalse(session.isActive());
				return invocation.callRealMethod();
			}).when(observed).getWarmUpAnalyzerChecks(original);
			registries.put(TEST_CATALOG, observed);

			final List<ILoggingEvent> warnings;
			try (final var deactivation = CatalogUnpublishableBarrierAssertions.subscribeToSettledState(
				engine, TEST_CATALOG, CatalogState.INACTIVE
			)) {
				warnings = captureCatalogWarnings(original::terminate);
				deactivation.await();
			}
			verify(observed).getWarmUpAnalyzerChecks(original);
			assertFalse(original.isPublishable());
			// the barrier raised by the refusing close is the reason the termination names
			assertTrue(warnings.stream().anyMatch(event -> event.getFormattedMessage().contains(TEST_CATALOG) &&
				event.getFormattedMessage().contains("rule 1")));
			CatalogUnpublishableBarrierAssertions.activateWithConflictRetry(engine, TEST_CATALOG);
			assertOriginalPublication();
		}

		/**
		 * Termination holds the warm-up call lock from before its decision until the collections are released, so a
		 * call the registered writer starts while termination is at its registry lookup waits for the termination to
		 * end.
		 * Termination therefore validates and publishes the state before that call - the writer's unpublished item
		 * included - and nothing of the change, whether the change would pass the behaviour check or fail it.
		 * @param failing whether the writer's change declares an analyzer that fails the behaviour check
		 * @throws IOException when the published storage cannot be copied
		 */
		@ParameterizedTest(name = "failing={0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("A schema change started at the termination's registry lookup waits and is not published")
		void shouldNotPublishSchemaChangeStartedAtTerminationRegistryLookup(boolean failing) throws IOException {
			final EvitaSessionContract session = openWriterWithUnpublishedItem();
			final Catalog original = catalog();
			final AtomicReference<CompletableFuture<Void>> writerCall = new AtomicReference<>();
			final Runnable change = writerChange(session, original, failing, writerCall);
			final AtomicInteger lookups = new AtomicInteger();
			final Map<String, SessionRegistry> registries = getNonnullFieldValue(engine, "catalogSessionRegistries");
			final SessionRegistry observed = spy(registries.get(TEST_CATALOG));
			doAnswer(invocation -> {
				final Object checks = invocation.callRealMethod();
				// the writer's own path may ask the same question, and only termination's first one pauses
				if (lookups.incrementAndGet() == 1) {
					assertNotNull(checks, "the writer is registered when termination looks it up");
					change.run();
				}
				return checks;
			}).when(observed).getWarmUpAnalyzerChecks(original);
			registries.put(TEST_CATALOG, observed);

			original.terminate();

			awaitWriterCall(writerCall);
			assertTrue(lookups.get() >= 1);
			assertTrue(original.isTerminated());
			assertEquals(new PublishedState(CatalogState.WARMING_UP, false, false, false, 1), readPublishedState());
		}

		/**
		 * Termination validates first and publishes afterwards. A call the registered writer starts in between - here
		 * once the behaviour check has passed and before the collections are flushed and the header stored - waits on
		 * the warm-up call lock, so the schema termination validated is the schema it publishes: the state before the
		 * call, with the writer's unpublished item, loads and answers queries.
		 * @throws IOException when the published storage cannot be copied
		 */
		@Test
		@DisplayName("A schema change started after termination's validation waits and is not published")
		void shouldNotPublishSchemaChangeStartedAfterTerminationValidation() throws IOException {
			final EvitaSessionContract session = openWriterWithUnpublishedItem();
			final Catalog original = catalog();
			final AtomicReference<CompletableFuture<Void>> writerCall = new AtomicReference<>();
			final Runnable change = writerChange(session, original, false, writerCall);
			final AtomicInteger checks = new AtomicInteger();
			// a static mock observes only the thread that opened it - the terminating one, which is this one
			try (final MockedStatic<FulltextAnalyzerBehaviourCheck> check =
				mockStatic(FulltextAnalyzerBehaviourCheck.class, CALLS_REAL_METHODS)) {
				check.when(() -> FulltextAnalyzerBehaviourCheck.validate(any(), any(), any(), any()))
					.thenAnswer(invocation -> {
						invocation.callRealMethod();
						checks.incrementAndGet();
						change.run();
						return null;
					});
				original.terminate();
			}

			awaitWriterCall(writerCall);
			assertEquals(1, checks.get());
			assertTrue(original.isTerminated());
			assertEquals(new PublishedState(CatalogState.WARMING_UP, false, false, false, 1), readPublishedState());
		}

		/**
		 * The bootstrap for each collection DDL in a batch must include the analyzer removal it just validated.
		 * @param ddl operation creating, renaming or deleting a collection
		 * @param compileInvalid whether the removed analyzer fails compilation instead of a behavior rule
		 * @throws IOException when the on-disk snapshot cannot be copied
		 */
		@ParameterizedTest(name = "{0}, compile-invalid={1}")
		@CsvSource({"create,false", "rename,false", "delete,false", "create,true", "rename,true", "delete,true"})
		@DisplayName("Mid-call collection DDL stores the repaired schema")
		void shouldPublishRepairedCatalogSchemaWithCollectionDdl(@Nonnull String ddl, boolean compileInvalid)
			throws IOException {
			prepareCollections();
			try (final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG)) {
				if (compileInvalid) {
					session.getCatalogSchema().openForWrite().withFulltextAnalyzer("plain",
						a -> a.words(asciiFolding(), czechStemming())).updateVia(session);
				} else {
					declareFailingAnalyzer(session);
				}
				final LocalCatalogSchemaMutation operation = switch (ddl) {
					case "create" -> new CreateEntitySchemaMutation("Created");
					case "rename" -> new ModifyEntitySchemaNameMutation("Original", "Renamed", false);
					case "delete" -> new RemoveEntitySchemaMutation("Removable");
					default -> throw new IllegalArgumentException(ddl);
				};
				final Set<String> collections = switch (ddl) {
					case "create" -> Set.of("Original", "Removable", "Created");
					case "rename" -> Set.of("Renamed", "Removable");
					default -> Set.of("Original");
				};
				session.updateCatalogSchema(new RemoveFulltextAnalyzerSchemaMutation("plain"), operation);
				assertStorageSnapshot(collections, null);
			}
		}

		/**
		 * A preceding nested cascade can buffer a schema part before the outer batch makes further edits.
		 * @throws IOException when the on-disk snapshot cannot be copied
		 */
		@Test
		@DisplayName("Mid-call DDL publishes edits after a preceding nested schema cascade")
		void shouldPublishSchemaAfterNestedCascade() throws IOException {
			prepareCollections();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite().withAttribute("code", String.class).updateVia(session);
				session.getEntitySchemaOrThrowException("Original").openForWrite().withGlobalAttribute("code")
					.updateVia(session);
			});
			try (final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG)) {
				declareFailingAnalyzer(session);
				session.updateCatalogSchema(
					new RemoveAttributeSchemaMutation("code"),
					new RemoveFulltextAnalyzerSchemaMutation("plain"),
					new ModifyCatalogSchemaDescriptionMutation("Repaired"),
					new CreateEntitySchemaMutation("Created")
				);
				assertStorageSnapshot(Set.of("Original", "Removable", "Created"), "Repaired");
			}
		}

		/** A later refused mutation restores the catalog schema part for the next flush. */
		@Test
		@DisplayName("The next flush uses the restored schema after a mid-call publication")
		void shouldBufferRestoredSchemaAfterPartlyPublishedBatch() throws IOException {
			prepareCollections();
			try (final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG)) {
				assertThrows(EntityTypeAlreadyPresentInCatalogSchemaException.class, () -> session.updateCatalogSchema(
					new ModifyCatalogSchemaDescriptionMutation("Published prefix"),
					new CreateEntitySchemaMutation("Created"),
					new CreateEntitySchemaMutation("Original")
				));
				assertStorageSnapshot(Set.of("Original", "Removable", "Created"), "Published prefix");
				assertNull(session.getCatalogSchema().getDescription());
			}
			reopen();
			assertNull(catalog().getSchema().getDescription());
			assertEquals(Set.of("Original", "Removable", "Created"), catalog().getEntityTypes());
		}

		/** A later call that reaches the schema version a failed batch published mid-call must still be published. */
		@Test
		@DisplayName("Close publishes a schema change made after a partly published failed batch")
		void shouldPublishSchemaChangedAfterPartlyPublishedFailedBatch() {
			prepareCollections();
			try (final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG)) {
				assertThrows(EntityTypeAlreadyPresentInCatalogSchemaException.class, () -> session.updateCatalogSchema(
					new ModifyCatalogSchemaDescriptionMutation("Published prefix"),
					new CreateEntitySchemaMutation("Created"),
					new CreateEntitySchemaMutation("Original")
				));
				session.updateCatalogSchema(new ModifyCatalogSchemaDescriptionMutation("After failure"));
			}
			reopen();
			assertEquals("After failure", catalog().getSchema().getDescription());
		}

		/** A failing write of the restored schema must not replace the batch failure or skip the restoration. */
		@Test
		@DisplayName("A failed write of the restored schema is suppressed into the batch failure")
		void shouldReportBatchFailureWhenRestoredSchemaCannotBeStored() {
			prepareCollections();
			final Catalog catalog = catalog();
			final DataStoreMemoryBuffer original = getNonnullFieldValue(catalog, "dataStoreBuffer");
			final DataStoreMemoryBuffer failingWrite = spy(original);
			doThrow(new UncheckedIOException(new IOException("Simulated storage write failure")))
				.doCallRealMethod()
				.when(failingWrite).update(anyLong(), any(CatalogSchemaStoragePart.class));
			setFieldValue(catalog, "dataStoreBuffer", failingWrite);
			try (final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG)) {
				final EntityTypeAlreadyPresentInCatalogSchemaException failure = assertThrows(
					EntityTypeAlreadyPresentInCatalogSchemaException.class, () -> session.updateCatalogSchema(
						new ModifyCatalogSchemaDescriptionMutation("Failed batch"),
						new CreateEntitySchemaMutation("Original")
					));
				assertEquals(1, failure.getSuppressed().length);
				assertInstanceOf(UncheckedIOException.class, failure.getSuppressed()[0]);
				assertNull(session.getCatalogSchema().getDescription());
			} finally {
				setFieldValue(catalog, "dataStoreBuffer", original);
			}
		}

		/**
		 * The cascade of a global attribute removal writes the catalog schema part of the batch so far - the analyzer
		 * the batch declared included - before a later mutation fails. When the write of the restored schema fails as
		 * well, that part is the newest one in the catalog file, and no probe has validated it: every route validates
		 * the restored schema held in memory. Whichever route publishes next must store the schema it validated.
		 * @param route the publication after the failed batch: the session close, the session go-live, or the
		 *              catalog termination after a failed session drain
		 * @param compileInvalid whether the batch's analyzer fails compilation instead of a behavior rule
		 * @param failRestoreWrite whether the write of the restored schema fails, once
		 */
		@ParameterizedTest(name = "{0}, compile-invalid={1}, fail-restore-write={2}")
		@CsvSource({
			"close,false,false", "close,false,true", "close,true,true", "goLive,false,true", "goLive,true,true",
			"termination,false,true", "termination,true,true"
		})
		@DisplayName("Publication after a failed batch stores the validated schema, not the part its cascade wrote")
		void shouldPublishValidatedSchemaAfterFailedBatchCascade(
			@Nonnull String route, boolean compileInvalid, boolean failRestoreWrite
		) {
			prepareCollections();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite().withAttribute("code", String.class).updateVia(session);
				session.getEntitySchemaOrThrowException("Original").openForWrite().withGlobalAttribute("code")
					.updateVia(session);
			});
			final Catalog catalog = catalog();
			final DataStoreMemoryBuffer original = failRestoredSchemaWrite(catalog, failRestoreWrite);
			final Consumer<FulltextAnalyzerSchemaEditor> analyzer = compileInvalid ?
				a -> a.words(asciiFolding(), czechStemming()) : a -> a.words().samples("Upper");
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			try {
				final LocalCatalogSchemaMutation[] batch = ArrayUtils.mergeArrays(
					session.getCatalogSchema().openForWrite().withFulltextAnalyzer("plain", analyzer)
						.toMutation().orElseThrow().getSchemaMutations(),
					new LocalCatalogSchemaMutation[]{
						new RemoveAttributeSchemaMutation("code"), new CreateEntitySchemaMutation("Original")
					}
				);
				final EntityTypeAlreadyPresentInCatalogSchemaException failure = assertThrows(
					EntityTypeAlreadyPresentInCatalogSchemaException.class, () -> session.updateCatalogSchema(batch));
				assertEquals(failRestoreWrite ? 1 : 0, failure.getSuppressed().length);
				assertTrue(session.getCatalogSchema().getAttribute("code").isPresent());
			} finally {
				setFieldValue(catalog, "dataStoreBuffer", original);
			}
			// unpublished work of the same writer: the stored catalog holds it only when the route did publish
			session.upsertEntity(session.createNewEntity("Removable", 1));
			switch (route) {
				case "close" -> session.close();
				case "goLive" -> session.goLiveAndClose();
				case "termination" -> failSessionDrain();
				default -> throw new IllegalArgumentException(route);
			}
			reopen();
			assertEquals("goLive".equals(route) ? CatalogState.ALIVE : CatalogState.WARMING_UP,
				engine.getCatalogState(TEST_CATALOG).orElseThrow());
			assertTrue(catalog().getSchema().getFulltextAnalyzer("plain").isEmpty());
			assertTrue(catalog().getSchema().getAttribute("code").isPresent());
			assertTrue(
				engine.queryCatalog(TEST_CATALOG, reader -> {
					return reader.getEntity("Removable", 1);
				}).isPresent(),
				"the route published nothing, so the stored schema proves nothing about it"
			);
		}

		/**
		 * The write of the restored schema can fail with an {@link Error} as well as with an exception. The part the
		 * batch's cascade wrote is then the newest one in the catalog file just the same, so the close must store the
		 * restored schema it validated instead of publishing that part.
		 */
		@Test
		@DisplayName("Close stores the validated schema when the write of the restored schema throws an error")
		void shouldPublishValidatedSchemaWhenRestoredSchemaWriteThrowsError() {
			prepareCollections();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite().withAttribute("code", String.class).updateVia(session);
				session.getEntitySchemaOrThrowException("Original").openForWrite().withGlobalAttribute("code")
					.updateVia(session);
			});
			final Catalog catalog = catalog();
			final DataStoreMemoryBuffer original =
				failRestoredSchemaWrite(catalog, new AtomicInteger(1), SimulatedWriteError::new);
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			try {
				final LocalCatalogSchemaMutation[] batch = ArrayUtils.mergeArrays(
					session.getCatalogSchema().openForWrite()
						.withFulltextAnalyzer("plain", a -> a.words().samples("Upper"))
						.toMutation().orElseThrow().getSchemaMutations(),
					new LocalCatalogSchemaMutation[]{
						new RemoveAttributeSchemaMutation("code"), new CreateEntitySchemaMutation("Original")
					}
				);
				final Throwable failure = assertThrows(Throwable.class, () -> session.updateCatalogSchema(batch));
				assertTrue(
					Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
						.anyMatch(SimulatedWriteError.class::isInstance),
					() -> "the batch failed for another reason: " + failure
				);
				assertTrue(session.getCatalogSchema().getAttribute("code").isPresent());
			} finally {
				setFieldValue(catalog, "dataStoreBuffer", original);
			}
			// unpublished work of the same writer: the stored catalog holds it only when the close did publish
			session.upsertEntity(session.createNewEntity("Removable", 1));
			session.close();
			reopen();
			assertEquals(CatalogState.WARMING_UP, engine.getCatalogState(TEST_CATALOG).orElseThrow());
			assertTrue(catalog().getSchema().getFulltextAnalyzer("plain").isEmpty());
			assertTrue(catalog().getSchema().getAttribute("code").isPresent());
			assertTrue(
				engine.queryCatalog(TEST_CATALOG, reader -> {
					return reader.getEntity("Removable", 1);
				}).isPresent(),
				"the close published nothing, so the stored schema proves nothing about it"
			);
		}

		/**
		 * Termination writes the restored schema once more before it publishes. When that write fails as well, the
		 * newest catalog schema part is still the one the batch's cascade wrote, so termination must keep the last
		 * published state rather than publish that part.
		 */
		@Test
		@DisplayName("Termination publishes nothing when the write of the restored schema fails again")
		void shouldPublishNothingWhenRestoredSchemaWriteFailsAgainAtTermination() {
			prepareCollections();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite().withAttribute("code", String.class).updateVia(session);
				session.getEntitySchemaOrThrowException("Original").openForWrite().withGlobalAttribute("code")
					.updateVia(session);
			});
			final AtomicInteger failures = new AtomicInteger(2);
			// the failing buffer stays installed, so that the write termination repeats fails too
			failRestoredSchemaWrite(catalog(), failures,
				() -> new UncheckedIOException(new IOException("Simulated storage write failure")));
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			final LocalCatalogSchemaMutation[] batch = ArrayUtils.mergeArrays(
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("plain", a -> a.words().samples("Upper"))
					.toMutation().orElseThrow().getSchemaMutations(),
				new LocalCatalogSchemaMutation[]{
					new RemoveAttributeSchemaMutation("code"), new CreateEntitySchemaMutation("Original")
				}
			);
			final EntityTypeAlreadyPresentInCatalogSchemaException failure = assertThrows(
				EntityTypeAlreadyPresentInCatalogSchemaException.class, () -> session.updateCatalogSchema(batch));
			assertEquals(1, failure.getSuppressed().length);
			// unpublished work of the same writer, so that termination has a change to publish
			session.upsertEntity(session.createNewEntity("Removable", 1));
			failSessionDrain();
			reopen();
			assertEquals(0, failures.get(), "termination did not write the restored schema again");
			assertEquals(CatalogState.WARMING_UP, engine.getCatalogState(TEST_CATALOG).orElseThrow());
			assertTrue(catalog().getSchema().getFulltextAnalyzer("plain").isEmpty());
			assertTrue(catalog().getSchema().getAttribute("code").isPresent());
			assertTrue(
				engine.queryCatalog(TEST_CATALOG, reader -> {
					return reader.getEntity("Removable", 1);
				}).isEmpty(),
				"termination published although the write of the restored schema failed"
			);
		}

		/** A later successful schema write supersedes the failed batch's part, so no repair write stays pending. */
		@Test
		@DisplayName("A successful schema change after a failed restored-schema write leaves no rewrite pending")
		void shouldNotRewriteSchemaAfterLaterSuccessfulChange() {
			prepareCollections();
			final Catalog catalog = catalog();
			final DataStoreMemoryBuffer original = failRestoredSchemaWrite(catalog, true);
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			try {
				final EntityTypeAlreadyPresentInCatalogSchemaException failure = assertThrows(
					EntityTypeAlreadyPresentInCatalogSchemaException.class, () -> session.updateCatalogSchema(
						new ModifyCatalogSchemaDescriptionMutation("Failed"),
						new CreateEntitySchemaMutation("Original")
					));
				assertEquals(1, failure.getSuppressed().length);
				session.updateCatalogSchema(new ModifyCatalogSchemaDescriptionMutation("Later"));
			} finally {
				setFieldValue(catalog, "dataStoreBuffer", original);
			}
			final DataStoreMemoryBuffer observed = spy(original);
			setFieldValue(catalog, "dataStoreBuffer", observed);
			session.close();
			verify(observed, never()).update(anyLong(), any(CatalogSchemaStoragePart.class));
			reopen();
			assertEquals("Later", catalog().getSchema().getDescription());
		}

		/** The failed batch's prefix published mid-call is superseded even when the restored schema's write fails. */
		@Test
		@DisplayName("Close stores the restored schema after a mid-call publication when its own write failed")
		void shouldPublishRestoredSchemaAfterPartlyPublishedBatchWhenItsWriteFailed() {
			prepareCollections();
			final Catalog catalog = catalog();
			final DataStoreMemoryBuffer original = failRestoredSchemaWrite(catalog, true);
			try (final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG)) {
				final EntityTypeAlreadyPresentInCatalogSchemaException failure = assertThrows(
					EntityTypeAlreadyPresentInCatalogSchemaException.class, () -> session.updateCatalogSchema(
						new ModifyCatalogSchemaDescriptionMutation("Published prefix"),
						new CreateEntitySchemaMutation("Created"),
						new CreateEntitySchemaMutation("Original")
					));
				assertEquals(1, failure.getSuppressed().length);
				assertNull(session.getCatalogSchema().getDescription());
			} finally {
				setFieldValue(catalog, "dataStoreBuffer", original);
			}
			reopen();
			assertNull(catalog().getSchema().getDescription());
			assertEquals(Set.of("Original", "Removable", "Created"), catalog().getEntityTypes());
		}

		/** Fixing an intermediate failure permits all pending collection changes to publish together. */
		@Test
		@DisplayName("Repair publishes the held-back collections and final analyzer on close")
		void shouldPublishHeldBackCollectionsAfterRepair() throws IOException {
			prepareCollections();
			try (final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG)) {
				declareFailingAnalyzer(session);
				mutateCollections(session);
				assertStorageSnapshotStillOriginal();
				session.getCatalogSchema().openForWrite().withFulltextAnalyzer("plain",
					a -> a.words(lowercase()).samples("Upper")).updateVia(session);
			}
			reopen();
			assertEquals(List.of("Upper"),
				catalog().getSchema().getFulltextAnalyzer("plain").orElseThrow().getSamples());
			assertEquals(Set.of("Created", "Renamed"), catalog().getEntityTypes());
		}

		/** Rejected and accepted verdicts can be reused when successive DDLs leave analysis inputs unchanged. */
		@Test
		@DisplayName("Unrelated collection DDL reuses behavior verdicts without reanalyzing samples")
		void shouldCacheWarmUpAnalysisAcrossUnrelatedDdl() {
			final AtomicInteger sampleAnalyses = new AtomicInteger();
			final FulltextAnalyzerRegistry registry = spy(catalog().getFulltextAnalyzerRegistry());
			doAnswer(invocation -> {
				final CompiledFulltextAnalyzer real = (CompiledFulltextAnalyzer) invocation.callRealMethod();
				final FulltextAnalyzer index = spy(real.indexSurface());
				doAnswer(analysis -> {
					sampleAnalyses.incrementAndGet();
					return analysis.callRealMethod();
				}).when(index).analyzeIndexed(anyString(), any(IndexedTermConsumer.class));
				return withObservedIndex(real, index);
			}).when(registry).getCompiledAnalyzer(any(), eq("plain"), eq(Locale.ROOT));
			setFieldValue(catalog(), "fulltextAnalyzerRegistry", registry);
			try (final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG)) {
				declareFailingAnalyzer(session);
				session.defineEntitySchema("First").updateVia(session);
				assertEquals(1, sampleAnalyses.get());
				session.defineEntitySchema("Second").updateVia(session);
				assertEquals(1, sampleAnalyses.get());
				session.getCatalogSchema().openForWrite().withFulltextAnalyzer("plain",
					a -> a.words(lowercase()).samples("Upper")).updateVia(session);
				session.defineEntitySchema("Third").updateVia(session);
				assertEquals(2, sampleAnalyses.get());
				session.defineEntitySchema("Fourth").updateVia(session);
				assertEquals(2, sampleAnalyses.get());
			}
			assertEquals(2, sampleAnalyses.get(), "Final validation reused the last accepted verdict");
		}

		/** A failing warm-up close must refuse publication and still terminate its session. */
		@Test
		@DisplayName("Warm-up close refuses publication and closes resources")
		void shouldRefuseWarmUpPublication() {
			final Catalog catalog = catalog();
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			session.getCatalogSchema().openForWrite().withFulltextAnalyzer("plain",
				a -> a.words().samples("Upper")).updateVia(session);
			assertThrows(InvalidSchemaMutationException.class, session::close);
			assertFalse(session.isActive());
			assertFalse(catalog.isPublishable());
			reopen();
			assertTrue(catalog().getSchema().getFulltextAnalyzer("plain").isEmpty());
		}

		/** Session-driven go-live bypasses ordinary close, but must use the same session-start baseline. */
		@Test
		@DisplayName("Session go-live refuses a touched failing analyzer and releases the session")
		void shouldRefuseGoLivePublication() {
			final Catalog catalog = catalog();
			final EvitaSessionContract session = engine.createReadWriteSession(TEST_CATALOG);
			session.getCatalogSchema().openForWrite().withFulltextAnalyzer("plain",
				a -> a.words().samples("Upper")).updateVia(session);
			assertThrows(InvalidSchemaMutationException.class, session::goLiveAndClose);
			assertFalse(session.isActive());
			assertFalse(catalog.isPublishable());
			reopen();
			assertTrue(catalog().getSchema().getFulltextAnalyzer("plain").isEmpty());
		}

		/**
		 * No fulltext declarations means the final-validation entry point must never invoke the behavior check. The
		 * static observer sees only the calling thread, so a catalog with an analyzer, closed and taken live the same
		 * way, shows that the close and the session go-live are observed at all.
		 */
		@Test
		@DisplayName("A catalog without fulltext configuration never invokes the behavior check")
		void shouldAvoidUnconfiguredCatalog() {
			final String configured = "configuredCatalog";
			engine.defineCatalog(configured);
			try (final MockedStatic<FulltextAnalyzerBehaviourCheck> observer =
				mockStatic(FulltextAnalyzerBehaviourCheck.class, CALLS_REAL_METHODS)) {
				engine.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema("Item").withAttribute("code", String.class).updateVia(session);
				});
				goLive();
				observer.verifyNoInteractions();

				engine.updateCatalog(configured, session -> {
					session.getCatalogSchema().openForWrite()
						.withFulltextAnalyzer("plain", a -> a.words(lowercase())).updateVia(session);
					session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
						.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("plain"))
						.updateVia(session);
				});
				observer.verify(
					() -> FulltextAnalyzerBehaviourCheck.validate(any(), any(), any(), any()), atLeastOnce());
				observer.clearInvocations();
				engine.updateCatalog(configured, session -> { session.goLiveAndClose(); });
				observer.verify(
					() -> FulltextAnalyzerBehaviourCheck.validate(any(), any(), any(), any()), atLeastOnce());
			}
		}

		/** The fixture bypasses final validation only; actual load and queries must work without a behavior verdict. */
		@Test
		@DisplayName("A legacy failing analyzer reloads and serves queries")
		void shouldLoadFailingAnalyzer() {
			goLive();
			legacyAnalyzer();
			reopen();
			assertLegacyQuery();
		}

		/** Direct engine go-live has no authoring session and must not recheck old untouched failures. */
		@Test
		@DisplayName("Direct engine go-live uses its pre-drain schema as the baseline")
		void shouldAllowUntouchedLegacyAnalyzerAtDirectGoLive() {
			legacyAnalyzer();
			engine.makeCatalogAlive(TEST_CATALOG);
			assertLegacyQuery();
		}

		/** A live storage copy with a proven checkpoint gap requires WAL replay to recover the failing declaration. */
		@Test
		@Tag(WAL)
		@DisplayName("Crash-style recovery replays a failing analyzer without behavior validation")
		void shouldReplayFailingAnalyzer() throws IOException {
			goLive();
			legacyAnalyzer();
			assertTrue(catalog().getVersion() > catalog().getPersistenceService().getLastCatalogVersion());
			final TestPaths recovery = createTestPaths("FulltextBehaviourRecovery");
			try {
				try (final Stream<Path> files = Files.walk(paths.storage())) {
					for (final Path path : files.toList()) {
						final Path destination = recovery.storage().resolve(paths.storage().relativize(path));
						if (Files.isDirectory(path)) {
							Files.createDirectories(destination);
						} else {
							Files.createDirectories(destination.getParent());
							Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
						}
					}
				}
				try (final Evita recovered = new Evita(configuration(recovery))) {
					recovered.waitUntilFullyInitialized();
					assertEquals(List.of("Étage"), recovered.queryCatalog(TEST_CATALOG,
						session -> {
							return session.getCatalogSchema().getFulltextAnalyzer("legacy").orElseThrow().getSamples();
						}));
					final List<EntityReference> found = recovered.queryCatalog(TEST_CATALOG, session -> {
						return session.queryList(query(collection("Item"),
							filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("cup"))), EntityReference.class);
					});
					assertEquals(1, found.size());
				}
			} finally {
				cleanupTestPaths(recovery);
			}
		}
	}

	/** Existing failures remain irrelevant until a dependency or the analyzer itself is touched. */
	@Nested
	@DisplayName("Touched analyzers")
	class Touched {
		/** New fields, another analyzer and unused resource content cannot make an old failing analyzer block work. */
		@Test
		@DisplayName("Unrelated schema changes pass with a legacy failing analyzer")
		void shouldAllowUnrelatedChanges() {
			goLive();
			legacyAnalyzer();
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema("Item").withAttribute("extra", String.class).updateVia(session);
			});
			change(b -> b.withFulltextAnalyzer("other", a -> a.words(lowercase()).samples("Cup")));
			change(b -> b.withFulltextResource("unused", wordList("blue")));
			assertLegacyQuery();
		}

		/** Each change of samples, definition, used resources or used splitting touches the legacy analyzer. */
		@Test
		@DisplayName("Every used dependency and own declaration is compared with the session-start schema")
		void shouldRefuseTouchedLegacyAnalyzer() {
			goLive();
			legacyAnalyzer();
			assertRefused(b -> b.withFulltextAnalyzer("legacy", a -> a.words(stopWords(resource("used")),
				synonyms(resource("queryUsed"))).samples("Étage", "Cup")), "legacy", "rule 1");
			assertRefused(b -> b.withFulltextAnalyzer("legacy", a -> a.words(englishPossessive(),
				stopWords(resource("used")), synonyms(resource("queryUsed"))).samples("Étage")), "legacy", "rule 1");
			assertRefused(b -> b.withFulltextResource("used", wordList("and", "or")), "legacy", "rule 1");
			assertRefused(b -> b.withFulltextResource("queryUsed", synonymList(synonymGroup("cup", "chair"))),
				"legacy", "rule 1");
			assertRefused(b -> b.withFulltextLanguage("en", l -> l.wordNumberSplit()), "legacy", "language `en`",
				"rule 1");
		}

		/** A resolved base change touches a derived analyzer, whose own declaration supplies its samples. */
		@Test
		@DisplayName("Inherited definitions touch derived analyzers without inheriting samples")
		void shouldCompareInheritedDefinitions() {
			goLive();
			change(b -> b.withFulltextAnalyzer("base", a -> a.words(lowercase()).samples("Cup"))
				.withFulltextAnalyzer("derived", a -> a.basedOn("base").samples("Upper")));
			assertRefused(b -> b.withFulltextAnalyzer("base", a -> a.words().samples()),
				"derived", "Upper", "rule 1");
		}
	}

	/** Bounds are measured by chain invocations, independently of wall-clock and compiler resource preparation. */
	@Nested
	@DisplayName("Resource bounds")
	class Bounds {
		/** A bad 501st stop entry is admissible; exactly 500 preceding entries are actually analyzed. */
		@Test
		@DisplayName("Checks only the first 500 stop entries")
		void shouldBoundStopEntries() {
			goLive();
			final FulltextAnalyzer[] observed = new FulltextAnalyzer[1];
			observeIndexCalls(observed);
			final String[] words = IntStream.range(0, 501).mapToObj(i -> i == 500 ? "running rooms" : "and")
				.toArray(String[]::new);
			changeForField("bounded", b -> b.withFulltextResource("stops", wordList(words))
				.withFulltextAnalyzer("bounded", a -> a.words(lowercase(), englishStemming(),
					stopWords(resource("stops")))));
			verify(observed[0], times(500)).analyzeIndexed(anyString(), any(IndexedTermConsumer.class));
			verify(observed[0], never()).analyzeIndexed(eq("running rooms"), any(IndexedTermConsumer.class));
		}

		/** The comparison chain is compiled once and reused for the first 500 words; the 501st would fail rule 3. */
		@Test
		@DisplayName("Checks only the first 500 protected entries")
		void shouldBoundProtectedEntries() {
			goLive();
			final FulltextAnalyzer[] observed = new FulltextAnalyzer[1];
			observeIndexCalls(observed);
			final String[] words = IntStream.range(0, 501).mapToObj(i -> i == 500 ? "runn&#105;ng" : "running")
				.toArray(String[]::new);
			changeForField("bounded", b -> b.withFulltextResource("protected", wordList(words))
				.withFulltextAnalyzer("bounded",
				a -> a.text(htmlStrip()).words(lowercase(), englishStemming(protectedWords(resource("protected"))))));
			verify(observed[0], times(500)).analyzeIndexed(anyString(), any(IndexedTermConsumer.class));
			verify(observed[0], never()).analyzeIndexed(eq("runn&#105;ng"), any(IndexedTermConsumer.class));
		}

		/** The ninth member and 101st group would fail, but only 100 groups times 56 ordered pairs are evaluated. */
		@Test
		@DisplayName("Checks only 100 synonym groups and eight members each")
		void shouldBoundSynonymEntries() {
			goLive();
			final FulltextAnalyzer[] observed = new FulltextAnalyzer[1];
			observeIndexCalls(observed);
			final SynonymGroup[] groups = IntStream.range(0, 101)
				.mapToObj(i -> i == 100 ? synonymGroup("&eacute;", "walking") :
					synonymGroup("cup", "mug", "chair", "table", "door", "window", "blue", "green", "&eacute;"))
				.toArray(SynonymGroup[]::new);
			changeForField("bounded", b -> b.withFulltextResource("equivalents", synonymList(groups))
				.withFulltextAnalyzer("bounded",
				a -> a.text(htmlStrip()).words(lowercase(), synonyms(resource("equivalents")))));
			verify(observed[0], times(5_600)).analyzeIndexed(anyString(), any(IndexedTermConsumer.class));
			verify(observed[0], never()).analyzeIndexed(eq("&eacute;"), any(IndexedTermConsumer.class));
		}
	}

	/**
	 * Observes real cached index-chain calls without replacing its analysis or any session/catalog behavior.
	 * The spy shares the catalog's real compiled chains and lifecycle; compilation's internal resource preparation
	 * never calls this wrapper, so only sample-check work is counted.
	 * @param observed receives the actual index wrapper spy used by the final check
	 */
	private void observeIndexCalls(@Nonnull FulltextAnalyzer[] observed) {
		final FulltextAnalyzerRegistry registry = spy(catalog().getFulltextAnalyzerRegistry());
		doAnswer(invocation -> {
			final CompiledFulltextAnalyzer real = (CompiledFulltextAnalyzer) invocation.callRealMethod();
			observed[0] = spy(real.indexSurface());
			return withObservedIndex(real, observed[0]);
		}).when(registry).getCompiledAnalyzer(any(), eq("bounded"), eq(Locale.FRENCH));
		setFieldValue(catalog(), "fulltextAnalyzerRegistry", registry);
	}

	/**
	 * Substitutes an observer for the production index chain while retaining the real query and split chains.
	 * @param real compiled chains owned by the catalog
	 * @param observed index chain forwarding operations to the original
	 * @return compiled chains with the observed index entry point
	 */
	@Nonnull
	private static CompiledFulltextAnalyzer withObservedIndex(
		@Nonnull CompiledFulltextAnalyzer real, @Nonnull FulltextAnalyzer observed
	) {
		return new CompiledFulltextAnalyzer(real.word(), real.splitting(), real.queryText(), real.textSplitting(),
			real.textSplitWord(), observed, real.surface(), real.textSplitSurface(), real.fragment(),
			real.textSplitFragment(), real.fingerprint(), real.foldedStopWords(), real.stopWordPreparations());
	}

	/**
	 * Declares resource rules and binds them to a real field in the same session. French has no built-in samples.
	 * @param analyzer analyzer named by the field
	 * @param editor catalog mutations creating the analyzer and resources
	 */
	private void changeForField(@Nonnull String analyzer, @Nonnull Consumer<CatalogSchemaBuilder> editor) {
		changeForField(analyzer, Locale.FRENCH, editor);
	}

	/**
	 * Declares resource rules and binds them to a real localized field in the same session.
	 * @param analyzer analyzer named by the field
	 * @param locale the collection's only locale, which selects the built-in samples that apply
	 * @param editor catalog mutations creating the analyzer and resources
	 */
	private void changeForField(
		@Nonnull String analyzer, @Nonnull Locale locale, @Nonnull Consumer<CatalogSchemaBuilder> editor
	) {
		this.engine.updateCatalog(TEST_CATALOG, session -> {
			final CatalogSchemaBuilder builder = session.getCatalogSchema().openForWrite();
			editor.accept(builder);
			builder.updateVia(session);
			session.defineEntitySchema("Item").withLocale(locale)
				.withAttribute("name", String.class, a -> a.localized().searchable().analyzer(analyzer))
				.updateVia(session);
		});
	}

	/**
	 * Binds an existing analyzer to a localized searchable attribute of a reference in an English collection.
	 * @param analyzer analyzer named by the reference attribute
	 */
	private void attachToReferenceAttribute(@Nonnull String analyzer) {
		this.engine.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
				.withReferenceTo("tags", "Tag", Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering()
					.withAttribute("label", String.class,
						a -> a.localized().nullable().searchable().analyzer(analyzer)))
				.updateVia(session);
		});
	}

	/**
	 * Verifies that rules 2 through 4 refuse a definition used by a field.
	 * @param analyzer analyzer named by the field
	 * @param editor catalog mutations creating the analyzer and resources
	 * @param fragments expected diagnostic fragments
	 * @return the failure reported by final validation
	 */
	@Nonnull
	private InvalidSchemaMutationException assertRefusedForField(
		@Nonnull String analyzer, @Nonnull Consumer<CatalogSchemaBuilder> editor, @Nonnull String... fragments
	) {
		return assertRefusal(() -> changeForField(analyzer, editor), fragments);
	}

	/** Publishes two existing collections so rename and removal both address real stored files. */
	private void prepareCollections() {
		this.engine.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Original").updateVia(session);
			session.defineEntitySchema("Removable").updateVia(session);
		});
	}

	/** Publishes a French field whose untouched Czech override would fail the Czech sample list. */
	private void prepareLocaleSensitiveAnalyzer() {
		this.engine.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema().openForWrite().withFulltextAnalyzer("localeSensitive",
				a -> a.words(lowercase()).language("cs", l -> l.words())).updateVia(session);
			session.defineEntitySchema("Item").withLocale(Locale.FRENCH)
				.verifySchemaButAllow(EvolutionMode.ADDING_LOCALES)
				.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("localeSensitive"))
				.updateVia(session);
		});
	}

	/**
	 * Runs an action while collecting the warnings the catalog logs, from whichever thread logs them.
	 * @param action work expected to make the catalog warn
	 * @return the WARN events the catalog logged while the action ran
	 */
	@Nonnull
	private static List<ILoggingEvent> captureCatalogWarnings(@Nonnull Runnable action) {
		final Logger logger = (Logger) LoggerFactory.getLogger(Catalog.class);
		final ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		logger.addAppender(events);
		try {
			action.run();
		} finally {
			logger.detachAppender(events);
			events.stop();
		}
		return events.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
	}

	/**
	 * Replaces the catalog's data store buffer with a spy whose first write of the catalog schema at the version the
	 * catalog holds now fails, as a storage write does. That is the write of the schema a failed batch restores; the
	 * writes of the batch's own parts and every later write, a retry of the same one included, reach the real buffer.
	 * @param catalog the warm-up catalog whose buffer is replaced
	 * @param fail whether the write fails at all, so that a control takes the same path without the failure
	 * @return the replaced buffer, for the caller to put back
	 */
	@Nonnull
	private static DataStoreMemoryBuffer failRestoredSchemaWrite(@Nonnull Catalog catalog, boolean fail) {
		return failRestoredSchemaWrite(catalog, new AtomicInteger(fail ? 1 : 0),
			() -> new UncheckedIOException(new IOException("Simulated storage write failure")));
	}

	/**
	 * Replaces the catalog's data store buffer with a spy whose writes of the catalog schema at the version the catalog
	 * holds now fail while failures remain. The first such write is the one of the schema a failed batch restores, the
	 * next one the write of the same schema a publication repeats; the writes of the batch's own parts and every write
	 * after the last failure reach the real buffer.
	 * @param catalog the warm-up catalog whose buffer is replaced
	 * @param failures how many of those writes fail, counted down as they do; zero for a control without the failure
	 * @param failure creates what each failing write throws
	 * @return the replaced buffer, for the caller to put back
	 */
	@Nonnull
	private static DataStoreMemoryBuffer failRestoredSchemaWrite(
		@Nonnull Catalog catalog, @Nonnull AtomicInteger failures, @Nonnull Supplier<? extends Throwable> failure
	) {
		final DataStoreMemoryBuffer original = getNonnullFieldValue(catalog, "dataStoreBuffer");
		final int restoredVersion = catalog.getSchema().version();
		final DataStoreMemoryBuffer failingWrite = spy(original);
		doAnswer(invocation -> {
			final CatalogSchemaStoragePart part = invocation.getArgument(1);
			if (part.catalogSchema().version() == restoredVersion &&
				failures.getAndUpdate(remaining -> Math.max(remaining - 1, 0)) > 0) {
				throw failure.get();
			}
			return invocation.callRealMethod();
		}).when(failingWrite).update(anyLong(), any(CatalogSchemaStoragePart.class));
		setFieldValue(catalog, "dataStoreBuffer", failingWrite);
		return original;
	}

	/** Injects the failure at the drain boundary while leaving shutdown, the writer and storage real. */
	private void failSessionDrain() {
		final Map<String, SessionRegistry> registries = getNonnullFieldValue(this.engine, "catalogSessionRegistries");
		final SessionRegistry observed = spy(registries.get(TEST_CATALOG));
		doThrow(new IllegalStateException("Simulated warm-up drain failure"))
			.when(observed).closeAllActiveSessionsAndSuspend(any());
		registries.put(TEST_CATALOG, observed);
	}

	/**
	 * Declares an analyzer that compiles but fails its mixed-case sample.
	 * @param session the active warm-up writer
	 */
	private static void declareFailingAnalyzer(@Nonnull EvitaSessionContract session) {
		session.getCatalogSchema().openForWrite().withFulltextAnalyzer("plain",
			a -> a.words().samples("Upper")).updateVia(session);
	}

	/**
	 * Exercises the three collection-level publication routes with live catalog APIs.
	 * @param session the active warm-up writer carrying the analyzer edit
	 */
	private static void mutateCollections(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema("Created").updateVia(session);
		assertTrue(session.renameCollection("Original", "Renamed"));
		assertTrue(session.deleteCollection("Removable"));
	}

	/** Verifies the activated catalog recovered its last accepted analyzer and collection declarations. */
	private void assertOriginalPublication() {
		assertTrue(catalog().getSchema().getFulltextAnalyzer("plain").isEmpty());
		assertEquals(Set.of("Original", "Removable"), catalog().getEntityTypes());
	}

	/**
	 * Opens a disk copy while the writer is still active to distinguish pending state from publication.
	 * @throws IOException when the catalog files cannot be copied
	 */
	private void assertStorageSnapshotStillOriginal() throws IOException {
		assertStorageSnapshot(Set.of("Original", "Removable"), null);
	}

	/**
	 * Reloads the published schema and collection headers independently of the live session.
	 * @param collections expected collection names
	 * @param description expected catalog description, if specified
	 * @throws IOException when the catalog files cannot be copied
	 */
	private void assertStorageSnapshot(@Nonnull Set<String> collections, @Nullable String description)
		throws IOException {
		readStorageSnapshot(copy -> {
			final var published = copy.getCatalogInstanceOrThrowException(TEST_CATALOG);
			assertTrue(published.getSchema().getFulltextAnalyzer("plain").isEmpty());
			assertEquals(collections, published.getEntityTypes());
			assertEquals(description, published.getSchema().getDescription());
			return null;
		});
	}

	/**
	 * Opens a copy of the storage in an engine of its own, which reads what was published independently of the live
	 * engine, its sessions and any instance they still hold.
	 * @param reader what to read from the copy's engine once it has loaded the catalogs
	 * @return what the reader returned
	 * @throws IOException when the catalog files cannot be copied
	 */
	@Nullable
	private <T> T readStorageSnapshot(@Nonnull Function<Evita, T> reader) throws IOException {
		final TestPaths snapshot = createTestPaths("AnalyzerWarmUpPublishedSnapshot");
		try {
			try (final Stream<Path> files = Files.walk(this.paths.storage())) {
				for (final Path path : files.toList()) {
					final Path destination = snapshot.storage().resolve(this.paths.storage().relativize(path));
					if (Files.isDirectory(path)) {
						Files.createDirectories(destination);
					} else {
						Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			try (final Evita copy = new Evita(configuration(snapshot))) {
				copy.waitUntilFullyInitialized();
				return reader.apply(copy);
			}
		} finally {
			cleanupTestPaths(snapshot);
		}
	}

	/**
	 * Opens a writer over a published `Item` collection whose `name` field uses the valid analyzer `plain`, and gives
	 * it unpublished work - one item - so that a termination finding it registered has something to publish.
	 * @return the open warm-up writer
	 */
	@Nonnull
	private EvitaSessionContract openWriterWithUnpublishedItem() {
		this.engine.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema().openForWrite()
				.withFulltextAnalyzer("plain", a -> a.words(lowercase()).samples("Upper")).updateVia(session);
			session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
				.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("plain"))
				.updateVia(session);
		});
		final EvitaSessionContract session = this.engine.createReadWriteSession(TEST_CATALOG);
		session.upsertEntity(session.createNewEntity("Item", 1).setAttribute("name", Locale.ENGLISH, "Upper"));
		return session;
	}

	/**
	 * Builds a schema change the registered warm-up writer starts on another thread while a termination is under way.
	 * The failing change declares an analyzer whose mixed-case sample fails the behaviour check; the valid one declares
	 * an analyzer and a field that uses it, so that the catalog schema part and a collection header would change
	 * together. The returned action starts the call and returns once the call waits on the catalog's warm-up call lock,
	 * which the terminating thread holds.
	 * @param session the registered warm-up writer
	 * @param catalog the catalog being terminated
	 * @param failing whether the change fails the behaviour check
	 * @param writerCall receives the started call
	 * @return the action; every run after the first does nothing
	 */
	@Nonnull
	private static Runnable writerChange(
		@Nonnull EvitaSessionContract session, @Nonnull Catalog catalog, boolean failing,
		@Nonnull AtomicReference<CompletableFuture<Void>> writerCall
	) {
		final ReentrantLock callLock = Objects.requireNonNull(catalog.getWarmUpCallLock());
		// the builders are prepared here, before the termination starts, so that the first call the writer makes
		// while the termination runs is the schema mutation itself and not a schema read
		final Runnable change;
		if (failing) {
			final CatalogSchemaBuilder catalogChange = session.getCatalogSchema().openForWrite()
				.withFulltextAnalyzer("failing", a -> a.words().samples("Upper"));
			change = () -> catalogChange.updateVia(session);
		} else {
			final CatalogSchemaBuilder catalogChange = session.getCatalogSchema().openForWrite()
				.withFulltextAnalyzer("second", a -> a.words(lowercase()).samples("Second"));
			final EntitySchemaBuilder itemChange = session.getEntitySchemaOrThrowException("Item").openForWrite()
				.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("second"));
			change = () -> {
				catalogChange.updateVia(session);
				itemChange.updateVia(session);
			};
		}
		final AtomicBoolean pending = new AtomicBoolean(true);
		return () -> {
			if (!pending.compareAndSet(true, false)) {
				return;
			}
			writerCall.set(CompletableFuture.runAsync(change));
			// a positive wait: the call reaches the lock in milliseconds, the bound only stops a hang. A lock offers
			// no latch for "a thread has queued", so its queue is observed instead
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while (!callLock.hasQueuedThreads() && System.nanoTime() < deadline) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
			}
			assertTrue(callLock.hasQueuedThreads(), "the writer's call does not wait for the termination");
		};
	}

	/**
	 * Waits until the writer's call that waited for the termination has ended. The call resumes on a terminated
	 * instance, so whether it returns or throws is not this test's subject - only that it ends.
	 * @param writerCall the call started by {@link #writerChange}
	 */
	private static void awaitWriterCall(@Nonnull AtomicReference<CompletableFuture<Void>> writerCall) {
		final CompletableFuture<Void> call =
			Objects.requireNonNull(writerCall.get(), "the writer's call never started");
		try {
			call.handle((result, failure) -> null).get(30, TimeUnit.SECONDS);
		} catch (Exception ex) {
			throw new AssertionError("the writer's call did not end after the termination", ex);
		}
	}

	/**
	 * Reads what a termination published while the writer changed the schema.
	 * @return the state a copy of the storage loads
	 * @throws IOException when the catalog files cannot be copied
	 */
	@Nonnull
	private PublishedState readPublishedState() throws IOException {
		return Objects.requireNonNull(readStorageSnapshot(copy -> {
			final CatalogContract published = copy.getCatalogInstanceOrThrowException(TEST_CATALOG);
			return new PublishedState(
				copy.getCatalogState(TEST_CATALOG).orElseThrow(),
				published.getSchema().getFulltextAnalyzer("failing").isPresent(),
				published.getSchema().getFulltextAnalyzer("second").isPresent(),
				published.getEntitySchema("Item").orElseThrow().getAttribute("title").isPresent(),
				copy.queryCatalog(TEST_CATALOG, reader -> {
					return reader.queryList(
						query(collection("Item"), filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("upper "))),
						EntityReference.class
					).size();
				})
			);
		}));
	}

	/**
	 * What a copy of the storage loads after a termination that the writer's schema change overlapped.
	 * @param state the state the copy loads the catalog in
	 * @param failing whether the analyzer that fails the behaviour check is published
	 * @param second whether the valid analyzer of the change is published
	 * @param title whether the field that uses the valid analyzer is published
	 * @param items how many items a fulltext query on the published `name` field finds
	 */
	private record PublishedState(
		@Nonnull CatalogState state, boolean failing, boolean second, boolean title, int items
	) {
	}

	/**
	 * A storage write failure thrown as an {@link Error} rather than an exception, so that it passes every catch that
	 * handles exceptions only. Deliberately not an `OutOfMemoryError`, which JUnit rethrows and aborts the run on.
	 */
	private static final class SimulatedWriteError extends Error {
		/** Creates the error with a message naming the simulation. */
		SimulatedWriteError() {
			super("Simulated storage write error");
		}
	}

	/**
	 * Applies a schema change through an actual transaction/session close.
	 * @param editor mutations built by the schema author
	 */
	private void change(@Nonnull Consumer<CatalogSchemaBuilder> editor) {
		this.engine.updateCatalog(TEST_CATALOG, session -> {
			final CatalogSchemaBuilder builder = session.getCatalogSchema().openForWrite();
			editor.accept(builder);
			builder.updateVia(session);
		});
	}

	/**
	 * Asserts transactional refusal and the required diagnostic fragments.
	 * @param editor schema change expected to fail at final validation
	 * @param fragments required analyzer, language, rule, sample and term text
	 * @return the schema refusal wrapped by transaction rollback
	 */
	@Nonnull
	private InvalidSchemaMutationException assertRefused(
		@Nonnull Consumer<CatalogSchemaBuilder> editor, @Nonnull String... fragments
	) {
		return assertRefusal(() -> change(editor), fragments);
	}

	/**
	 * Unwraps final transactional validation and checks its diagnostics.
	 * @param mutation the session operation expected to fail upon close
	 * @param fragments required diagnostic fragments
	 * @return the behavior refusal associated with transaction rollback
	 */
	@Nonnull
	private static InvalidSchemaMutationException assertRefusal(
		@Nonnull Runnable mutation, @Nonnull String... fragments
	) {
		final RollbackException rollback = assertThrows(RollbackException.class, mutation::run);
		final InvalidSchemaMutationException failure = assertInstanceOf(InvalidSchemaMutationException.class,
			rollback.getCause());
		for (final String fragment : fragments) {
			assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
		}
		return failure;
	}

	/** Switches the empty or previously validated warm-up catalog into transactional mode. */
	private void goLive() {
		this.engine.updateCatalog(TEST_CATALOG, session -> { session.goLiveAndClose(); });
	}

	/**
	 * Retrieves the embedded catalog, including its actual compiled-chain cache.
	 * @return the live catalog instance
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.engine.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/** Restarts over the same storage and reactivates a catalog whose refusal-driven deactivation completed. */
	private void reopen() {
		this.engine.close();
		this.engine = new Evita(configuration(this.paths));
		this.engine.waitUntilFullyInitialized();
		if (this.engine.getCatalogState(TEST_CATALOG).orElse(null) == CatalogState.INACTIVE) {
			CatalogUnpublishableBarrierAssertions.activateWithConflictRetry(this.engine, TEST_CATALOG);
		}
	}

	/**
	 * Creates a compilable but behavior-failing legacy schema with real mutations and real postings. The reflection
	 * fixture advances only the session's existing version gate, simulating a build without final behavior checking.
	 * Compilation still runs explicitly. No production bypass or persisted marker is introduced.
	 */
	private void legacyAnalyzer() {
		try (final EvitaSessionContract session = this.engine.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema("Item").withLocale(Locale.ENGLISH).updateVia(session);
			session.getCatalogSchema().openForWrite().withFulltextResource("used", wordList("and"))
				.withFulltextLanguage("en", l -> l.tokenizer(standardTokenizer()))
				.withFulltextResource("queryUsed", synonymList(synonymGroup("cup", "mug")))
				.withFulltextAnalyzer("legacy", a -> a.words(stopWords(resource("used")),
					synonyms(resource("queryUsed"))).samples("Étage"))
				.updateVia(session);
			session.defineEntitySchema("Item").withLocale(Locale.ENGLISH)
				.withAttribute("name", String.class, a -> a.localized().searchable().analyzer("legacy"))
				.updateVia(session);
			session.upsertEntity(session.createNewEntity("Item", 1).setAttribute("name", Locale.ENGLISH, "cup"));
			catalog().validateSchema();
			final Object underlying = getNonnullFieldValue(Proxy.getInvocationHandler(session), "evitaSession");
			setFieldValue(underlying, "startCatalogSchemaVersion", session.getCatalogSchema().version());
		}
	}

	/** The loaded catalog must actually answer textMatches through the failing analyzer's production chains. */
	private void assertLegacyQuery() {
		final List<EntityReference> found = this.engine.queryCatalog(TEST_CATALOG, session -> {
			return session.queryList(query(collection("Item"),
				filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("cup"))), EntityReference.class);
		});
		assertEquals(1, found.size());
	}
}
