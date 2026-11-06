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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.fulltext.FulltextIndex.SurfaceFormCursor;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.fulltext.typo.FulltextDictionaryWalker;
import io.evitadb.test.fulltext.EditDistances;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-checks lane walks against an independent decoded linear scan and distance oracle. */
@Tag(INDEXING)
@Tag(FULLTEXT)
@DisplayName("Surface lane guided walk")
class FulltextSurfaceWalkerTest {
	/** Framing, Unicode ordering, suffix isolation and repeated use of one automaton. */
	@Nested
	@DisplayName("Decoded walk equivalence")
	class Equivalence {
		/** Escaped NUL/SOH and supplementary scalars participate in distance as original code points. */
		@ParameterizedTest
		@CsvSource({"0,false,false", "0,false,true", "0,true,false", "0,true,true",
			"1,false,false", "1,false,true", "1,true,false", "1,true,true"})
		@DisplayName("Matches a linear folded-component oracle with finite and open ends")
		void shouldMatchDecodedOracle(int frozen, boolean swaps, boolean openEnd) {
			final InternalCatalogSchemaBuilder schema =
				new InternalCatalogSchemaBuilder(FulltextIndexTestSupport.SCHEMA);
			schema.withFulltextLanguage("en", l -> l.tokenizer(tokenizerStep(KeywordFactory.NAME)));
			schema.withFulltextAnalyzer("lane-walk", a -> a.words(lowercase(), englishStemming()));
			schema.withFulltextAnalyzer("other-lane", a -> a.words(lowercase(), asciiFolding()));
			try (final FulltextAnalyzerRegistry registry = analyzerRegistryWith(new KeywordFactory())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(
					schema.toInstance(), "lane-walk", Locale.ENGLISH);
				final CompiledFulltextAnalyzer other = registry.getCompiledAnalyzer(
					schema.toInstance(), "other-lane", Locale.ENGLISH);
				final FulltextIndex index = new FulltextIndex();
				final Random random = new Random(435930);
				final String[] alphabet = {"a", "b", "c", "\0", "\1", "𝒜", "\ue000", "\uffff"};
				for (int i = 0; i < 350; i++) {
					final StringBuilder value = new StringBuilder(16);
					for (int j = 0, length = 2 + random.nextInt(6); j < length; j++) {
						value.append(alphabet[random.nextInt(alphabet.length)]);
					}
					index.addValue(attribute("title"), i + 1, analyzer, 25, null, value.toString());
				}
				index.addValue(attribute("title"), 500, analyzer, 25, null, "running", "runnings", "ab\0", "ab\1");
				index.addValue(attribute("other"), 501, other, 25, null, "abonlyother");
				final long phase = analyzer.fingerprint().wordPhase();
				for (final String query : List.of("ab", "ab\0", "ab\1", "𝒜a", "a\ue000", "a\uffff", "running")) {
					for (int edits = 0; edits <= 2; edits++) {
						final Map<String, Integer> expected = new TreeMap<>();
						final SurfaceFormCursor scan = index.surfaceFormCursor(phase, "");
						while (scan.next()) {
							final String folded = scan.folded();
							final String prefix = query.substring(0, query.offsetByCodePoints(0, frozen));
							if (!folded.startsWith(prefix)) {
								continue;
							}
							int distance = EditDistances.distance(query, folded, swaps);
							if (openEnd) {
								for (int end = 0; end <= folded.length();) {
									distance = Math.min(distance,
										EditDistances.distance(query, folded.substring(0, end), swaps));
									if (end == folded.length()) {
										break;
									}
									end += Character.charCount(folded.codePointAt(end));
								}
							}
							if (distance <= edits) {
								expected.put(folded + "/" + scan.stem(), distance);
							}
						}
						final FulltextDictionaryWalker walker = new FulltextDictionaryWalker(query, edits, frozen,
							swaps, openEnd);
						for (int repeat = 0; repeat < 2; repeat++) {
							final Map<String, Integer> actual = new TreeMap<>();
							walker.walkSurface(index, phase, (cursor, distance) -> {
								actual.put(cursor.folded() + "/" + cursor.stem(), distance);
								return true;
							});
							assertEquals(expected, actual, query + "/" + edits + "/" + openEnd);
						}
						walker.walkSurface(index, phase, (cursor, distance) -> false);
					}
				}
			}
		}
	}
	/** Reused exact lookups read all key shapes and field ranges without borrowing another field's views. */
	@Nested
	@DisplayName("Reusable posting lookup")
	class Lookups {
		/** Multiple dictionary leaves exercise separator selection and mutable UTF-16 probes. */
		@Test
		@DisplayName("Finds exact Unicode stems and their aligned views across fields and leaves")
		void shouldReuseExactLookupAcrossLeaves() {
			final InternalCatalogSchemaBuilder schema =
				new InternalCatalogSchemaBuilder(FulltextIndexTestSupport.SCHEMA);
			schema.withFulltextLanguage("en", l -> l.tokenizer(tokenizerStep(KeywordFactory.NAME)));
			schema.withFulltextAnalyzer("lookup-lane", a -> a.words());
			try (final FulltextAnalyzerRegistry registry = analyzerRegistryWith(new KeywordFactory())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
					"lookup-lane", Locale.ENGLISH);
				final FulltextIndex index = new FulltextIndex();
				final List<String> words = new ArrayList<>();
				for (int i = 0; i < 600; i++) {
					words.add("word" + i);
				}
				words.addAll(List.of("ab\0", "ab\1", "a𝒜", "a\ue000", "a\uffff", "a\ud800z", "a\udc00z"));
				for (int field = 0; field < 3; field++) {
					for (int i = 0; i < words.size(); i++) {
						index.addValue(attribute("field" + field), 10000 * field + i + 1, analyzer,
							25, null, words.get(i));
					}
				}
				for (int field = 0; field < 3; field++) {
					final FulltextIndex.TermLookup lookup = index.termLookup(field);
					final StringBuilder probe = new StringBuilder(32);
					for (int i = words.size() - 1; i >= 0; i--) {
						probe.setLength(0);
						probe.append(words.get(i));
						assertTrue(lookup.find(probe), probe.toString());
						probe.setLength(0);
						assertEquals(1, lookup.size());
						assertArrayEquals(new int[]{10000 * field + i + 1}, lookup.postings().getArray());
						assertArrayEquals(impactsOf(index, field, words.get(i)), lookup.impacts().toArray());
					}
					assertFalse(lookup.find("missing"));
					assertThrows(GenericEvitaInternalError.class, lookup::size);
				}
				final SurfaceFormCursor lane = index.surfaceFormCursor(analyzer.fingerprint().wordPhase(), "");
				while (lane.next()) {
					assertEquals(lane.folded(), lane.foldedCharacters().toString());
					assertEquals(lane.stem(), lane.stemCharacters().toString());
				}
			}
		}
	}

	/** A test-only forwarding tree counts cursor opens without rewriting production classes or adding a hook. */
	@Nested
	@DisplayName("Positioned cursor seeks")
	class Seeks {
		/** An unconsumed lower bound may be revised backwards, whether or not its first ceiling exists. */
		@ParameterizedTest
		@CsvSource({"false,false", "true,false", "false,true", "true,true"})
		@DisplayName("Revises a pending seek before next consumes a ceiling or its absence")
		void shouldRevisePendingSeek(boolean absentCeiling, boolean laterPhase) {
			withSeekFixture(laterPhase, cursor -> {
				assertTrue(cursor.next());
				assertEquals("ab0000", cursor.folded());
				assertThrows(GenericEvitaInternalError.class, () -> cursor.seek("ab0000"));
				cursor.seek(absentCeiling ? "zzzz" : "ab0004");
				cursor.seek("ab0002");
				assertTrue(cursor.next());
				assertEquals("ab0002", cursor.folded());
				assertTrue(cursor.next());
				assertEquals("ab0004", cursor.folded());
				assertFalse(cursor.next());
			});
		}

		/** Once next consumes exhaustion, later seeks cannot reopen this cursor or cross its phase boundary. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Consumes a seek past the end without exposing the following phase")
		void shouldConsumeAbsentCeiling(boolean laterPhase) {
			withSeekFixture(laterPhase, cursor -> {
				assertTrue(cursor.next());
				cursor.seek("zzzz");
				assertFalse(cursor.next());
				cursor.seek("ab0002");
				assertFalse(cursor.next());
			});
		}

		/** Positioned successor seeks must keep the tree cursor, including repeated pending seeks. */
		@Test
		@DisplayName("Does not reopen the tree for same-leaf surface successors")
		void shouldReuseDelegateForSameLeafSeeks() throws Exception {
			final InternalCatalogSchemaBuilder schema =
				new InternalCatalogSchemaBuilder(FulltextIndexTestSupport.SCHEMA);
			schema.withFulltextLanguage("en", l -> l.tokenizer(tokenizerStep(KeywordFactory.NAME)));
			schema.withFulltextAnalyzer("seek-lane", a -> a.words(lowercase()));
			try (final FulltextAnalyzerRegistry registry = analyzerRegistryWith(new KeywordFactory())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
					"seek-lane", Locale.ENGLISH);
				final FulltextIndex index = new FulltextIndex();
				for (int i = 0; i < 1000; i++) {
					index.addValue(attribute("title"), i + 1, analyzer, 25, null,
						String.format(Locale.ROOT, "ab%04d", i));
				}
				final Field treeField = FulltextIndex.class.getDeclaredField("surfaceForms");
				treeField.setAccessible(true);
				@SuppressWarnings("unchecked")
				final CountingSurfaceTree tree = new CountingSurfaceTree(
					(TransactionalBucketBPlusTree<String>) treeField.get(index));
				treeField.set(index, tree);
				final SurfaceFormCursor cursor = index.surfaceFormCursor(analyzer.fingerprint().wordPhase(), "ab");
				assertTrue(cursor.next());
				assertEquals("ab0000", cursor.folded());
				cursor.seek("ab0002");
				cursor.seek("ab0004");
				assertTrue(cursor.next());
				assertEquals("ab0004", cursor.folded());
				assertTrue(cursor.next());
				assertEquals("ab0005", cursor.folded());
				assertEquals(1, tree.opens);
			}
		}
	}

	/** Supplies sparse keys, optionally followed by a physical ceiling in another word phase. */
	private static void withSeekFixture(boolean laterPhase, Consumer<SurfaceFormCursor> test) {
		final InternalCatalogSchemaBuilder schema =
			new InternalCatalogSchemaBuilder(FulltextIndexTestSupport.SCHEMA);
		schema.withFulltextLanguage("en", l -> l.tokenizer(tokenizerStep(KeywordFactory.NAME)));
		schema.withFulltextAnalyzer("seek-lane", a -> a.words(lowercase()));
		schema.withFulltextAnalyzer("other-seek-lane", a -> a.words(lowercase(), asciiFolding()));
		try (final FulltextAnalyzerRegistry registry = analyzerRegistryWith(new KeywordFactory())) {
			final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
				"seek-lane", Locale.ENGLISH);
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("title"), 1, analyzer, 25, null, "ab0000", "ab0002", "ab0004");
			if (laterPhase) {
				final CompiledFulltextAnalyzer other = registry.getCompiledAnalyzer(schema.toInstance(),
					"other-seek-lane", Locale.ENGLISH);
				assertNotEquals(analyzer.fingerprint().wordPhase(), other.fingerprint().wordPhase());
				index.addValue(attribute("brand"), 2, other, 25, null, "ab0003");
			}
			test.accept(index.surfaceFormCursor(analyzer.fingerprint().wordPhase(), "ab"));
		}
	}

	/** Forwards the selected lane read entry point, avoiding Mockito's global allocation instrumentation. */
	private static final class CountingSurfaceTree extends TransactionalBucketBPlusTree<String> {
		private final TransactionalBucketBPlusTree<String> source;
		private int opens;

		/** Retains the real populated tree; this forwarding instance never receives writes. */
		private CountingSurfaceTree(TransactionalBucketBPlusTree<String> source) {
			super(String.class);
			this.source = source;
		}

		@Nonnull
		@Override
		public BucketCursor<String> cursor(@Nonnull String value) {
			this.opens++;
			return this.source.cursor(value);
		}
	}
}
