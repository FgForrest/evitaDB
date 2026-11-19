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

package io.evitadb.index.fulltext;

import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FulltextIndex.SurfaceFormCursor;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.test.fulltext.AnalyzedTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.*;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static io.evitadb.utils.AssertionUtils.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the surface lane through real compiled analyzers and the value write path. */
@Tag(FULLTEXT)
@Tag(INDEXING)
@DisplayName("Fulltext surface forms")
class FulltextSurfaceFormIndexTest {
	/** Value-level counts and profile boundaries. */
	@Nested
	@DisplayName("Maintenance")
	class Maintenance {
		/** Repetitions within a value count once, including repetitions across array elements. */
		@Test
		@DisplayName("should count each entity field once and remove only its final reference")
		void shouldCountValues() {
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), 25, null, "Černé černé", "černé");
			index.addValue(attribute("description"), 1, testAnalyzer(), 25, null, "černé");
			index.addValue(attribute("name"), 2, testAnalyzer(), 25, null, "cerne");
			assertEquals(List.of("cerne/cern"), entries(index, testAnalyzer().fingerprint().wordPhase(), ""));
			assertPersistedEntries(index, testAnalyzer().fingerprint().wordPhase(), List.of("cerne/cern/černé"));
			assertEquals(3, count(index));
			index.removeValue(attribute("name"), 1, testAnalyzer(), 25, "Černé černé", "černé");
			assertEquals(2, count(index));
			assertEquals(List.of("cerne/cern"), entries(index, testAnalyzer().fingerprint().wordPhase(), ""));
			assertPersistedEntries(index, testAnalyzer().fingerprint().wordPhase(), List.of("cerne/cern/černé"));
			index.removeValue(attribute("description"), 1, testAnalyzer(), 25, "černé");
			assertEquals(1, count(index));
			index.removeValue(attribute("name"), 2, testAnalyzer(), 25, "cerne");
			assertTrue(entries(index, testAnalyzer().fingerprint().wordPhase(), "").isEmpty());
		}

		/** An unstemmed, nonfolding profile has its own lane range. */
		@Test
		@DisplayName("should isolate word phases and preserve accents when the profile does not fold")
		void shouldIsolateProfiles() {
			final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
			schema.withFulltextAnalyzer("brand", profile -> profile.words(lowercase()));
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer brand = registry.getCompiledAnalyzer(schema.toInstance(), "brand",
					Locale.forLanguageTag("cs"));
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("description"), 1, testAnalyzer(), 25, null, "černé");
				index.addValue(attribute("brand"), 1, brand, 25, null, "Cern café cafe");
				final long descriptionPhase = testAnalyzer().fingerprint().wordPhase();
				final long brandPhase = brand.fingerprint().wordPhase();
				assertNotEquals(descriptionPhase, brandPhase);
				assertEquals(List.of("cerne/cern"), entries(index, descriptionPhase, "cer"));
				assertEquals(List.of("cafe/cafe"), entries(index, brandPhase, "cafe"));
				assertEquals(List.of("café/café"), entries(index, brandPhase, "café"));
				assertEquals(List.of("cern/cern"), entries(index, brandPhase, "cer"));
				final List<FulltextIndex.SurfaceFormPage> pages = index.collectChangedSurfaceFormPages().changedPages();
				assertEquals(List.of("cerne/cern/černé"),
					persistedSurfaceEntries(pages, index.getWordPhaseIds().get(descriptionPhase)));
				assertEquals(List.of("cafe/cafe/cafe", "café/café/café", "cern/cern/cern"),
					persistedSurfaceEntries(pages, index.getWordPhaseIds().get(brandPhase)));
			}
		}

		/** Protected and unprotected inputs share a normalized lookup key and retain independently addressed stems. */
		@Test
		@DisplayName("should retain distinct stems under the same normalized lookup key")
		void shouldRetainProtectedTokens() {
			final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
			schema.withFulltextResource("protected", wordList("cerne"))
				.withFulltextAnalyzer("protected-text", profile -> profile.words(lowercase(),
					czechStemming(protectedWords(resource("protected")))));
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
					"protected-text", Locale.forLanguageTag("cs"));
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, analyzer, 25, null, "černé cerne");
				assertEquals(List.of("cerne/cerne", "cerne/cern"),
					entriesExact(index, analyzer.fingerprint().wordPhase(), "cerne"));
				assertPersistedEntries(index, analyzer.fingerprint().wordPhase(),
					List.of("cerne/cerne/cerne", "cerne/cern/černé"));
				index.removeValue(attribute("name"), 1, analyzer, 25, "černé cerne");
				assertTrue(entries(index, analyzer.fingerprint().wordPhase(), "").isEmpty());
			}
		}

		/** String display lowercasing can expand a code point that the index filter lowercases without expansion. */
		@Test
		@DisplayName("should let the index chain own Unicode lowercasing")
		void shouldRespectIndexLowercasing() {
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("name"), 1, exactAnalyzer(), 25, null, "İSTANBUL");
			assertEquals(List.of("istanbul/istanbul"),
				entriesExact(index, exactAnalyzer().fingerprint().wordPhase(), "istanbul"));
			assertPersistedEntries(index, exactAnalyzer().fingerprint().wordPhase(),
				List.of("istanbul/istanbul/i\u0307stanbul"));
		}

		/** Index text preparation changes the token while display retains the original source slice. */
		@Test
		@DisplayName("should derive tokens from index text preparation")
		void shouldPrepareIndexText() {
			final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
			schema.withFulltextAnalyzer("joined", profile -> profile.text(removeCharacters("-"))
				.words(lowercase()));
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
					"joined", Locale.ROOT);
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, analyzer, 25, null, "USB-C");
				assertEquals(List.of("usbc/usbc"), entries(index, analyzer.fingerprint().wordPhase(), ""));
				assertPersistedEntries(index, analyzer.fingerprint().wordPhase(), List.of("usbc/usbc/usb-c"));
			}
		}

		/** The lane uses the same embedded German normalization as query surface preparation. */
		@Test
		@DisplayName("should retain the lookup normalization of the German stemming operation")
		void shouldNormalizeGermanSurfaces() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(SCHEMA, "text", Locale.GERMAN);
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, analyzer, 25, null, "Häuser Straße Café");
				final SurfaceFormCursor cursor = index.surfaceFormCursor(analyzer.fingerprint().wordPhase(), "");
				final List<String> folded = new ArrayList<>();
				while (cursor.next()) {
					folded.add(folded(cursor));
				}
				assertEquals(List.of("café", "hauser", "strasse"), folded);
			}
		}

		/** Removed stop words must never become lane entries, including stop-only values. */
		@Test
		@DisplayName("should omit stop words and keep text-only profile variants in one word phase")
		void shouldShareOnlyWordPhase() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer html = registry.getCompiledAnalyzer(SCHEMA, "html-text",
					Locale.forLanguageTag("cs"));
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, testAnalyzer(), 25, null, "a ale jako");
				assertTrue(entries(index, testAnalyzer().fingerprint().wordPhase(), "").isEmpty());
				index.addValue(attribute("name"), 2, testAnalyzer(), 25, null, "cerne");
				index.addValue(attribute("html"), 2, html, 25, null, "<b>černé</b>", "<p>&#269;erné</p>");
				assertEquals(1, index.getWordPhaseIds().size());
				assertEquals(Map.of("cerne\0cern", 2L),
					surfaceReferenceCounts(index, testAnalyzer().fingerprint().wordPhase()));
			}
		}

		/** Cursor bounds are part of the production expansion seam. */
		@Test
		@DisplayName("should bound exact hits, prefixes and forward seeks to one phase")
		void shouldBoundCursor() {
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("name"), 1, exactAnalyzer(), 25, null, "cafe cafeteria café delta");
			final long phase = exactAnalyzer().fingerprint().wordPhase();
			assertEquals(List.of("cafe/cafe"), entriesExact(index, phase, "cafe"));
			assertPersistedEntries(index, phase, List.of("cafe/cafe/cafe", "cafeteria/cafeteria/cafeteria",
				"café/café/café", "delta/delta/delta"));
			final SurfaceFormCursor cursor = index.surfaceFormCursor(phase, "caf");
			assertTrue(cursor.next());
			assertEquals("cafe", folded(cursor));
			cursor.seek("café");
			assertTrue(cursor.next());
			assertEquals("café", folded(cursor));
			assertThrows(GenericEvitaInternalError.class, () -> cursor.seek("cafe"));
			assertFalse(cursor.next());
			assertFalse(index.surfaceFormCursor(Long.MIN_VALUE, "").next());
		}

		/** Removing a field with no lane keys frees its short identity. */
		@Test
		@DisplayName("should retain ids for current fields and free unused word phases")
		void shouldFreeUnusedPhase() {
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), 25, null, "černé");
			index.removeValue(attribute("name"), 1, testAnalyzer(), 25, "černé");
			assertEquals(1, index.getWordPhaseIds().size());
			index.retireField(attribute("name"));
			assertTrue(index.getWordPhaseIds().isEmpty());
			index.addValue(attribute("brand"), 1, exactAnalyzer(), 25, null, "brand");
			assertEquals(0, index.getWordPhaseIds().get(exactAnalyzer().fingerprint().wordPhase()));
		}
	}

	/** Both mutation modes rewind the lane and its id registry. */
	@Nested
	@Tag(TRANSACTION)
	@DisplayName("Rollback")
	class Rollback {
		/** Warm-up inverse replay must rewind all lane structures. */
		@Test
		@DisplayName("should restore the lane after an entity savepoint rollback")
		void shouldRestoreEntitySavepoint() {
			final FulltextIndex fixture = new FulltextIndex();
			assertWarmUpSavepointRollbackRestores(fixture,
				index -> index.addValue(attribute("name"), 1, testAnalyzer(), 25, null, "černé"),
				FulltextSurfaceFormIndexTest::state,
				index -> {
					index.removeValue(attribute("name"), 1, testAnalyzer(), 25, "černé");
					index.retireField(attribute("name"));
					index.addValue(attribute("brand"), 2, exactAnalyzer(), 25, null, "café");
				});
			assertPersistedEntries(fixture, testAnalyzer().fingerprint().wordPhase(), List.of("cerne/cern/černé"));
		}

		/**
		 * A transaction's per-entity memento also restores additions, removals and a word-phase id the retirement
		 * released and the next field reused.
		 */
		@Test
		@DisplayName("should restore the lane after a transactional entity savepoint rollback")
		void shouldRestoreTransactionalEntitySavepoint() {
			assertStateAfterCommit(new FulltextIndex(),
				index -> {
					index.addValue(attribute("name"), 1, testAnalyzer(), 25, null, "černé");
					final String expected = state(index);
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					final Savepoint savepoint = maintainer.openSavepoint();
					index.removeValue(attribute("name"), 1, testAnalyzer(), 25, "černé");
					index.retireField(attribute("name"));
					index.addValue(attribute("brand"), 2, exactAnalyzer(), 25, null, "café");
					assertEquals(Map.of(exactAnalyzer().fingerprint().wordPhase(), 0), index.getWordPhaseIds());
					maintainer.rollbackSavepoint(savepoint);
					assertEquals(expected, state(index));
				},
				(original, committed) -> assertPersistedEntries(committed,
					testAnalyzer().fingerprint().wordPhase(), List.of("cerne/cern/černé")));
		}

		/** Transaction rollback discards both node and id-map layers. */
		@Test
		@DisplayName("should restore the lane after transaction rollback and merge it on commit")
		void shouldRestoreTransaction() {
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("name"), 1, testAnalyzer(), 25, null, "černé");
			assertStateAfterRollback(index,
				changed -> {
					changed.removeValue(attribute("name"), 1, testAnalyzer(), 25, "černé");
					changed.addValue(attribute("brand"), 2, exactAnalyzer(), 25, null, "café");
				},
				(original, ignored) -> {
					assertEquals(1, original.getWordPhaseIds().size());
					assertEquals(List.of("cerne/cern"),
						entries(original, testAnalyzer().fingerprint().wordPhase(), ""));
					assertPersistedEntries(original, testAnalyzer().fingerprint().wordPhase(),
						List.of("cerne/cern/černé"));
				});
			assertStateAfterCommit(index,
				changed -> changed.addValue(attribute("name"), 2, testAnalyzer(), 25, null, "černé"),
				(original, committed) -> {
					assertEquals(1, count(original));
					assertEquals(2, count(committed));
				});
		}
	}

	/** The query preparation must compose with both exact lookup and prefix walking. */
	@Nested
	@DisplayName("Lookup normalization")
	class LookupNormalization {
		/** Folding profiles index the accented spelling; English and no-stem profiles index both distinct spellings. */
		@ParameterizedTest
		@CsvSource({"cs,Černé,cerne", "sk,Čierne,cierne", "pl,Żółty,zolty", "ro,Cărți,carti",
			"de,Häuser,hauser", "en,Cafés,cafes", "root,Café,cafe"})
		@DisplayName("should find every built-in profile through query-prepared exact and prefix keys")
		void shouldComposeQueryPreparation(String language, String accented, String unaccented) {
			final Locale locale = language.equals("root") ? Locale.ROOT : Locale.forLanguageTag(language);
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(SCHEMA, "text", locale);
				final FulltextIndex index = new FulltextIndex();
				final boolean preservesAccents = language.equals("root") || language.equals("en");
				final String value = preservesAccents ? accented + " " + unaccented : accented;
				index.addValue(attribute("name"), 1, analyzer, 25, null, value);
				final List<String> persisted =
					collectPersistedSurfaceEntries(index, analyzer.fingerprint().wordPhase());
				final Map<String, String> expectedPersisted = new TreeMap<>();
				for (final String spelling : List.of(accented, unaccented)) {
					final List<AnalyzedTerm> prepared = collectTerms(analyzer.surface(), spelling);
					assertEquals(1, prepared.size());
					final String key = prepared.getFirst().term();
					assertEquals(preservesAccents ? spelling.toLowerCase(Locale.ROOT) : unaccented, key);
					final SurfaceFormCursor exact = index.exactSurfaceFormCursor(
						analyzer.fingerprint().wordPhase(), key);
					assertTrue(exact.next(), language + ": query key " + key);
					assertEquals(key, folded(exact));
					final String stem = stem(exact);
					final String display = preservesAccents ? spelling.toLowerCase(Locale.ROOT)
						: accented.toLowerCase(Locale.ROOT);
					expectedPersisted.put(key, key + "/" + stem + "/" + display);
					final SurfaceFormCursor prefix = index.surfaceFormCursor(
						analyzer.fingerprint().wordPhase(), key.substring(0, 2));
					boolean found = false;
					while (prefix.next()) {
						found |= folded(prefix).equals(key);
					}
					assertTrue(found, language + ": prepared prefix must reach " + key);
				}
				assertEquals(new ArrayList<>(expectedPersisted.values()), persisted);
				index.removeValue(attribute("name"), 1, analyzer, 25, value);
				assertTrue(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).isEmpty());
			}
		}
	}

	/** Cursor bounds are actual text, including a final NUL. */
	@Nested
	@DisplayName("Arbitrary cursor bounds")
	class CursorBounds {
		/** Exact selection and a real NUL-containing prefix have different ranges. */
		@Test
		@DisplayName("should distinguish exact words from prefixes containing trailing NUL")
		void shouldRepresentTrailingNulPrefixes() {
			withCursorFixture((index, phase) -> {
				for (final String value : List.of("ab", "ab\0", "ab\0c", "ac")) {
					final SurfaceFormCursor exact = index.exactSurfaceFormCursor(phase, value);
					assertDoesNotThrow(() -> exact.seek(value));
					assertWords(exact, List.of(value));
				}
				assertWords(index.surfaceFormCursor(phase, "ab\0"), List.of("ab\0", "ab\0c"));
				assertWords(index.surfaceFormCursor(phase, "ab"), List.of("ab", "ab\0", "ab\0c"));
			});
		}

		/** The lower bound after ab must remain after ab when encoded. */
		@Test
		@DisplayName("should seek forward to real trailing NUL bounds without throwing")
		void shouldSeekTrailingNulBounds() {
			withCursorFixture((index, phase) -> {
				final SurfaceFormCursor cursor = index.surfaceFormCursor(phase, "");
				assertTrue(cursor.next());
				assertEquals("ab", folded(cursor));
				assertDoesNotThrow(() -> cursor.seek("ab\0"));
				assertTrue(cursor.next());
				assertEquals("ab\0", folded(cursor));
				cursor.seek("ab\0c");
				assertTrue(cursor.next());
				assertEquals("ab\0c", folded(cursor));
				cursor.seek("ac");
				assertWords(cursor, List.of("ac"));
				final SurfaceFormCursor prefix = index.surfaceFormCursor(phase, "ab");
				prefix.seek("ab\0");
				assertWords(prefix, List.of("ab\0", "ab\0c"));
			});
		}
	}

	/** Creates adjacent keys and a second phase whose matching keys must never leak into the selected range. */
	private static void withCursorFixture(BiConsumer<FulltextIndex, Long> assertion) {
		final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
		schema.withFulltextLanguage("en", language -> language.tokenizer(tokenizerStep(KeywordFactory.NAME)));
		schema.withFulltextAnalyzer("keyword", profile -> profile.words(lowercase()));
		schema.withFulltextAnalyzer("other", profile -> profile.words(lowercase(), asciiFolding()));
		try (final FulltextAnalyzerRegistry registry = analyzerRegistryWith(new KeywordFactory())) {
			final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
				"keyword", Locale.ENGLISH);
			final CompiledFulltextAnalyzer other = registry.getCompiledAnalyzer(schema.toInstance(),
				"other", Locale.ENGLISH);
			final FulltextIndex index = new FulltextIndex();
			int id = 1;
			for (final String value : List.of("ab", "ab\0", "ab\0c", "ac")) {
				index.addValue(attribute("name"), id++, analyzer, 25, null, value);
				index.addValue(attribute("other"), id++, other, 25, null, value);
			}
			assertNotEquals(analyzer.fingerprint().wordPhase(), other.fingerprint().wordPhase());
			assertion.accept(index, analyzer.fingerprint().wordPhase());
		}
	}

	/** Reads every folded result and checks exact cardinality as well as order. */
	private static void assertWords(SurfaceFormCursor cursor, List<String> expected) {
		final List<String> actual = new ArrayList<>();
		while (cursor.next()) {
			actual.add(folded(cursor));
		}
		assertEquals(expected, actual);
	}

	/** Materializes only the bounded production cursor. */
	static List<String> entries(FulltextIndex index, long phase, String prefix) {
		final List<String> result = new ArrayList<>();
		final SurfaceFormCursor cursor = index.surfaceFormCursor(phase, prefix);
		while (cursor.next()) {
			result.add(foldedAndStem(cursor));
		}
		return result;
	}

	/** Materializes the explicit exact-word production cursor, with no delimiter convention. */
	static List<String> entriesExact(FulltextIndex index, long phase, String word) {
		final List<String> result = new ArrayList<>();
		final SurfaceFormCursor cursor = index.exactSurfaceFormCursor(phase, word);
		while (cursor.next()) {
			result.add(foldedAndStem(cursor));
		}
		return result;
	}

	/** Flushes a freshly written fixture and checks its persisted display payload as well as the two lookup parts. */
	private static void assertPersistedEntries(
		@Nonnull FulltextIndex index, long phase, @Nonnull List<String> expected
	) {
		final List<FulltextIndex.SurfaceFormPage> pages = index.collectChangedSurfaceFormPages().changedPages();
		assertFalse(pages.isEmpty(), "This assertion requires a dirty fixture at its flush boundary.");
		assertEquals(expected, persistedSurfaceEntries(pages, index.getWordPhaseIds().get(phase)));
	}

	/** Captures keys, references and short identities for savepoint comparison. */
	private static String state(FulltextIndex index) {
		assertEquals(Map.of("cerne\0cern", 1L),
			surfaceReferenceCounts(index, testAnalyzer().fingerprint().wordPhase()),
			"The savepoint baseline must contain the lane pair, not just postings.");
		return index.getWordPhaseIds().toString()
			+ surfaceReferenceCounts(index, testAnalyzer().fingerprint().wordPhase())
			+ entries(index, testAnalyzer().fingerprint().wordPhase(), "")
			+ entries(index, exactAnalyzer().fingerprint().wordPhase(), "");
	}

	/** Reads reference counts from the lane payload without changing flush state. */
	private static long count(FulltextIndex index) {
		long result = 0;
		for (final long references : surfaceReferenceCounts(index, testAnalyzer().fingerprint().wordPhase()).values()) {
			result += references;
		}
		return result;
	}
}
