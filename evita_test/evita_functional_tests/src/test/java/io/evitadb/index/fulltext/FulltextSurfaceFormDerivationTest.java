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

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.fulltext.analysis.FulltextChainCompiler;
import io.evitadb.index.fulltext.analysis.ResolvedFulltextAnalyzer;
import io.evitadb.index.page.PageEmission;
import io.evitadb.spi.analysis.*;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextSurfaceFormLeafPagePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.test.fulltext.AnalyzedTerm;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKBigramFilter;
import org.apache.lucene.analysis.miscellaneous.ConcatenateGraphFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.AttributeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.index.fulltext.FulltextIndexTestSupport.*;
import static io.evitadb.store.index.serializer.StoragePartSerializerTestSupport.roundTrip;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Derivation of surface pairs from index tokens: what the capture sees through text preparation, splitting, stemming
 * and downstream words operations, and how the pairs are encoded as canonical keys.
 */
@Tag(FULLTEXT)
@Tag(INDEXING)
@DisplayName("Surface forms derived from index tokens")
class FulltextSurfaceFormDerivationTest {
	/** Query-only resources cannot change persistent lane identities. */
	@Nested
	@DisplayName("Query-only synonyms")
	class SynonymEdits {
		/** Growing the query expansion must not make an indexed value undeletable. */
		@Test
		@DisplayName("should remove after growing a synonym group without orphaned references")
		void shouldRemoveAfterGrowingSynonyms() {
			assertSynonymEdit(new String[]{"car", "vehicle"}, new String[]{"car", "vehicle", "automobile"});
		}

		/** Shrinking the query expansion must not leave old alternatives in the lane. */
		@Test
		@DisplayName("should remove after shrinking a synonym group without orphaned references")
		void shouldRemoveAfterShrinkingSynonyms() {
			assertSynonymEdit(new String[]{"car", "vehicle", "automobile"}, new String[]{"car", "vehicle"});
		}
	}

	/** Source offsets cannot identify individual split tokens after text preparation. */
	@Nested
	@DisplayName("Text preparation and splitting")
	class SplitTokens {
		/** The character filter makes all split tokens share the original source span. */
		@Test
		@DisplayName("should retain each word and number token after removing a character")
		void shouldRetainSplitTokensAfterRemovingCharacters() {
			final InternalCatalogSchemaBuilder schema = splitSchema();
			schema.withFulltextAnalyzer("split", profile -> profile.text(removeCharacters("-")).words(lowercase()));
			assertSplitPairs(schema.toInstance(), "AB-12");
		}

		/** Decoded character references produce the same offset-span mismatch. */
		@Test
		@DisplayName("should retain each word and number token after decoding HTML references")
		void shouldRetainSplitTokensAfterHtmlReferences() {
			final InternalCatalogSchemaBuilder schema = splitSchema();
			schema.withFulltextAnalyzer("split", profile -> profile.text(htmlStrip()).words(lowercase()));
			assertSplitPairs(schema.toInstance(), "AB&#49;2");
		}

		/** A removed delimiter is not part of the analyzed token and must not reject the value. */
		@Test
		@DisplayName("should drop NUL from display text after the analyzer removes it")
		void shouldRemoveNulFromDisplay() {
			final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
			schema.withFulltextAnalyzer("clean", profile -> profile.text(removeCharacters("\0")).words(lowercase()));
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
					"clean", Locale.ROOT);
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, analyzer, 25, null, "AB\0CD");
				assertEquals(Map.of("abcd\0abcd", 1L),
					surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()));
				assertEquals(List.of("abcd/abcd"),
					FulltextSurfaceFormIndexTest.entries(index, analyzer.fingerprint().wordPhase(), ""));
				assertEquals(List.of("abcd/abcd/abcd"),
					collectPersistedSurfaceEntries(index, analyzer.fingerprint().wordPhase()));
				index.removeValue(attribute("name"), 1, analyzer, 25, "AB\0CD");
				assertTrue(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).isEmpty());
			}
		}

		/**
		 * A text step that lengthens the text without correcting offsets leaves the last token's end past the text, so
		 * there is no display slice - the write must still index the postings and record the pair.
		 */
		@Test
		@DisplayName("should fall back to the folded display when a text step leaves offsets past the text")
		void shouldFallBackToFoldedDisplayWhenOffsetsLeaveText() {
			final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
			schema.withFulltextAnalyzer("expanded",
				profile -> profile.text(textStep(SharpSExpansionFactory.NAME)).words(lowercase()));
			try (final FulltextAnalyzerRegistry registry = analyzerRegistryWith(new SharpSExpansionFactory())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
					"expanded", Locale.ROOT);
				final List<String> postingTerms = new ArrayList<>();
				analyzer.indexSurface().analyze("Straße", (term, surface, start, end, increment) -> postingTerms.add(term));
				assertEquals(List.of("strasse"), postingTerms);
				final FulltextIndex index = new FulltextIndex();

				index.addValue(attribute("name"), 1, analyzer, 25, null, "Straße");

				final int fieldId = index.getFieldId(attribute("name"));
				assertArrayEquals(new int[]{1}, postingsOf(index, fieldId, "strasse").getArray());
				assertEquals(List.of("strasse/strasse"),
					FulltextSurfaceFormIndexTest.entries(index, analyzer.fingerprint().wordPhase(), ""));
				assertEquals(List.of("strasse/strasse/strasse"),
					collectPersistedSurfaceEntries(index, analyzer.fingerprint().wordPhase()));

				index.removeValue(attribute("name"), 1, analyzer, 25, "Straße");

				assertTrue(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).isEmpty());
				assertTrue(postingsOf(index, fieldId, "strasse").isEmpty());
			}
		}
	}

	/** Identity stems need no repeated variable-length payload. */
	@Nested
	@DisplayName("Canonical keys")
	class CanonicalKeys {
		/** The persisted key carries empty identity-stem and display components. */
		@Test
		@DisplayName("should store an identity stem only once")
		void shouldOmitIdentityStem() {
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("name"), 1, exactAnalyzer(), 25, null, "cafe");
			final FulltextIndex.SurfaceFormPage page = index.collectChangedSurfaceFormPages().changedPages().getFirst();
			assertArrayEquals(new String[]{"0000\0cafe\0\0"}, page.keys());
			assertEquals(List.of("cafe/cafe"),
				FulltextSurfaceFormIndexTest.entriesExact(index, exactAnalyzer().fingerprint().wordPhase(), "cafe"));
		}
		/** Persisted identities must not repeat folded text or contain ambiguous escape sequences. */
		@Test
		@DisplayName("should reject malformed or noncanonical persisted components")
		void shouldRejectMalformedKeys() {
			for (final String key : List.of("0000\0cafe\0cafe\0", "0000\0cafe\0\0cafe",
				"0000\0cafe\0\1\0", "0000\0cafe\0\0bad\0display", "0000\0\1\3\0\0")) {
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, exactAnalyzer(), 25, null, "cafe");
				assertThrows(io.evitadb.exception.GenericEvitaInternalError.class,
					() -> reload(index, new FulltextIndex.SurfaceFormPage(0, new String[]{key}, new long[]{1})));
			}
		}

	}

	/** The capture variant must leave the complete posting stream byte-for-byte equivalent. */
	@Nested
	@DisplayName("Index stream capture")
	class IndexCapture {
		/** Every built-in index stemmer is one-in/one-out, including the folding stemmers. */
		@ParameterizedTest
		@ValueSource(strings = {"cs", "sk", "pl", "ro", "en", "de", ""})
		@DisplayName("should preserve posting attributes and pair each built-in pre-stem token in order")
		void shouldPreserveBuiltInStreams(String language) {
			final Locale locale = Locale.forLanguageTag(language);
			final AnalysisStepRegistry steps = new AnalysisStepRegistry();
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(steps)) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(SCHEMA, "text", locale);
				final String text = "Černé černá Häuser Straße Running café AB12 a the şi, ";
				final List<AnalyzedTerm> capturedPostings = new ArrayList<>();
				final List<AnalyzedTerm> capturedInputs = new ArrayList<>();
				analyzer.indexSurface().analyzeIndexed(text, (term, folded, display, start, end, increment) -> {
					capturedPostings.add(new AnalyzedTerm(term, display.get(), start, end, increment));
					capturedInputs.add(new AnalyzedTerm(folded.toString(), display.get(), start, end, increment));
				});
				final ResolvedFulltextAnalyzer resolved = ResolvedFulltextAnalyzer.resolve(SCHEMA, "text", locale);
				try (final FulltextAnalyzer uncaptured = new FulltextAnalyzer("uncaptured",
					new FulltextChainCompiler(steps).compile(resolved, FulltextAnalysisSide.INDEX))) {
					assertEquals(collectTerms(uncaptured, text), capturedPostings);
				}
				int stemAt = resolved.words().size();
				for (int i = 0; i < resolved.words().size(); i++) {
					if (resolved.words().get(i).name().contains("stemming")) {
						stemAt = i;
						break;
					}
				}
				try (final FulltextAnalyzer prefix = new FulltextAnalyzer("index-prefix",
					new FulltextChainCompiler(steps).compile(resolved.text(), resolved.splitting(),
						resolved.words().subList(0, stemAt), FulltextAnalysisSide.INDEX, resolved.context()))) {
					final List<AnalyzedTerm> expected = new ArrayList<>();
					for (final AnalyzedTerm input : collectTerms(prefix, text)) {
						final String normalized = collectTerms(analyzer.surface(), input.term()).getFirst().term();
						expected.add(new AnalyzedTerm(normalized, input.surfaceForm(), input.startOffset(),
							input.endOffset(), input.positionIncrement()));
					}
					assertEquals(expected, capturedInputs);
				}
			}
		}

		/** Captured token states preserve the input even when a plugin emits multiple stems at a shared position. */
		@Test
		@DisplayName("should independently address identity and nonidentity stems at the same position")
		void shouldPairMultipleStems() {
			final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
			schema.withFulltextAnalyzer("multi", profile -> profile.words(lowercase(), wordsStep("test-multi-stem")));
			try (final FulltextAnalyzerRegistry registry = analyzerRegistryWith(new MultiStemFactory())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
					"multi", Locale.ROOT);
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, analyzer, 25, null, "cards cards");
				index.addValue(attribute("name"), 2, analyzer, 25, null, "cards");
				assertEquals(Map.of("cards\0cards", 2L, "cards\0card", 2L),
					surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()));
				assertEquals(List.of("cards/cards", "cards/card"),
					FulltextSurfaceFormIndexTest.entriesExact(index, analyzer.fingerprint().wordPhase(), "cards"));
				final FulltextIndex restored = reload(index, null, "cards/cards/cards", "cards/card/cards");
				assertEquals(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()),
					surfaceReferenceCounts(restored, analyzer.fingerprint().wordPhase()));
				restored.removeValue(attribute("name"), 1, analyzer, 25, "cards cards");
				assertEquals(Map.of("cards\0cards", 1L, "cards\0card", 1L),
					surfaceReferenceCounts(restored, analyzer.fingerprint().wordPhase()));
				restored.removeValue(attribute("name"), 2, analyzer, 25, "cards");
				assertTrue(surfaceReferenceCounts(restored, analyzer.fingerprint().wordPhase()).isEmpty());
			}
		}

		/** Keyword tokenization can legitimately retain framing characters that standard splitting removes. */
		@Test
		@DisplayName("should escape NUL and SOH in actual tokens and preserve exact lookup after reload")
		void shouldEscapeTokenComponents() {
			final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
			schema.withFulltextLanguage("en", language -> language.tokenizer(tokenizerStep(KeywordFactory.NAME)));
			schema.withFulltextAnalyzer("keyword", profile -> profile.words(lowercase()));
			try (final FulltextAnalyzerRegistry registry = analyzerRegistryWith(new KeywordFactory())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema.toInstance(),
					"keyword", Locale.ENGLISH);
				final FulltextIndex index = new FulltextIndex();
				final String value = "AB\0CD\1EF";
				index.addValue(attribute("name"), 1, analyzer, 25, null, value);
				final FulltextIndex restored = reload(index, null, "ab\0cd\1ef/ab\0cd\1ef/abcd\1ef");
				final FulltextIndex.SurfaceFormCursor cursor = restored.exactSurfaceFormCursor(
					analyzer.fingerprint().wordPhase(), value.toLowerCase(Locale.ROOT));
				assertTrue(cursor.next());
				assertEquals("ab\0cd\1ef", folded(cursor));
				assertEquals(folded(cursor), stem(cursor));
				assertFalse(cursor.next());
				assertTrue(restored.collectChangedSurfaceFormPages().changedPages().isEmpty());
				// Reuse the pair to emit its reconstructed key without replacing the first display spelling.
				restored.addValue(attribute("name"), 2, analyzer, 25, null, value);
				assertEquals(List.of("ab\0cd\1ef/ab\0cd\1ef/abcd\1ef"),
					collectPersistedSurfaceEntries(restored, analyzer.fingerprint().wordPhase()),
					"The reconstructed index must retain the escaped display spelling.");
				restored.removeValue(attribute("name"), 1, analyzer, 25, value);
				assertEquals(Map.of("ab\0cd\1ef\0ab\0cd\1ef", 1L),
					surfaceReferenceCounts(restored, analyzer.fingerprint().wordPhase()));
				restored.removeValue(attribute("name"), 2, analyzer, 25, value);
				assertTrue(surfaceReferenceCounts(restored, analyzer.fingerprint().wordPhase()).isEmpty());
			}
		}
	}

	/** Auxiliary state must never refuse otherwise valid postings. */
	@Nested
	@DisplayName("Attribute propagation")
	class AttributePropagation {
		/** This real filter owns its attribute source, including for a one-token value. */
		@ParameterizedTest
		@ValueSource(strings = {"cards", "cards dogs"})
		@DisplayName("should index and remove concatenated postings without inventing surface pairs")
		void shouldDegradeWhenAttributeSourceIsReplaced(String value) {
			assertDegraded("concat", value);
		}

		/** Both the synthesized unigram and bigrams clear custom attributes. */
		@ParameterizedTest
		@ValueSource(strings = {"一", "一丁丂"})
		@DisplayName("should skip cleared capture for synthesized CJK tokens and remove symmetrically")
		void shouldDegradeWhenCaptureIsCleared(String value) {
			assertDegraded("cjk", value);
		}

		/** Buffering reads dogs before restoring cards, so the oracle must not share capture implementation. */
		@Test
		@DisplayName("should restore buffered captures for distinct inputs")
		void shouldRestoreBufferedCaptures() {
			try (final FulltextAnalyzerRegistry registry = pluginRegistry()) {
				final CompiledFulltextAnalyzer analyzer = pluginAnalyzer(registry, "buffer");
				final CompiledFulltextAnalyzer prefix = registry.getCompiledAnalyzer(
					new InternalCatalogSchemaBuilder(SCHEMA).withFulltextAnalyzer("prefix",
						profile -> profile.words(lowercase())).toInstance(), "prefix", Locale.ENGLISH);
				final List<String> inputs = collectTerms(prefix.indexSurface(), "cards dogs").stream()
					.map(AnalyzedTerm::term).toList();
				assertEquals(List.of("cards", "dogs"), inputs);
				final List<String> captured = new ArrayList<>();
				analyzer.indexSurface().analyzeIndexed("cards dogs", (term, folded, display, start, end, increment) ->
					captured.add(folded == null ? null : folded.toString()));
				assertEquals(inputs, captured);
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, analyzer, 25, null, "cards dogs");
				assertEquals(Map.of("cards\0card", 1L, "dogs\0dog", 1L),
					surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()));
				index.addValue(attribute("name"), 2, analyzer, 25, null, "cats birds");
				assertEquals(Map.of("cards\0card", 1L, "dogs\0dog", 1L, "cats\0cat", 1L, "birds\0bird", 1L),
					surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()));
				index.removeValue(attribute("name"), 1, analyzer, 25, "cards dogs");
				assertEquals(Map.of("cats\0cat", 1L, "birds\0bird", 1L),
					surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()));
				index.removeValue(attribute("name"), 2, analyzer, 25, "cats birds");
				assertTrue(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).isEmpty());
			}
		}
	}

	/** Capture storage is observable through identity and state, without allocation measurements. */
	@Nested
	@DisplayName("Reusable capture")
	class ReusableCapture {
		/** Tail capture must share the final posting string rather than making another. */
		@Test
		@DisplayName("should reuse each final term without stemming")
		void shouldReuseFinalTerm() {
			final int[] count = new int[1];
			exactAnalyzer().indexSurface().analyzeIndexed("cafe cafe cafe",
				(term, folded, display, start, end, increment) -> {
					assertSame(term, folded);
					count[0]++;
				});
			assertEquals(3, count[0]);
		}

		/** Stemming capture remains one mutable character view, with value-level pair deduplication. */
		@Test
		@DisplayName("should reuse capture characters and count repeated pairs only once per value")
		void shouldReuseCharacterCapture() {
			try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(SCHEMA, "text", Locale.ENGLISH);
				final CharSequence[] first = new CharSequence[1];
				analyzer.indexSurface().analyzeIndexed("cards cards cards",
					(term, folded, display, start, end, increment) -> {
						assertNotNull(folded);
						assertFalse(folded instanceof String);
						if (first[0] == null) {
							first[0] = folded;
						} else {
							assertSame(first[0], folded);
						}
						assertEquals("cards", folded.toString());
					});
				final FulltextIndex index = new FulltextIndex();
				index.addValue(attribute("name"), 1, analyzer, 25, null, "cards cards cards");
				assertEquals(Map.of("cards\0card", 1L),
					surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()));
				index.removeValue(attribute("name"), 1, analyzer, 25, "cards cards cards");
				assertTrue(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).isEmpty());
			}
		}
	}

	/** Checks the unchanged standard posting attributes and symmetric removal when no capture survives. */
	private static void assertDegraded(String plugin, String value) {
		try (final FulltextAnalyzerRegistry registry = pluginRegistry();
			final FulltextAnalyzer uncaptured = new FulltextAnalyzer("uncaptured",
				new FulltextChainCompiler(pluginSteps()).compile(ResolvedFulltextAnalyzer.resolve(
					pluginSchema(plugin), "plugin", Locale.ENGLISH), FulltextAnalysisSide.INDEX))) {
			final CompiledFulltextAnalyzer analyzer = pluginAnalyzer(registry, plugin);
			final List<AnalyzedTerm> expected = collectTerms(uncaptured, value);
			assertFalse(expected.isEmpty());
			assertEquals(expected, collectTerms(analyzer.indexSurface(), value));
			final FulltextIndex index = new FulltextIndex();
			assertDoesNotThrow(() -> index.addValue(attribute("name"), 1, analyzer, 25, null, value));
			assertTrue(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).isEmpty());
			for (final AnalyzedTerm term : expected) {
				assertArrayEquals(new int[]{1}, postingsOf(index, 0, term.term()).getArray());
			}
			index.removeValue(attribute("name"), 1, analyzer, 25, value);
			assertTrue(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).isEmpty());
			for (final AnalyzedTerm term : expected) {
				assertTrue(postingsOf(index, 0, term.term()).isEmpty());
			}
		}
	}

	/** Uses real extension providers and the normal shared analyzer registry, with every propagation plugin. */
	private static FulltextAnalyzerRegistry pluginRegistry() {
		return new FulltextAnalyzerRegistry(pluginSteps());
	}

	/** Adds a downstream index-only filter after real English stemming. */
	private static CompiledFulltextAnalyzer pluginAnalyzer(FulltextAnalyzerRegistry registry, String plugin) {
		return registry.getCompiledAnalyzer(pluginSchema(plugin), "plugin", Locale.ENGLISH);
	}

	/** Supplies independent operation instances to both captured and uncaptured compilers. */
	private static AnalysisStepRegistry pluginSteps() {
		final List<AnalysisStepFactory> factories = new ArrayList<>(java.util.ServiceLoader.load(AnalysisStepFactory.class)
			.stream().map(java.util.ServiceLoader.Provider::get).toList());
		factories.add(new PluginWordsFactory("concat"));
		factories.add(new PluginWordsFactory("cjk"));
		factories.add(new PluginWordsFactory("buffer"));
		return new AnalysisStepRegistry(factories);
	}

	/** Uses the same resolved profile content for the independently compiled posting oracle. */
	private static io.evitadb.api.requestResponse.schema.CatalogSchemaContract pluginSchema(String plugin) {
		return new InternalCatalogSchemaBuilder(SCHEMA)
			.withFulltextAnalyzer("plugin", profile -> profile.words(lowercase(), englishStemming(),
				wordsStep(PluginWordsFactory.PREFIX + plugin))).toInstance();
	}

	/** Restores actual emitted pages after surface leaves pass through their registered Kryo serializer. */
	@Nonnull
	private static FulltextIndex reload(
		@Nonnull FulltextIndex source, @Nullable FulltextIndex.SurfaceFormPage replacement,
		@Nonnull String... expectedEntries
	) {
		final PageEmission<FulltextIndex.DictionaryPage> pages = source.collectChangedPages();
		final FieldLengthTable.LengthBlockEmission[] lengths = source.collectChangedLengthBlocks();
		final List<FulltextIndex.Field> fields = new ArrayList<>(lengths.length);
		for (int field = 0; field < lengths.length; field++) {
			fields.add(new FulltextIndex.Field(source.getFieldKey(field), source.getAnalyzerName(field),
				source.getIndexFingerprint(field), source.getWordPhaseId(field), source.getLengthPivot(field),
				source.getTextExpression(field), source.isFieldRetired(field),
				FieldLengthTable.fromPersistedBlocks(lengths[field].changedBlocks()
					.toArray(FieldLengthTable.LengthBlock[]::new))));
		}
		final PageEmission<FulltextIndex.SurfaceFormPage> surfaces = source.collectChangedSurfaceFormPages();
		final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
		final Kryo kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(compressor));
		final FulltextIndex.SurfaceFormPage[] decoded =
			new FulltextIndex.SurfaceFormPage[surfaces.orderedPageSequences().length];
		for (int i = 0; i < decoded.length; i++) {
			final FulltextIndex.SurfaceFormPage page = replacement == null ? surfaces.changedPages().get(i)
				: new FulltextIndex.SurfaceFormPage(surfaces.orderedPageSequences()[i],
					replacement.keys(), replacement.referenceCounts());
			final FulltextSurfaceFormLeafPagePart part = new FulltextSurfaceFormLeafPagePart(3, Locale.ROOT, page);
			part.computeUniquePartIdAndSet(compressor);
			decoded[i] = roundTrip(kryo, part, FulltextSurfaceFormLeafPagePart.class).getPage();
			assertArrayEquals(page.keys(), decoded[i].keys(), "The display and escaped components must survive.");
		}
		if (expectedEntries.length > 0) {
			assertEquals(List.of(expectedEntries), persistedSurfaceEntries(List.of(decoded), 0));
		}
		return FulltextIndex.fromPersistedPages(fields, pages.orderedPageSequences(),
			pages.changedPages().toArray(FulltextIndex.DictionaryPage[]::new), pages.highWaterPageSequence(),
			source.getWordPhaseIds(), surfaces.orderedPageSequences(), decoded, surfaces.highWaterPageSequence());
	}

	/** Real extension fixture emitting an identity and a shortened stem, both at the original position. */
	private static final class MultiStemFactory implements FulltextWordsAnalysisStepFactory {
		@Override
		public String getName() { return "test-multi-stem"; }
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }
		@Override
		public void validate(Map<String, Serializable> parameters) { }
		@Override
		public Set<FulltextAnalysisSide> getSides() { return Set.of(FulltextAnalysisSide.INDEX); }
		@Override
		public Set<String> getIndexSideParameters() { return Set.of(); }
		@Override
		public int getBehaviourVersion() { return 1; }
		@Override
		public FulltextWordsAnalysisOperation create(
			Map<String, Serializable> parameters, FulltextAnalysisContext context
		) {
			return new FulltextWordsAnalysisOperation() {
				@Override
				public Set<FulltextAnalysisTrait> getTraits(FulltextAnalysisSide side) {
					return Set.of(FulltextAnalysisTrait.STEMS);
				}
				@Override
				public TokenStream wrap(TokenStream words, FulltextAnalysisSide side) {
					return new MultiStemFilter(words);
				}
			};
		}
	}

	/** A real index text step that expands `ß` to `ss` and, like a careless plugin, does not correct offsets. */
	private static final class SharpSExpansionFactory implements FulltextTextAnalysisStepFactory {
		/** The step name the fixture profile refers to. */
		static final String NAME = "test-sharp-s-expansion";

		@Override
		public String getName() { return NAME; }
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }
		@Override
		public void validate(Map<String, Serializable> parameters) { }
		@Override
		public Set<FulltextAnalysisSide> getSides() { return Set.of(FulltextAnalysisSide.INDEX); }
		@Override
		public Set<String> getIndexSideParameters() { return Set.of(); }
		@Override
		public int getBehaviourVersion() { return 1; }
		@Override
		public FulltextTextAnalysisOperation create(
			Map<String, Serializable> parameters, FulltextAnalysisContext context
		) {
			return (text, side) -> expandSharpS(text);
		}

		/** Reads the whole text and returns it with every `ß` replaced by `ss`, offsets left uncorrected. */
		private static Reader expandSharpS(Reader text) {
			try {
				final StringWriter content = new StringWriter(64);
				text.transferTo(content);
				return new StringReader(content.toString().replace("ß", "ss"));
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
	}

	/** Deliberately exercises three standard Lucene propagation behaviors. */
	private record PluginWordsFactory(String mode) implements FulltextWordsAnalysisStepFactory {
		/** Prefix of the step names, followed by the mode. */
		static final String PREFIX = "test-surface-";

		@Override
		public String getName() { return PREFIX + this.mode; }
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }
		@Override
		public void validate(Map<String, Serializable> parameters) { }
		@Override
		public Set<FulltextAnalysisSide> getSides() { return Set.of(FulltextAnalysisSide.INDEX); }
		@Override
		public Set<String> getIndexSideParameters() { return Set.of(); }
		@Override
		public int getBehaviourVersion() { return 1; }
		@Override
		public FulltextWordsAnalysisOperation create(
			Map<String, Serializable> parameters, FulltextAnalysisContext context
		) {
			return (words, side) -> switch (this.mode) {
				case "concat" -> new ConcatenateGraphFilter(words);
				case "cjk" -> new CJKBigramFilter(words);
				case "buffer" -> new BufferedTokensFilter(words);
				default -> throw new IllegalArgumentException(this.mode);
			};
		}
	}

	/** Reads distinct inputs before restoring their states, with a fresh buffer for every analyzed value. */
	private static final class BufferedTokensFilter extends TokenFilter {
		private final List<AttributeSource.State> states = new ArrayList<>();
		private boolean loaded;
		private int position;

		/** Wraps the real index stream after stemming. */
		private BufferedTokensFilter(TokenStream input) {
			super(input);
		}

		@Override
		public void reset() throws IOException {
			super.reset();
			this.states.clear();
			this.loaded = false;
			this.position = 0;
		}

		@Override
		public boolean incrementToken() throws IOException {
			if (!this.loaded) {
				while (this.input.incrementToken()) {
					this.states.add(captureState());
				}
				this.loaded = true;
			}
			if (this.position == this.states.size()) {
				return false;
			}
			restoreState(this.states.get(this.position++));
			return true;
		}
	}

	/** Uses normal Lucene capture/restore semantics to fan out one indexed token. */
	private static final class MultiStemFilter extends TokenFilter {
		private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
		private final PositionIncrementAttribute position = addAttribute(PositionIncrementAttribute.class);
		private AttributeSource.State pending;

		/** Wraps the actual index token stream. */
		private MultiStemFilter(TokenStream input) { super(input); }

		@Override
		public void reset() throws IOException {
			super.reset();
			this.pending = null;
		}

		@Override
		public boolean incrementToken() throws IOException {
			if (this.pending != null) {
				restoreState(this.pending);
				this.pending = null;
				this.term.setLength(Math.max(0, this.term.length() - 1));
				this.position.setPositionIncrement(0);
				return true;
			}
			if (!this.input.incrementToken()) {
				return false;
			}
			this.pending = captureState();
			return true;
		}
	}

	/** Uses the same index definition with changed query-only resource contents. */
	private static void assertSynonymEdit(String[] before, String[] after) {
		try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
			final CompiledFulltextAnalyzer original = registry.getCompiledAnalyzer(synonymSchema(before),
				"aliases", Locale.ROOT);
			final CompiledFulltextAnalyzer changed = registry.getCompiledAnalyzer(synonymSchema(after),
				"aliases", Locale.ROOT);
			assertEquals(original.fingerprint(), changed.fingerprint());
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("name"), 1, original, 25, null, "car");
			index.addValue(attribute("name"), 2, original, 25, null, "car");
			assertEquals(Map.of("car\0car", 2L), surfaceReferenceCounts(index, original.fingerprint().wordPhase()));
			index.removeValue(attribute("name"), 1, changed, 25, "car");
			assertEquals(Map.of("car\0car", 1L), surfaceReferenceCounts(index, original.fingerprint().wordPhase()));
			index.removeValue(attribute("name"), 2, changed, 25, "car");
			assertEquals(Map.of(), surfaceReferenceCounts(index, original.fingerprint().wordPhase()));
			assertTrue(postingsOf(index, index.getFieldId(attribute("name")), "car").isEmpty());
		}
	}

	/** Builds a real synonym resource on a query-only step. */
	private static CatalogSchemaContract synonymSchema(String[] words) {
		return new InternalCatalogSchemaBuilder(SCHEMA)
			.withFulltextResource("aliases", synonymList(synonymGroup(words)))
			.withFulltextAnalyzer("aliases", profile -> profile.words(lowercase(), synonyms(resource("aliases"))))
			.toInstance();
	}

	/** Enables the language splitter that preserves the concatenation and emits its subparts. */
	private static InternalCatalogSchemaBuilder splitSchema() {
		final InternalCatalogSchemaBuilder schema = new InternalCatalogSchemaBuilder(SCHEMA);
		schema.withFulltextLanguage("en", language -> language.wordNumberSplit());
		return schema;
	}

	/** Checks both the lane pairs and the postings of every actual split token. */
	private static void assertSplitPairs(CatalogSchemaContract schema, String text) {
		try (final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
			final CompiledFulltextAnalyzer analyzer = registry.getCompiledAnalyzer(schema, "split", Locale.ENGLISH);
			final FulltextIndex index = new FulltextIndex();
			index.addValue(attribute("name"), 1, analyzer, 25, null, text);
			assertEquals(Set.of("ab\0ab", "12" + '\0' + "12", "ab12\0ab12"),
				surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).keySet());
			for (final String term : Set.of("ab", "12", "ab12")) {
				final FulltextIndex.SurfaceFormCursor cursor =
					index.exactSurfaceFormCursor(analyzer.fingerprint().wordPhase(), term);
				assertTrue(cursor.next());
				assertEquals(term, folded(cursor));
				assertEquals(term, stem(cursor));
				assertFalse(cursor.next());
				assertArrayEquals(new int[]{1},
					postingsOf(index, index.getFieldId(attribute("name")), term).getArray());
			}
			index.removeValue(attribute("name"), 1, analyzer, 25, text);
			assertTrue(surfaceReferenceCounts(index, analyzer.fingerprint().wordPhase()).isEmpty());
		}
	}
}
