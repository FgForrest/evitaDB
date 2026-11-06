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


package io.evitadb.index.fulltext.analysis;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.*;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.dataType.*;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.analysis.LowercaseAnalysisStepFactory;
import io.evitadb.spi.analysis.*;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract.HTML_TEXT;
import static io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract.TEXT;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies persistent fulltext identities on definitions resolved from real catalog schemas. */
@DisplayName("Fulltext analyzer fingerprint")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class FulltextAnalyzerFingerprintTest {
	/**
	 * The installed built-in providers.
	 */
	private static final AnalysisStepRegistry INSTALLED = new AnalysisStepRegistry();
	/**
	 * The compiler used for built-in definitions.
	 */
	private static final FulltextChainCompiler COMPILER = new FulltextChainCompiler(INSTALLED);
	/**
	 * The consequence of an accidental encoding or behaviour change.
	 */
	private static final String GOLDEN_MESSAGE =
		"A changed value makes every stored field stale: bump the behaviour version of the step whose index terms " +
			"changed and re-pin deliberately, or revert.";

	/** Literal hashes protect the persisted contract across JVMs and releases. */
	@Nested
	@DisplayName("Golden values")
	class GoldenValues {
		/** Built-in identities stay pinned, and the text-only difference never reaches the words identity. */
		@ParameterizedTest(name = "{0}")
		@CsvSource({
			"cs, -1705890908168216171, -1376749510378306985, -8987484836907473605",
			"sk, 5064537545457944621, -6641921392690437784, 2053546552033886871",
			"pl, -2276050775374623463, 3427560589008338612, 5452015047685618542",
			"ro, -8576959782079232694, 984409283076930610, 3323567866629094995",
			"en, 2705306358525940611, 2306383905716444064, -5695288295780142967",
			"de, 875488786346163094, -7515703116387783617, 225311355206215435",
			"root, 8057872479271164115, -2391864800070616011, 4502002754307684964"
		})
		@DisplayName("should preserve built-in fingerprints and share words across text phases")
		void shouldPreserveBuiltInFingerprints(@Nonnull String language, long textIndex, long htmlIndex, long words) {
			final Locale locale = "root".equals(language) ? Locale.ROOT : Locale.forLanguageTag(language);
			final CatalogSchemaContract schema = catalog(b -> {});
			final FulltextAnalyzerFingerprint text = fingerprint(schema, TEXT, locale);
			final FulltextAnalyzerFingerprint html = fingerprint(schema, HTML_TEXT, locale);
			assertAll(
				() -> assertEquals(textIndex, text.index(), GOLDEN_MESSAGE),
				() -> assertEquals(htmlIndex, html.index(), GOLDEN_MESSAGE),
				() -> assertEquals(words, text.wordPhase(), GOLDEN_MESSAGE),
				() -> assertEquals(words, html.wordPhase(), GOLDEN_MESSAGE),
				() -> assertNotEquals(text.index(), html.index()),
				() -> assertEquals(text.wordPhase(), html.wordPhase())
			);
		}

		/** Every permitted scalar and object/primitive array branch has a literal persisted identity. */
		@Test
		@DisplayName("should preserve scalar and array encoding fingerprints")
		void shouldPreserveScalarAndArrayFingerprints() {
			final FulltextAnalyzerFingerprint fingerprint = providerCompiler(new ScalarFingerprintProvider())
				.fingerprint(sample(scalarStep()));
			assertAll(
				() -> assertEquals(9185522079273042019L, fingerprint.index(), GOLDEN_MESSAGE),
				() -> assertEquals(5421614544913488071L, fingerprint.wordPhase(), GOLDEN_MESSAGE)
			);
		}

		/** A language-dispatched stemmer pins the non-empty delegate encoding. */
		@Test
		@DisplayName("should preserve stemming delegate fingerprints")
		void shouldPreserveStemmingDelegateFingerprints() {
			final CatalogSchemaContract schema = catalog(b -> b.withFulltextAnalyzer("sample",
				a -> a.words(lowercase(), stemming())));
			final FulltextAnalyzerFingerprint fingerprint = fingerprint(schema, "sample", Locale.forLanguageTag("cs"));
			assertAll(
				() -> assertEquals(7327516152101548197L, fingerprint.index(), GOLDEN_MESSAGE),
				() -> assertEquals(8455269067258995786L, fingerprint.wordPhase(), GOLDEN_MESSAGE)
			);
		}

		/** Built-in text parameters pin raw scalar strings independently of the words phase. */
		@Test
		@DisplayName("should preserve remove-characters text fingerprints")
		void shouldPreserveRemoveCharactersFingerprints() {
			final CatalogSchemaContract schema = catalog(b -> b.withFulltextAnalyzer("sample",
				a -> a.text(removeCharacters("-_'")).words(lowercase())));
			final FulltextAnalyzerFingerprint fingerprint = fingerprint(schema, "sample", Locale.ROOT);
			assertAll(
				() -> assertEquals(-5767369857033757871L, fingerprint.index(), GOLDEN_MESSAGE),
				() -> assertEquals(4502002754307684964L, fingerprint.wordPhase(), GOLDEN_MESSAGE)
			);
		}

	}

	/** Inputs that affect index terms must change the identities of the affected phases. */
	@Nested
	@DisplayName("Index changes")
	class IndexChanges {
		/** Resource content, rather than its unchanged name, decides the identity. */
		@Test
		@DisplayName("should change both values when stop or protected words change")
		void shouldChangeWhenResourceContentChanges() {
			for (final WordsAnalysisStep step : List.of(
				stopWords(resource("list")), stemming(protectedWords(resource("list")))
			)) {
				final FulltextAnalyzerFingerprint first = withWordsAndResource(step, "list", wordList("first"));
				final FulltextAnalyzerFingerprint second = withWordsAndResource(step, "list", wordList("second"));
				assertBothDiffer(first, second);
			}
		}

		/** The words phase is an ordered sequence, including every selected step. */
		@Test
		@DisplayName("should change both values when words steps are added removed or reordered")
		void shouldChangeWhenWordsChange() {
			final FulltextAnalyzerFingerprint lower = words(lowercase());
			final FulltextAnalyzerFingerprint folded = words(lowercase(), asciiFolding());
			assertBothDiffer(lower, folded);
			assertBothDiffer(folded, words(asciiFolding(), lowercase()));
			assertBothDiffer(lower, words());
		}

		/** Text and splitting precede the words phase and do not identify it. */
		@Test
		@DisplayName("should change only the index fingerprint for text and splitting changes")
		void shouldChangeOnlyIndexForEarlierPhases() {
			final FulltextAnalyzerFingerprint plain = words(lowercase());
			final FulltextAnalyzerFingerprint text = fingerprint(catalog(b -> b.withFulltextAnalyzer(
				"sample", a -> a.text(htmlStrip()).words(lowercase())
			)), "sample", Locale.ROOT);
			final FulltextAnalyzerFingerprint splitting = fingerprint(catalog(b -> b
				.withFulltextLanguage("en", l -> l.wordNumberSplit())
				.withFulltextAnalyzer("sample", a -> a.words(lowercase()))), "sample", Locale.ENGLISH);
			assertNotEquals(plain.index(), text.index());
			assertNotEquals(plain.index(), splitting.index());
			assertEquals(plain.wordPhase(), text.wordPhase());
			assertEquals(plain.wordPhase(), splitting.wordPhase());
		}

		/** The value of a text step's index-side parameter reaches the index fingerprint, never the words identity. */
		@Test
		@DisplayName("should change only the index fingerprint when a text step parameter changes")
		void shouldChangeOnlyIndexForTextParameters() {
			final FulltextAnalyzerFingerprint dash = fingerprint(catalog(b -> b.withFulltextAnalyzer(
				"sample", a -> a.text(removeCharacters("-")).words(lowercase())
			)), "sample", Locale.ROOT);
			final FulltextAnalyzerFingerprint underscore = fingerprint(catalog(b -> b.withFulltextAnalyzer(
				"sample", a -> a.text(removeCharacters("_")).words(lowercase())
			)), "sample", Locale.ROOT);

			assertNotEquals(dash.index(), underscore.index());
			assertEquals(dash.wordPhase(), underscore.wordPhase());
		}

		/** Both direct versions and recursively delegated versions describe executed behaviour. */
		@Test
		@DisplayName("should change both values for provider or recursive delegate versions")
		void shouldChangeWhenVersionsChange() {
			final ResolvedFulltextAnalyzer resolved = sample(FingerprintWordsProvider.step());
			assertBothDiffer(
				providerCompiler(new FingerprintWordsProvider(1, List.of(), null)).fingerprint(resolved),
				providerCompiler(new FingerprintWordsProvider(2, List.of(), null)).fingerprint(resolved)
			);
			final FulltextWordsAnalysisStepFactory firstDelegate = new FingerprintWordsProvider(1, List.of(), null) {
				@Nonnull
				@Override
				public String getName() { return "test-fingerprint-leaf"; }
			};
			final FulltextWordsAnalysisStepFactory secondDelegate = new FingerprintWordsProvider(2, List.of(), null) {
				@Nonnull
				@Override
				public String getName() { return "test-fingerprint-leaf"; }
			};
			final FulltextWordsAnalysisStepFactory firstMiddle = delegateParent(firstDelegate);
			final FulltextWordsAnalysisStepFactory secondMiddle = delegateParent(secondDelegate);
			assertBothDiffer(
				providerCompiler(new FingerprintWordsProvider(1, List.of(firstMiddle), null)).fingerprint(resolved),
				providerCompiler(new FingerprintWordsProvider(1, List.of(secondMiddle), null)).fingerprint(resolved)
			);
		}

		/** Locale itself is absent; only the language-selected delegate distinguishes dispatch. */
		@Test
		@DisplayName("should distinguish stemming delegates and equate languages without one")
		void shouldIdentifyStemmingDelegates() {
			final CatalogSchemaContract schema = catalog(b -> b.withFulltextAnalyzer(
				"sample", a -> a.words(lowercase(), stemming())
			));
			assertBothDiffer(fingerprint(schema, "sample", Locale.forLanguageTag("cs")),
				fingerprint(schema, "sample", Locale.forLanguageTag("sk")));
			assertEquals(fingerprint(schema, "sample", Locale.forLanguageTag("fi")),
				fingerprint(schema, "sample", Locale.forLanguageTag("fr")));
		}
	}

	/** Definition edits unrelated to the executed index behaviour keep both values. */
	@Nested
	@DisplayName("Equivalent index definitions")
	class EquivalentDefinitions {
		/** Instances, analyzer names and parameter declaration order are not part of either stream. */
		@Test
		@DisplayName("should ignore object identity analyzer names and parameter declaration order")
		void shouldIgnoreNamesAndParameterOrder() {
			final FulltextChainCompiler compiler = providerCompiler(new FingerprintWordsProvider(1, List.of(), null));
			final CatalogSchemaContract first = catalog(b -> b.withFulltextAnalyzer("first", a -> a.words(
				FingerprintWordsProvider.step(parameter("first", new String("a")), parameter("second", new int[]{1, 2}))
			)));
			final CatalogSchemaContract second = catalog(b -> b.withFulltextAnalyzer("second", a -> a.words(
				FingerprintWordsProvider.step(parameter("second", new int[]{1, 2}), parameter("first", new String("a")))
			)));
			assertEquals(compiler.fingerprint(ResolvedFulltextAnalyzer.resolve(first, "first", Locale.ROOT)),
				compiler.fingerprint(ResolvedFulltextAnalyzer.resolve(second, "second", Locale.ROOT)));
		}

		/** A query-only words step and the resource it reads cannot retire index fields. */
		@Test
		@DisplayName("should ignore synonyms steps and resource content")
		void shouldIgnoreSynonyms() {
			final FulltextAnalyzerFingerprint plain = words(lowercase());
			for (final String synonym : List.of("television", "screen")) {
				assertEquals(plain, withWordsAndResource(synonyms(resource("list")), "list",
					synonymList(synonymGroup("tv", synonym))));
			}
		}

		/** A query-only text provider is validated but never encoded. */
		@Test
		@DisplayName("should ignore query-only text steps")
		void shouldIgnoreQueryOnlyText() {
			final CatalogSchemaContract schema = catalog(b -> b.withFulltextAnalyzer("sample", a -> a
				.text(textStep("test-fingerprint-text")).words(lowercase())));
			assertEquals(words(lowercase()), providerCompiler(new FingerprintTextProvider()).fingerprint(
				ResolvedFulltextAnalyzer.resolve(schema, "sample", Locale.ROOT)));
		}

		/** Resource names are lookup keys; unrelated content never enters the stream. */
		@Test
		@DisplayName("should ignore resource renames and unrelated resource changes")
		void shouldIgnoreResourceNamesAndUnrelatedContent() {
			final FulltextAnalyzerFingerprint original = withWordsAndResource(
				stopWords(resource("first")), "first", wordList("same"));
			assertEquals(original, withWordsAndResource(stopWords(resource("second")), "second", wordList("same")));
			for (final String unrelated : List.of("a", "b")) {
				final CatalogSchemaContract schema = catalog(b -> b
					.withFulltextResource("first", wordList("same"))
					.withFulltextResource("unrelated", wordList(unrelated))
					.withFulltextAnalyzer("sample", a -> a.words(lowercase(), stopWords(resource("first")))));
				assertEquals(original, fingerprint(schema, "sample", Locale.ROOT));
			}
		}

		/** A resolved chain has no dependency on the overrides of other languages, though the override takes effect. */
		@Test
		@DisplayName("should ignore another language override")
		void shouldIgnoreOtherLanguageOverride() {
			final CatalogSchemaContract schema = catalog(b -> b.withFulltextAnalyzer("sample", a -> a
				.words(lowercase()).language("de", l -> l.words(asciiFolding()))));
			assertEquals(words(lowercase()), fingerprint(schema, "sample", Locale.forLanguageTag("cs")));
			assertBothDiffer(words(lowercase()), fingerprint(schema, "sample", Locale.forLanguageTag("de")));
		}

		/** Even declared values stay out when the provider declares them query-side only. */
		@Test
		@DisplayName("should ignore parameters absent from index-side declarations")
		void shouldIgnoreQueryParameters() {
			final FulltextChainCompiler compiler = providerCompiler(new FingerprintWordsProvider(1, List.of(), null));
			assertEquals(compiler.fingerprint(sample(FingerprintWordsProvider.step(parameter("query", "a")))),
				compiler.fingerprint(sample(FingerprintWordsProvider.step(parameter("query", "b")))));
		}
	}

	/** Encoding boundaries, scalar support and third-party resource declarations. */
	@Nested
	@DisplayName("Canonical encoding")
	class CanonicalEncoding {
		/** Independently constructed values and arrays do not depend on object identity. */
		@Test
		@DisplayName("should encode all accepted scalar and array types independently of identity")
		void shouldEncodeAllSupportedTypes() {
			final FulltextChainCompiler compiler = providerCompiler(new ScalarFingerprintProvider());
			assertEquals(compiler.fingerprint(sample(scalarStep())), compiler.fingerprint(sample(scalarStep())));
		}

		/** Length and type tags distinguish values that bare concatenation or lossy text would equate. */
		@Test
		@DisplayName("should preserve boundaries types precision and surrogate code units")
		void shouldPreserveDistinctValues() {
			final List<Serializable[]> pairs = List.of(
				new Serializable[]{new String[]{"ab", "c"}, new String[]{"a", "bc"}},
				new Serializable[]{"1", 1}, new Serializable[]{1, 1L},
				new Serializable[]{new BigDecimal("1.0"), new BigDecimal("1.00")},
				new Serializable[]{new int[]{1, 2}, new Integer[]{1, 2}},
				new Serializable[]{LocalTime.parse("12:00:00.000000001"), LocalTime.parse("12:00:00.000000002")},
				new Serializable[]{LocalDateTime.parse("2026-01-01T12:00:00.000000001"),
					LocalDateTime.parse("2026-01-01T12:00:00.000000002")},
				new Serializable[]{OffsetDateTime.parse("2026-01-01T12:00:00.000000001Z"),
					OffsetDateTime.parse("2026-01-01T12:00:00.000000002Z")},
				new Serializable[]{String.valueOf((char) 0xd800), String.valueOf((char) 0xd801)},
				new Serializable[]{Locale.of("en", "USA"), Locale.ENGLISH},
				new Serializable[]{Locale.of("123"), Locale.ROOT}
			);
			for (final Serializable[] pair : pairs) {
				assertBothDiffer(
					providerCompiler(typedProvider(pair[0].getClass().asSubclass(Serializable.class)))
						.fingerprint(sample(FingerprintWordsProvider.step(parameter("first", pair[0])))),
					providerCompiler(typedProvider(pair[1].getClass().asSubclass(Serializable.class)))
						.fingerprint(sample(FingerprintWordsProvider.step(parameter("first", pair[1])))));
			}
		}

		/** Third-party resource metadata works for scalar and array names, including synonym lists. */
		@Test
		@DisplayName("should use declared resource kinds and preserve raw list and group order")
		void shouldUseThirdPartyResourceMetadata() {
			for (final FulltextResourceKind kind : FulltextResourceKind.values()) {
				final FulltextChainCompiler compiler =
					providerCompiler(new FingerprintWordsProvider(1, List.of(), kind));
				final FulltextResourceContent first = kind == FulltextResourceKind.WORD_LIST ? wordList("ab", "c") :
					synonymList(synonymGroup("ab", "c"), synonymGroup("d", "e"));
				final FulltextResourceContent reordered = kind == FulltextResourceKind.WORD_LIST ? wordList("c", "ab") :
					synonymList(synonymGroup("d", "e"), synonymGroup("ab", "c"));
				final FulltextResourceContent changed = kind == FulltextResourceKind.WORD_LIST ? wordList("a", "bc") :
					synonymList(synonymGroup("c", "ab"), synonymGroup("d", "e"));
				for (final String parameter : List.of("first", "second")) {
					final Serializable name = "first".equals(parameter) ? "list" : new String[]{"list"};
					final WordsAnalysisStep step = FingerprintWordsProvider.step(parameter(parameter, name));
					final FulltextAnalyzerFingerprint original =
						compiler.fingerprint(resourceSample(step, "list", first));
					assertBothDiffer(original, compiler.fingerprint(resourceSample(step, "list", reordered)));
					assertBothDiffer(original, compiler.fingerprint(resourceSample(step, "list", changed)));
					final Serializable renamed = "first".equals(parameter) ? "renamed" : new String[]{"renamed"};
					assertEquals(original, compiler.fingerprint(resourceSample(
						FingerprintWordsProvider.step(parameter(parameter, renamed)), "renamed", first)));
				}
			}
		}

		/** Synonym groups holding the same words in the same order still differ by where one group ends. */
		@Test
		@DisplayName("should distinguish synonym groups sharing their words in order")
		void shouldPreserveSynonymGroupBoundaries() {
			final FulltextChainCompiler compiler =
				providerCompiler(new FingerprintWordsProvider(1, List.of(), FulltextResourceKind.SYNONYM_LIST));
			final WordsAnalysisStep step = FingerprintWordsProvider.step(parameter("first", "list"));

			final FulltextAnalyzerFingerprint shortFirst = compiler.fingerprint(resourceSample(step, "list",
				synonymList(synonymGroup("a", "b"), synonymGroup("c", "d", "e"))));
			final FulltextAnalyzerFingerprint longFirst = compiler.fingerprint(resourceSample(step, "list",
				synonymList(synonymGroup("a", "b", "c"), synonymGroup("d", "e"))));

			assertBothDiffer(shortFirst, longFirst);
		}
	}

	/** User errors match compilation; impossible provider values and cycles are internal errors. */
	@Nested
	@DisplayName("Errors and SPI metadata")
	class ErrorsAndMetadata {
		/** Unknown steps, wrong phases and invalid parameters are usage errors. */
		@Test
		@DisplayName("should reject unknown steps wrong phases and invalid parameters")
		void shouldRejectInvalidSteps() {
			for (final WordsAnalysisStep step : List.of(wordsStep("unknown"), wordsStep(HTML_STRIP),
				wordsStep(LOWERCASE, parameter("unknown", "value")))) {
				assertThrows(EvitaInvalidUsageException.class, () -> COMPILER.fingerprint(sample(step)));
			}
			final ResolvedFulltextAnalyzer resolved = sample(lowercase());
			assertThrows(EvitaInvalidUsageException.class, () -> COMPILER.fingerprint(new ResolvedFulltextAnalyzer(
				resolved.analyzer(), resolved.text(), List.of(), resolved.words(), resolved.context())));
			assertThrows(EvitaInvalidUsageException.class, () -> COMPILER.fingerprint(new ResolvedFulltextAnalyzer(
				resolved.analyzer(), List.of(lowercase()), resolved.splitting(), resolved.words(),
				resolved.context())));
			assertThrows(EvitaInvalidUsageException.class, () -> COMPILER.fingerprint(new ResolvedFulltextAnalyzer(
				resolved.analyzer(), resolved.text(), List.of(lowercase()), resolved.words(), resolved.context())));
			assertThrows(EvitaInvalidUsageException.class, () -> COMPILER.fingerprint(new ResolvedFulltextAnalyzer(
				resolved.analyzer(), resolved.text(), List.of(standardTokenizer(), lowercase()), resolved.words(),
				resolved.context())));
		}

		/** Resource resolution uses the context's missing-name refusal. */
		@Test
		@DisplayName("should reject a missing resource")
		void shouldRejectMissingResource() {
			assertThrows(EvitaInvalidUsageException.class,
				() -> COMPILER.fingerprint(sample(stopWords(resource("missing")))));
		}

		/** Wrong resource kinds are usage errors, just as when a built-in operation reads them. */
		@Test
		@DisplayName("should reject the wrong resource kind")
		void shouldRejectWrongResourceKind() {
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> COMPILER.fingerprint(resourceSample(
					stopWords(resource("list")), "list", synonymList(synonymGroup("a", "b")))));
			assertEquals("Fulltext resource `list` named by parameter `words` of step `stop-words` " +
				"is a synonym list, not a word list.", error.getMessage());
		}

		/** Recursion follows the current path, so cycles fail while repeated independent delegates are allowed. */
		@Test
		@DisplayName("should reject delegate cycles and allow a shared delegate")
		void shouldRejectDelegateCycles() {
			final List<FulltextAnalysisStepFactory> delegates = new ArrayList<>();
			final FingerprintWordsProvider provider = new FingerprintWordsProvider(1, delegates, null);
			delegates.add(provider);
			assertThrows(GenericEvitaInternalError.class,
				() -> providerCompiler(provider).fingerprint(sample(FingerprintWordsProvider.step())));
			delegates.clear();
			final FulltextWordsAnalysisStepFactory leaf = delegateParent(new LowercaseAnalysisStepFactory());
			delegates.add(leaf);
			delegates.add(leaf);
			assertDoesNotThrow(() -> providerCompiler(provider).fingerprint(sample(FingerprintWordsProvider.step())));
		}

		/** Discovery refuses broad and composite descriptors before a definition can compile or be hashed. */
		@Test
		@DisplayName("should reject index-side descriptors without lossless encodings at discovery")
		void shouldRejectUnsupportedDescriptorTypes() {
			for (final Class<? extends Serializable> type : List.of(
				Serializable.class, BigDecimalNumberRange.class, BigDecimalNumberRange[].class, Expression.class,
				Expression[].class, DateTimeRange.class, LongNumberRange.class, IntegerNumberRange.class,
				ShortNumberRange.class, ByteNumberRange.class, Predecessor.class, ReferencedEntityPredecessor.class,
				Serializable[].class, String[][].class, float[].class, double[].class, int.class
			)) {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> providerCompiler(typedProvider(type)));
				assertTrue(error.getMessage().contains("test-fingerprint-words"));
				assertTrue(error.getMessage().contains("first"));
				assertTrue(error.getMessage().contains(type.getTypeName()));
			}
		}

		/** Even an unchecked broad Object descriptor cannot grant arbitrary index-side values. */
		@Test
		@SuppressWarnings({"unchecked", "rawtypes"})
		@DisplayName("should reject a broad Object descriptor at discovery")
		void shouldRejectObjectDescriptor() {
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> providerCompiler(typedProvider((Class) Object.class)));
			assertTrue(error.getMessage().contains("test-fingerprint-words"));
			assertTrue(error.getMessage().contains("first"));
			assertTrue(error.getMessage().contains(Object.class.getTypeName()));
		}

		/** An index parameter must have its own exact descriptor, even when omitted in a definition. */
		@Test
		@DisplayName("should reject an index-side parameter without a descriptor at discovery")
		void shouldRejectMissingIndexDescriptor() {
			final FingerprintWordsProvider provider = new FingerprintWordsProvider(1, List.of(), null) {
				@Nonnull
				@Override
				public Set<String> getIndexSideParameters() { return Set.of("missing"); }
			};
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> providerCompiler(provider));
			assertTrue(error.getMessage().contains("test-fingerprint-words"));
			assertTrue(error.getMessage().contains("missing"));
		}

		/** A step without a query side of its own has no parameter that could leave the index terms unchanged. */
		@Test
		@DisplayName("should reject a parameter missing from the index side of a step without a query side")
		void shouldRejectUndeclaredParameterOfStepWithoutQuerySide() {
			final FingerprintWordsProvider indexOnly = new FingerprintWordsProvider(1, List.of(), null) {
				@Nonnull
				@Override
				public Set<FulltextAnalysisSide> getSides() { return EnumSet.of(FulltextAnalysisSide.INDEX); }
			};

			final EvitaInvalidUsageException tokenizerError = assertThrows(EvitaInvalidUsageException.class,
				() -> new AnalysisStepRegistry(List.of(new UndeclaredLimitTokenizer())));
			final EvitaInvalidUsageException wordsError = assertThrows(EvitaInvalidUsageException.class,
				() -> new AnalysisStepRegistry(List.of(indexOnly)));

			final String tokenizerMessage = tokenizerError.getMessage();
			final String wordsMessage = wordsError.getMessage();
			assertTrue(tokenizerMessage.contains(UndeclaredLimitTokenizer.NAME), tokenizerMessage);
			assertTrue(tokenizerMessage.contains("`limit`"), tokenizerMessage);
			assertTrue(wordsMessage.contains("test-fingerprint-words"), wordsMessage);
			assertTrue(wordsMessage.contains("`query`"), wordsMessage);
		}

		/** Query-only values may retain composite types because neither hash reads them. */
		@Test
		@DisplayName("should allow supported composite values for query-only parameters")
		void shouldAllowQueryOnlyCompositeValues() {
			final FulltextChainCompiler compiler = providerCompiler(new FingerprintWordsProvider(1, List.of(), null));
			final ResolvedFulltextAnalyzer resolved = sample(FingerprintWordsProvider.step(parameter(
				"query", BigDecimalNumberRange.between(new BigDecimal("1.20"), new BigDecimal("2.30")))));
			assertEquals(compiler.fingerprint(sample(FingerprintWordsProvider.step())), compiler.fingerprint(resolved));
			try (final org.apache.lucene.analysis.Analyzer ignored =
				compiler.compile(resolved, FulltextAnalysisSide.QUERY)) {
				assertNotNull(ignored);
			}
		}

		/** Shared registry validation rejects null elements consistently before compilation or fingerprinting. */
		@Test
		@DisplayName("should reject null array elements through the registry for compile and fingerprint")
		void shouldRejectNullArrayElements() {
			final FulltextChainCompiler compiler = providerCompiler(typedProvider(String[].class));
			for (final String parameter : List.of("first", "query")) {
				final ResolvedFulltextAnalyzer resolved = sample(
					FingerprintWordsProvider.step(parameter(parameter, new String[]{"valid", null})));
				final EvitaInvalidUsageException compileError = assertThrows(EvitaInvalidUsageException.class,
					() -> compiler.compile(resolved, FulltextAnalysisSide.INDEX));
				final EvitaInvalidUsageException fingerprintError = assertThrows(EvitaInvalidUsageException.class,
					() -> compiler.fingerprint(resolved));
				assertEquals(compileError.getMessage(), fingerprintError.getMessage());
				assertTrue(compileError.getMessage().contains("test-fingerprint-words"));
				assertTrue(compileError.getMessage().contains(parameter));
				assertTrue(compileError.getMessage().contains("null array elements"));
			}
		}

		/** Neither a plain string parameter nor an omitted resource parameter grants resource access. */
		@Test
		@DisplayName("should refuse undeclared resource parameters and implicit default resources")
		void shouldRefuseImplicitResourceAccess() {
			for (final boolean declared : List.of(false, true)) {
				final FingerprintWordsProvider provider = new FingerprintWordsProvider(
					1, List.of(), declared ? FulltextResourceKind.WORD_LIST : null
				) {
					@Nonnull
					@Override
					public FulltextWordsAnalysisOperation create(
						@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
					) {
						context.getResource((String) parameters.getOrDefault("first", "list"));
						return (words, side) -> words;
					}
				};
				final WordsAnalysisStep step = declared ? FingerprintWordsProvider.step() :
					FingerprintWordsProvider.step(parameter("first", "list"));
				final FulltextChainCompiler compiler = providerCompiler(provider);
				for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
					final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
						() -> compiler.compile(resourceSample(step, "list", wordList("one")), side));
					assertTrue(error.getMessage().contains("Fulltext resource `list` does not exist."));
					assertTrue(error.getMessage().contains("only the resources its resource parameters name"));
				}
			}
		}

		/** The text, tokenizer and further splitting steps get a context as narrow as a words step gets. */
		@Test
		@DisplayName("should refuse implicit resource access before the words phase")
		void shouldRefuseImplicitResourceAccessBeforeWordsPhase() {
			final FulltextChainCompiler compiler =
				providerCompiler(new ListReadingText(), new ListReadingTokenizer(), new ListReadingSplitting());
			final ResolvedFulltextAnalyzer base = ResolvedFulltextAnalyzer.resolve(catalog(b -> b
				.withFulltextResource("list", wordList("one"))
				.withFulltextAnalyzer("sample", a -> a.words(lowercase()))), "sample", Locale.ROOT);
			final List<ResolvedFulltextAnalyzer> analyzers = List.of(
				new ResolvedFulltextAnalyzer(base.analyzer(), List.of(AnalysisStep.of(ListReadingText.NAME, Map.of())),
					base.splitting(), base.words(), base.context()),
				new ResolvedFulltextAnalyzer(base.analyzer(), base.text(),
					List.of(AnalysisStep.of(ListReadingTokenizer.NAME, Map.of())), base.words(), base.context()),
				new ResolvedFulltextAnalyzer(base.analyzer(), base.text(),
					List.of(standardTokenizer(), AnalysisStep.of(ListReadingSplitting.NAME, Map.of())), base.words(),
					base.context())
			);

			assertEquals(wordList("one"), base.context().getResource("list"));
			for (final ResolvedFulltextAnalyzer resolved : analyzers) {
				for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
					final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
						() -> compiler.compile(resolved, side));
					assertTrue(error.getMessage().contains("Fulltext resource `list` does not exist."));
					assertTrue(error.getMessage().contains("only the resources its resource parameters name"));
				}
			}
		}

		/** Every name an array resource parameter holds is readable, and a resource no such parameter names is not. */
		@Test
		@DisplayName("should grant every resource an array resource parameter names and no other")
		void shouldGrantResourcesNamedByArrayParameter() {
			final FingerprintWordsProvider provider = new FingerprintWordsProvider(
				1, List.of(), FulltextResourceKind.WORD_LIST
			) {
				@Nonnull
				@Override
				public FulltextWordsAnalysisOperation create(
					@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
				) {
					for (final String name : (String[]) parameters.get("second")) {
						context.getResource(name);
					}
					if (parameters.get("query") instanceof String unnamed) {
						context.getResource(unnamed);
					}
					return (words, side) -> words;
				}
			};
			final FulltextChainCompiler compiler = providerCompiler(provider);
			final CatalogSchemaContract schema = catalog(b -> b
				.withFulltextResource("a", wordList("one"))
				.withFulltextResource("b", wordList("two"))
				.withFulltextResource("c", wordList("three"))
				.withFulltextAnalyzer("granted", a -> a.words(
					FingerprintWordsProvider.step(parameter("second", new String[]{"a", "b"}))))
				.withFulltextAnalyzer("ungranted", a -> a.words(FingerprintWordsProvider.step(
					parameter("second", new String[]{"a", "b"}), parameter("query", "c")))));
			final ResolvedFulltextAnalyzer granted = ResolvedFulltextAnalyzer.resolve(schema, "granted", Locale.ROOT);
			final ResolvedFulltextAnalyzer ungranted =
				ResolvedFulltextAnalyzer.resolve(schema, "ungranted", Locale.ROOT);

			for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
				try (final org.apache.lucene.analysis.Analyzer analyzer = compiler.compile(granted, side)) {
					assertNotNull(analyzer);
				}
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> compiler.compile(ungranted, side));
				assertTrue(error.getMessage().contains("Fulltext resource `c` does not exist."), error.getMessage());
			}
		}

		/** The server descriptors agree with the API's built-in resource vocabulary. */
		@Test
		@DisplayName("should declare the same built-in resource kinds as the API")
		void shouldMatchBuiltInResourceMetadata() {
			for (final AnalysisStepFactory factory : ServiceLoader.load(AnalysisStepFactory.class)) {
				if (factory instanceof FulltextAnalysisStepFactory) {
					final Map<String, FulltextResourceKind> declared = CollectionUtils.createHashMap(2);
					for (final AnalysisStepParameterDescriptor descriptor : factory.getParameters()) {
						if (descriptor.resourceKind() != null) {
							declared.put(descriptor.name(), descriptor.resourceKind());
						}
					}
					assertEquals(RESOURCE_PARAMETERS.getOrDefault(factory.getName(), Map.of()), declared,
						factory.getName());
				}
			}
		}

		/** Resource metadata can only describe a string name or an array of names. */
		@Test
		@DisplayName("should reject resource kinds on non-string parameter types")
		void shouldRejectResourceKindOnNonString() {
			assertThrows(EvitaInvalidUsageException.class, () -> new AnalysisStepParameterDescriptor(
				"list", Integer.class, false, "Resource names", FulltextResourceKind.WORD_LIST));
			assertThrows(EvitaInvalidUsageException.class, () -> new AnalysisStepParameterDescriptor(
				"list", Integer[].class, false, "Resource names", FulltextResourceKind.WORD_LIST));
			assertNull(new AnalysisStepParameterDescriptor("plain", Integer.class, false, "Value").resourceKind());
		}
	}

	/** Resolves and hashes an analyzer from its real schema. */
	@Nonnull
	private static FulltextAnalyzerFingerprint fingerprint(
		@Nonnull CatalogSchemaContract schema, @Nonnull String name, @Nonnull Locale locale
	) {
		return COMPILER.fingerprint(ResolvedFulltextAnalyzer.resolve(schema, name, locale));
	}

	/** Builds a real catalog schema with the given changes. */
	@Nonnull
	private static CatalogSchemaContract catalog(@Nonnull Consumer<InternalCatalogSchemaBuilder> changes) {
		final CatalogSchema empty = CatalogSchema._internalBuild(TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG), null, EnumSet.allOf(CatalogEvolutionMode.class),
			EmptyEntitySchemaAccessor.INSTANCE);
		final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(empty);
		changes.accept(builder);
		return builder.toInstance();
	}

	/** Resolves a schema declaring exactly these words steps. */
	@Nonnull
	private static ResolvedFulltextAnalyzer sample(@Nonnull WordsAnalysisStep... steps) {
		return ResolvedFulltextAnalyzer.resolve(catalog(b -> b.withFulltextAnalyzer("sample", a -> a.words(steps))),
			"sample", Locale.ROOT);
	}

	/** Hashes an analyzer consisting of the given words steps. */
	@Nonnull
	private static FulltextAnalyzerFingerprint words(@Nonnull WordsAnalysisStep... steps) {
		return COMPILER.fingerprint(sample(steps));
	}

	/** Resolves a real schema with lowercase followed by a resource-reading step. */
	@Nonnull
	private static ResolvedFulltextAnalyzer resourceSample(
		@Nonnull WordsAnalysisStep step, @Nonnull String name, @Nonnull FulltextResourceContent content
	) {
		return ResolvedFulltextAnalyzer.resolve(catalog(b -> b.withFulltextResource(name, content)
			.withFulltextAnalyzer("sample", a -> a.words(lowercase(), step))), "sample", Locale.ROOT);
	}

	/** Hashes the resource-bearing sample with built-in providers. */
	@Nonnull
	private static FulltextAnalyzerFingerprint withWordsAndResource(
		@Nonnull WordsAnalysisStep step, @Nonnull String name, @Nonnull FulltextResourceContent content
	) {
		return COMPILER.fingerprint(resourceSample(step, name, content));
	}

	/**
	 * Asserts that an index-side words change reaches both identities.
	 */
	private static void assertBothDiffer(
		@Nonnull FulltextAnalyzerFingerprint first, @Nonnull FulltextAnalyzerFingerprint second
	) {
		assertNotEquals(first.index(), second.index());
		assertNotEquals(first.wordPhase(), second.wordPhase());
	}

	/** Creates a compiler over installed providers and the test providers. */
	@Nonnull
	private static FulltextChainCompiler providerCompiler(@Nonnull AnalysisStepFactory... extras) {
		final List<AnalysisStepFactory> factories = new ArrayList<>();
		for (final AnalysisStepFactory factory : ServiceLoader.load(AnalysisStepFactory.class)) {
			factories.add(factory);
		}
		factories.addAll(List.of(extras));
		return new FulltextChainCompiler(new AnalysisStepRegistry(factories));
	}

	/** Creates an intermediate dispatch to exercise recursive delegate versions. */
	@Nonnull
	private static FulltextWordsAnalysisStepFactory delegateParent(@Nonnull FulltextAnalysisStepFactory leaf) {
		return new FingerprintWordsProvider(1, List.of(leaf), null) {
			@Nonnull
			@Override
			public String getName() { return "test-fingerprint-middle"; }
		};
	}

	/** Creates independent scalar objects, every object array, and every accepted primitive array. */
	@Nonnull
	private static List<Serializable> scalarValues() {
		final List<Serializable> scalars = List.of(
			new String("value'\ud800"), Byte.valueOf("-128"), Short.valueOf("-12345"), Integer.valueOf("1234567"),
			Long.valueOf("9876543210"), Boolean.valueOf("true"), Character.valueOf('\ud801'), new BigDecimal("1.20"),
			OffsetDateTime.parse("2026-01-02T03:04:05.123456789+01:30"),
			LocalDateTime.parse("2026-01-02T03:04:05.123456789"), LocalDate.parse("2026-01-02"),
			LocalTime.parse("03:04:05.123456789"), Locale.forLanguageTag("cs-CZ-u-nu-latn"),
			Currency.getInstance("EUR"), UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
		);
		final List<Serializable> values = new ArrayList<>(scalars);
		for (final Serializable value : scalars) {
			final Serializable array = (Serializable) Array.newInstance(value.getClass(), 2);
			Array.set(array, 0, value);
			Array.set(array, 1, value);
			values.add(array);
		}
		values.addAll(List.of(new byte[]{-128, 127}, new short[]{-12345, 12345}, new int[]{1, -2},
			new long[]{9876543210L, -1L}, new boolean[]{true, false}, new char[]{'x', '\ud800'}));
		return values;
	}

	/** Builds a fresh definition containing all lossless scalar and array types. */
	@Nonnull
	private static WordsAnalysisStep scalarStep() {
		final List<Serializable> values = scalarValues();
		final AnalysisStepParameter[] parameters = new AnalysisStepParameter[values.size()];
		for (int i = 0; i < values.size(); i++) {
			parameters[i] = parameter("value" + i, values.get(i));
		}
		return FingerprintWordsProvider.step(parameters);
	}

	/** Creates a provider whose first index parameter has the exact descriptor type under test. */
	@Nonnull
	private static FingerprintWordsProvider typedProvider(@Nonnull Class<? extends Serializable> type) {
		return new FingerprintWordsProvider(1, List.of(), null) {
			@Nonnull
			@Override
			public List<AnalysisStepParameterDescriptor> getParameters() {
				return List.of(
					new AnalysisStepParameterDescriptor("first", type, false, "Typed index value"),
					new AnalysisStepParameterDescriptor("second", String[].class, false, "Other index value"),
					new AnalysisStepParameterDescriptor("query", Serializable.class, false, "Query-only value")
				);
			}
		};
	}

	/**
	 * Fixed step name `test-fingerprint-words` and version 1 pin every accepted scalar and array branch.
	 */
	private static final class ScalarFingerprintProvider extends FingerprintWordsProvider {
		/** Creates the fixed golden provider without delegates or resources. */
		private ScalarFingerprintProvider() { super(1, List.of(), null); }

		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() {
			final List<Serializable> values = scalarValues();
			final List<AnalysisStepParameterDescriptor> descriptors = new ArrayList<>(values.size());
			for (int i = 0; i < values.size(); i++) {
				descriptors.add(new AnalysisStepParameterDescriptor("value" + i,
					values.get(i).getClass().asSubclass(Serializable.class), true, "Lossless value"));
			}
			return descriptors;
		}

		@Nonnull
		@Override
		public Set<String> getIndexSideParameters() {
			final Set<String> names = CollectionUtils.createHashSet(scalarValues().size());
			for (final AnalysisStepParameterDescriptor descriptor : getParameters()) {
				names.add(descriptor.name());
			}
			return names;
		}
	}

	/**
	 * Configurable provider versions, delegates and resource declarations, exercised through a real registry.
	 */
	private static class FingerprintWordsProvider implements FulltextWordsAnalysisStepFactory {
		/**
		 * The behaviour version under test.
		 */
		private final int version;
		/**
		 * The delegated behaviour, mutable only in the cycle test.
		 */
		@Nonnull private final List<FulltextAnalysisStepFactory> delegates;
		/**
		 * The resource metadata for test parameters, absent for scalar tests.
		 */
		@Nullable private final FulltextResourceKind resourceKind;

		/** Creates a provider with the requested behaviour and parameter semantics. */
		private FingerprintWordsProvider(
			int version,
			@Nonnull List<FulltextAnalysisStepFactory> delegates,
			@Nullable FulltextResourceKind resourceKind
		) {
			this.version = version;
			this.delegates = delegates;
			this.resourceKind = resourceKind;
		}

		/** Describes a test-provider words step. */
		@Nonnull
		static WordsAnalysisStep step(@Nonnull AnalysisStepParameter... parameters) {
			return wordsStep("test-fingerprint-words", parameters);
		}

		@Nonnull
		@Override
		public String getName() { return "test-fingerprint-words"; }

		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() {
			return List.of(
				new AnalysisStepParameterDescriptor(
					"first", String.class,
					false, "First value or resource name", this.resourceKind),
				new AnalysisStepParameterDescriptor(
					"second", this.resourceKind == null ? int[].class : String[].class,
					false, "Second value or resource names", this.resourceKind),
				new AnalysisStepParameterDescriptor("query", Serializable.class, false, "Query-only value")
			);
		}

		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) {
			// The descriptors specify all test-provider restrictions.
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() { return EnumSet.allOf(FulltextAnalysisSide.class); }

		@Nonnull
		@Override
		public Set<String> getIndexSideParameters() { return Set.of("first", "second"); }

		@Override
		public int getBehaviourVersion() { return this.version; }

		@Nonnull
		@Override
		public List<FulltextAnalysisStepFactory> getDelegates(@Nonnull Locale locale) { return this.delegates; }

		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			return (words, side) -> words;
		}
	}

	/**
	 * Base of the third-party steps of the text, tokenizer and further splitting phases: parameterless, version 1.
	 */
	private abstract static class ThirdPartyStep implements AnalysisStepFactory {
		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }

		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) {
			// The descriptors specify all test-provider restrictions.
		}

		@Nonnull
		public Set<String> getIndexSideParameters() { return Set.of(); }

		public int getBehaviourVersion() { return 1; }
	}

	/**
	 * A tokenizer leaving its `limit` parameter out of its index-side parameters, although a tokenizer acts on both
	 * sides alike.
	 */
	private static final class UndeclaredLimitTokenizer extends ThirdPartyStep
		implements FulltextTokenizerAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-undeclared-limit-tokenizer";

		@Nonnull
		@Override
		public String getName() { return NAME; }

		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() {
			return List.of(new AnalysisStepParameterDescriptor("limit", Integer.class, false, "Longest word kept"));
		}

		@Nonnull
		@Override
		public FulltextTokenizerAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			return StandardTokenizer::new;
		}
	}

	/**
	 * A text step on both sides reading the resource `list`, which none of its parameters names.
	 */
	private static final class ListReadingText extends ThirdPartyStep implements FulltextTextAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-list-reading-text";

		@Nonnull
		@Override
		public String getName() { return NAME; }

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() { return EnumSet.allOf(FulltextAnalysisSide.class); }

		@Nonnull
		@Override
		public FulltextTextAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			context.getResource("list");
			return (text, side) -> text;
		}
	}

	/**
	 * A tokenizer reading the resource `list`, which none of its parameters names.
	 */
	private static final class ListReadingTokenizer extends ThirdPartyStep
		implements FulltextTokenizerAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-list-reading-tokenizer";

		@Nonnull
		@Override
		public String getName() { return NAME; }

		@Nonnull
		@Override
		public FulltextTokenizerAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			context.getResource("list");
			return StandardTokenizer::new;
		}
	}

	/**
	 * A further splitting step reading the resource `list`, which none of its parameters names.
	 */
	private static final class ListReadingSplitting extends ThirdPartyStep
		implements FulltextSplittingAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-list-reading-splitting";

		@Nonnull
		@Override
		public String getName() { return NAME; }

		@Nonnull
		@Override
		public FulltextSplittingAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			context.getResource("list");
			return words -> words;
		}
	}

	/**
	 * A text operation whose behaviour applies only to queries.
	 */
	private static final class FingerprintTextProvider implements FulltextTextAnalysisStepFactory {
		@Nonnull
		@Override
		public String getName() { return "test-fingerprint-text"; }

		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() { return List.of(); }

		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) {
			// There are no parameters to constrain beyond the descriptors.
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() { return Set.of(FulltextAnalysisSide.QUERY); }

		@Nonnull
		@Override
		public Set<String> getIndexSideParameters() { return Set.of(); }

		@Override
		public int getBehaviourVersion() { return 1; }

		@Nonnull
		@Override
		public FulltextTextAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			return (text, side) -> text;
		}
	}
}
