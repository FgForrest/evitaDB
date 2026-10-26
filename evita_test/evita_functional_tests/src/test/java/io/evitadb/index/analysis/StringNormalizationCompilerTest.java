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


package io.evitadb.index.analysis;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Pins single-pass ordering, legacy compatibility, unchanged instances and immutable cache identity. */
@DisplayName("String normalization compiler")
@Tag(ENGINE)
@Tag(SCHEMA)
class StringNormalizationCompilerTest {
	/** Empty descriptions must exactly match the pre-P3 NFD function, including null. */
	@Test
	@DisplayName("should preserve the legacy NFD-only key function")
	void shouldPreserveLegacyFunction() {
		final UnaryOperator<String> function = new AnalysisStepRegistry().compile(List.of(), null);
		assertNull(function.apply(null));
		for (final String text : corpus()) {
			assertEquals(Normalizer.normalize(text, Normalizer.Form.NFD), function.apply(text));
		}
	}

	/** Each ordering runs once, with NFC only at entry and NFD only at exit. */
	@ParameterizedTest(name = "{0} / {1}")
	@MethodSource("orderings")
	@DisplayName("should apply each step once in declared order for every locale")
	void shouldApplySinglePass(@Nonnull List<AnalysisStep> steps, @Nonnull Locale locale) {
		final AnalysisStepRegistry registry = new AnalysisStepRegistry();
		final UnaryOperator<String> function = registry.compile(steps, locale);
		assertNull(function.apply(null));
		for (final String text : corpus()) {
			String expected = Normalizer.normalize(text, Normalizer.Form.NFC);
			for (final AnalysisStep step : steps) {
				expected = registry.resolve(step).create(step.parameters(), locale).apply(expected);
			}
			assertEquals(Normalizer.normalize(expected, Normalizer.Form.NFD), function.apply(text));
		}
	}

	/** Literal expectations, independent of the step implementations the single-pass check replays. */
	@ParameterizedTest(name = "{0} / {1} / \"{2}\"")
	@MethodSource("literalChains")
	@DisplayName("should produce the literal key of representative chains")
	void shouldProduceLiteralOutputForRepresentativeChains(
		@Nonnull List<AnalysisStep> steps, @Nonnull Locale locale, @Nullable String input, @Nullable String expected
	) {
		assertEquals(expected, new AnalysisStepRegistry().compile(steps, locale).apply(input));
	}

	/** Cache keys copy caller-owned lists, compare descriptions by value and separate fold locales and step order. */
	@Test
	@DisplayName("should cache immutable functions by descriptions and locale")
	void shouldCacheFunctions() {
		final AnalysisStepRegistry registry = new AnalysisStepRegistry();
		final List<AnalysisStep> mutable = new ArrayList<>(List.of(lowercase()));
		final UnaryOperator<String> root = registry.compile(mutable, null);
		mutable.add(trim());
		assertSame(root, registry.compile(List.of(lowercase()), Locale.ROOT));
		assertNotSame(root, registry.compile(mutable, null));
		assertNotSame(root, registry.compile(List.of(lowercase()), Locale.forLanguageTag("tr")));
		assertEquals("i", root.apply("I"));
		assertEquals("ı", registry.compile(List.of(lowercase()), Locale.forLanguageTag("tr")).apply("I"));
		assertNotSame(registry.compile(List.of(trim(), lowercase()), null), registry.compile(mutable, null));
		assertSame(registry.compile(List.of(removeCharacters("-")), null),
			registry.compile(List.of(step("remove-characters", parameter("characters", "-"))), null));
		IntStream.range(0, 100).parallel().forEach(index ->
			assertSame(root, registry.compile(List.of(lowercase()), null)));
	}

	/** Identity is retained when the whole function returns the input contents, including an NFD input. */
	@Test
	@DisplayName("should return the original instance when the resulting key is unchanged")
	void shouldPreserveInstance() {
		final UnaryOperator<String> function = new AnalysisStepRegistry().compile(List.of(trim()), null);
		for (final String text : List.of(new String("abc"), new String("a\u0301"), new String(""))) {
			assertSame(text, function.apply(text));
		}
	}

	/**
	 * Removal before trimming consumes the exposed whitespace and the reverse order keeps it; folding before
	 * lowercasing exposes the Turkish dotless `ı`; Lucene's folding table maps the small `ǧ` to a capital `G`.
	 *
	 * @return arguments of one case: step chain, fold locale, input text and the expected normalized text (`null` for
	 *     `null`)
	 */
	@Nonnull
	static Stream<Arguments> literalChains() {
		final Locale turkish = Locale.forLanguageTag("tr");
		return Stream.of(
			Arguments.of(List.of(removeCharacters("-"), trim()), Locale.ROOT, " - abc", "abc"),
			Arguments.of(List.of(trim(), removeCharacters("-")), Locale.ROOT, " - abc", " abc"),
			Arguments.of(List.of(lowercase(), asciiFolding()), Locale.ROOT, "ǧ", "G"),
			Arguments.of(List.of(asciiFolding(), lowercase()), turkish, "Í", "ı"),
			Arguments.of(List.of(), Locale.ROOT, "é", "é"),
			Arguments.of(List.of(lowercase(), trim()), turkish, null, null)
		);
	}

	/**
	 * Enumerates all 4! declared step orderings with root, Turkish, Azeri and Lithuanian locales.
	 *
	 * @return arguments of one case: a permutation of the four steps and a fold locale
	 */
	@Nonnull
	static Stream<Arguments> orderings() {
		final List<Arguments> result = new ArrayList<>();
		final List<AnalysisStep> steps = List.of(lowercase(), asciiFolding(), trim(), removeCharacters("-😀a"));
		for (int first = 0; first < 4; first++) {
			for (int second = 0; second < 4; second++) {
				for (int third = 0; third < 4; third++) {
					for (int fourth = 0; fourth < 4; fourth++) {
						if (first != second && first != third && first != fourth && second != third &&
							second != fourth && third != fourth) {
							for (final String language : List.of("", "tr", "az", "lt")) {
								result.add(Arguments.of(List.of(
									steps.get(first), steps.get(second), steps.get(third), steps.get(fourth)
								), Locale.forLanguageTag(language)));
							}
						}
					}
				}
			}
		}
		return result.stream();
	}

	/**
	 * Reuses all golden inputs and adds reproducible random code-point mixtures, including combining marks.
	 *
	 * @return golden inputs followed by 200 random code-point mixtures, identical on every run
	 */
	@Nonnull
	private static List<String> corpus() {
		final List<String> result = new ArrayList<>(AnalysisStepGoldenOutputTest.samples()
			.map(AnalysisStepGoldenOutputTest.GoldenSample::input).toList());
		final int[] alphabet = "A-IİıiJĮ ÁČĽŁÓŽΣςßẞÆŒﬃ℡😀😁𐐀𐐨\u0301\u0307\u0323\u00a0\u2007\u202f".codePoints().toArray();
		final Random random = new Random(545L);
		for (int index = 0; index < 200; index++) {
			final StringBuilder text = new StringBuilder(40);
			final int length = random.nextInt(20);
			for (int position = 0; position < length; position++) {
				text.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
			}
			result.add(text.toString());
		}
		return result;
	}
}
