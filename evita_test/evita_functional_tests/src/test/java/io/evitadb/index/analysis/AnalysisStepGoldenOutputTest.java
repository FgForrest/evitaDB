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

import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.spi.analysis.StringNormalizationStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Pins built-in output against committed, independently specified Unicode samples. */
@DisplayName("Analysis step golden output")
@Tag(ENGINE)
@Tag(SCHEMA)
class AnalysisStepGoldenOutputTest {
	/** Verifies every recorded output, including unchanged-instance guarantees. */
	@DisplayName("should preserve recorded step output")
	@ParameterizedTest(name = "{0}")
	@MethodSource("samples")
	void shouldPreserveRecordedOutput(@Nonnull GoldenSample sample) {
		final NormalizingAnalysisStepFactory factory = sample.factory();
		final StringNormalizationStep operation = factory.create(
			sample.characters().isEmpty() ? Map.of() : Map.of("characters", sample.characters()), sample.locale()
		);
		final String output = operation.apply(sample.input());
		assertEquals(sample.expected(), output, "Output changed: update the golden corpus deliberately or revert.");
		if (sample.expected().equals(sample.input())) {
			assertSame(sample.input(), output, "An unchanged step must return the same String instance.");
		}
	}

	/**
	 * Reads all four committed sample files; shared with the compiler corpus test.
	 *
	 * @return recorded samples of every built-in step provider
	 */
	@Nonnull
	static Stream<GoldenSample> samples() {
		return Stream.of(
			new LowercaseAnalysisStepFactory(), new AsciiFoldingAnalysisStepFactory(),
			new TrimAnalysisStepFactory(), new RemoveCharactersAnalysisStepFactory()
		).flatMap(AnalysisStepGoldenOutputTest::readSamples);
	}

	/**
	 * Decodes versioned TSV fields represented as Unicode scalar values, preserving empty strings and controls.
	 *
	 * @param factory provider whose `<name>.tsv` sample file is read
	 * @return recorded samples of the provider, in file order
	 */
	@Nonnull
	private static Stream<GoldenSample> readSamples(@Nonnull NormalizingAnalysisStepFactory factory) {
		final List<GoldenSample> samples = new ArrayList<>();
		try (final BufferedReader reader = new BufferedReader(new InputStreamReader(
			Objects.requireNonNull(AnalysisStepGoldenOutputTest.class.getResourceAsStream(
				"/analysis/" + factory.getName() + ".tsv"
			)), StandardCharsets.UTF_8
		))) {
			final int version = Integer.parseInt(reader.readLine().substring("# version=".length()));
			for (final String line : reader.lines().toList()) {
				if (!line.startsWith("#")) {
					final String[] fields = line.split("\t", -1);
					samples.add(new GoldenSample(
						factory, version, "root".equals(fields[0]) ? Locale.ROOT : Locale.forLanguageTag(fields[0]),
						decode(fields[1]), decode(fields[2]), decode(fields[3])
					));
				}
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return samples.stream();
	}

	/**
	 * Converts hexadecimal code points to a String; a single hyphen encodes the empty String.
	 *
	 * @param encoded space-separated hexadecimal code points, or `-` for the empty String
	 * @return the decoded text
	 */
	@Nonnull
	private static String decode(@Nonnull String encoded) {
		final StringBuilder result = new StringBuilder(encoded.length());
		if (!"-".equals(encoded)) {
			for (final String codePoint : encoded.split(" ")) {
				result.appendCodePoint(Integer.parseInt(codePoint, 16));
			}
		}
		return result.toString();
	}

	/**
	 * One fixed input/output pair with its recorded provider version, parameters and fold locale.
	 *
	 * @param factory provider that creates the step under test
	 * @param version provider version the sample was recorded with
	 * @param locale fold locale the step is created for
	 * @param characters value of the `characters` parameter; empty when the step takes none
	 * @param input text passed to the step
	 * @param expected output recorded for the input
	 */
	record GoldenSample(
		@Nonnull NormalizingAnalysisStepFactory factory, int version, @Nonnull Locale locale,
		@Nonnull String characters, @Nonnull String input, @Nonnull String expected
	) {
		/** Renders a compact parameterized-test identity. */
		@Nonnull
		@Override
		public String toString() {
			return this.factory.getName() + " / " + this.locale + " / " + this.input;
		}
	}
}
