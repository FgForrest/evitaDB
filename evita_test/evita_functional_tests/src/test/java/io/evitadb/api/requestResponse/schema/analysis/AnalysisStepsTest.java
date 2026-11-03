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

package io.evitadb.api.requestResponse.schema.analysis;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the immutable analysis vocabulary without executing any normalization. */
@DisplayName("Analysis step descriptions")
@Tag(CONTRACT)
@Tag(SCHEMA)
class AnalysisStepsTest {

	/** Built-in factories and generic notation describe identical steps. */
	@Test
	@DisplayName("Factories use the public step and parameter names")
	void shouldDescribeBuiltInStepsThroughGenericNotation() {
		assertEquals(normalizingStep(LOWERCASE), lowercase());
		assertEquals(wordsStep(ASCII_FOLDING), asciiFolding());
		assertEquals(normalizingStep(TRIM), trim());
		assertEquals(textStep(REMOVE_CHARACTERS, parameter(CHARACTERS, "- ")), removeCharacters("- "));
		assertEquals(tokenizerStep(STANDARD_TOKENIZER), standardTokenizer());
		assertEquals("lowercase", lowercase().toString());
		assertEquals("remove-characters(characters='- ')", removeCharacters("- ").toString());
		assertEquals(
			removeCharacters("- ").hashCode(),
			normalizingStep(REMOVE_CHARACTERS, parameter(CHARACTERS, "- ")).hashCode()
		);
	}

	/** A step read back from storage or an external API carries no place, yet equals the one its factory made. */
	@Test
	@DisplayName("Untyped descriptions equal typed ones of the same name and parameters")
	void shouldCompareDescriptionsWhateverTheirType() {
		final AnalysisStep untyped = AnalysisStep.of(LOWERCASE, Map.of());
		assertEquals(lowercase(), untyped);
		assertEquals(untyped, lowercase());
		assertEquals(lowercase().hashCode(), untyped.hashCode());
		assertFalse(untyped instanceof NormalizingAnalysisStep);
		assertEquals(
			removeCharacters("-"), AnalysisStep.of(REMOVE_CHARACTERS, Map.of(CHARACTERS, "-"))
		);
		assertNotEquals(removeCharacters("-"), AnalysisStep.of(REMOVE_CHARACTERS, Map.of(CHARACTERS, "/")));
	}

	/** Each factory states every place its step may take, and no other. */
	@Test
	@DisplayName("Factories return the types of the places their steps serve")
	void shouldTypeBuiltInStepsByTheirPlaces() {
		assertInstanceOf(NormalizingWordsAnalysisStep.class, lowercase());
		assertInstanceOf(NormalizingWordsAnalysisStep.class, asciiFolding());
		assertInstanceOf(NormalizingTextAnalysisStep.class, removeCharacters("-"));
		assertInstanceOf(NormalizingAnalysisStep.class, trim());
		assertInstanceOf(TextAnalysisStep.class, htmlStrip());
		assertInstanceOf(TokenizerAnalysisStep.class, standardTokenizer());
		assertInstanceOf(SplittingAnalysisStep.class, wordNumberSplit());
		assertInstanceOf(WordsAnalysisStep.class, stopWords(czechStopWords()));
		assertInstanceOf(WordsAnalysisStep.class, czechStemming());
		assertFalse((AnalysisStep) trim() instanceof WordsAnalysisStep);
		assertFalse((AnalysisStep) htmlStrip() instanceof NormalizingAnalysisStep);
		assertFalse((AnalysisStep) stopWords(czechStopWords()) instanceof NormalizingAnalysisStep);
		assertFalse((AnalysisStep) wordNumberSplit() instanceof TokenizerAnalysisStep);
	}

	/** A generic factory grants its step its own place and no other, so no editor of another place accepts it. */
	@Test
	@DisplayName("Generic factories return the type of their place only")
	void shouldTypeGenericStepsByTheirPlaceOnly() {
		final List<Class<? extends AnalysisStep>> places = List.of(
			TextAnalysisStep.class, TokenizerAnalysisStep.class, WordsAnalysisStep.class, NormalizingAnalysisStep.class
		);
		final List<AnalysisStep> steps = List.of(
			textStep("custom"), tokenizerStep("custom"), wordsStep("custom"), normalizingStep("custom")
		);

		for (int i = 0; i < steps.size(); i++) {
			for (int j = 0; j < places.size(); j++) {
				assertEquals(
					i == j, places.get(j).isInstance(steps.get(i)),
					steps.get(i) + " as " + places.get(j).getSimpleName()
				);
			}
		}
	}

	/** Generic steps permit names supplied by future server providers and typed parameters. */
	@Test
	@DisplayName("Generic steps preserve supported parameter values")
	@SuppressWarnings({"SimplifiableAssertion", "EqualsBetweenInconvertibleTypes"})
	void shouldDescribeCustomStepsAndTypedParameters() {
		final AnalysisStep step = wordsStep(
			"custom", parameter("limit", 2), parameter("factor", new BigDecimal("1.5"))
		);
		assertEquals(Map.of("limit", 2, "factor", new BigDecimal("1.5")), step.parameters());
		assertEquals("custom(limit=2, factor=1.5)", step.toString());
		assertNotEquals(step, lowercase());
		// deliberately not `assertNotEquals`: it calls `equals` on its first argument, and the IDE's argument-order
		// fix moves the string literal there, so `String.equals` would be tested instead of the step's (and below,
		// the parameter's)
		assertFalse(step.equals("custom"));
		assertEquals(parameter("limit", 2), parameter("limit", 2));
		assertNotEquals(parameter("limit", 2), parameter("limit", 3));
		assertNotEquals(parameter("limit", 2), parameter("other", 2));
		assertFalse(parameter("limit", 2).equals("limit"));
	}

	/** Descriptions own the map and any supported mutable array values. */
	@Test
	@DisplayName("Maps and arrays are defensively copied and exposed immutably")
	void shouldKeepDescriptionsImmutable() {
		final int[] numbers = {1, 2};
		final Map<String, Serializable> input = new LinkedHashMap<>();
		input.put("numbers", numbers);
		final AnalysisStep step = AnalysisStep.of("custom", input);
		input.clear();
		numbers[0] = 9;
		assertArrayEquals(new int[]{1, 2}, (int[]) step.parameters().get("numbers"));
		((int[]) step.parameters().get("numbers"))[0] = 9;
		assertArrayEquals(new int[]{1, 2}, (int[]) step.parameters().get("numbers"));
		assertThrows(UnsupportedOperationException.class, () -> step.parameters().put("new", "value"));
		final AnalysisStep equal = wordsStep("custom", parameter("numbers", new int[]{1, 2}));
		assertEquals(equal, step);
		assertEquals(equal.hashCode(), step.hashCode());
		assertEquals("custom(numbers=[1, 2])", step.toString());
		final AnalysisStepParameter parameter = parameter("numbers", numbers);
		numbers[0] = 7;
		((int[]) parameter.value())[0] = 8;
		assertArrayEquals(new int[]{9, 2}, (int[]) parameter.value());
		assertEquals(parameter("numbers", new int[]{9, 2}), parameter);
		assertEquals(parameter("numbers", new int[]{9, 2}).hashCode(), parameter.hashCode());
	}

	/** Invalid descriptions fail before they can enter any schema. */
	@Test
	@DisplayName("Names, values, parameter uniqueness and removal characters are validated")
	void shouldRejectInvalidDescriptions() {
		assertThrows(EvitaInvalidUsageException.class, () -> wordsStep(null));
		assertThrows(EvitaInvalidUsageException.class, () -> textStep(" "));
		assertThrows(EvitaInvalidUsageException.class, () -> AnalysisStep.of("x", null));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter(null, "x"));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter(" ", "x"));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter("x", null));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter("x", (Serializable) List.of("x")));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter("x", new Object[0]));
		assertThrows(EvitaInvalidUsageException.class, () -> normalizingStep("x", (AnalysisStepParameter[]) null));
		assertThrows(EvitaInvalidUsageException.class, () -> tokenizerStep("x", (AnalysisStepParameter) null));
		assertThrows(
			EvitaInvalidUsageException.class, () -> wordsStep("x", parameter("a", 1), parameter("a", 2))
		);
		assertThrows(EvitaInvalidUsageException.class, () -> removeCharacters(""));
		assertThrows(EvitaInvalidUsageException.class, () -> removeCharacters(null));
		assertEquals(" ", removeCharacters(" ").parameters().get(CHARACTERS));
	}
}
