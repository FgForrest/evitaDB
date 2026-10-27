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
		assertEquals(step(LOWERCASE), lowercase());
		assertEquals(step(ASCII_FOLDING), asciiFolding());
		assertEquals(step(TRIM), trim());
		assertEquals(step(REMOVE_CHARACTERS, parameter(CHARACTERS, "- ")), removeCharacters("- "));
		assertEquals("lowercase", lowercase().toString());
		assertEquals("remove-characters(characters='- ')", removeCharacters("- ").toString());
		assertEquals(
			removeCharacters("- ").hashCode(), step(REMOVE_CHARACTERS, parameter(CHARACTERS, "- ")).hashCode()
		);
	}

	/** Generic steps permit names supplied by future server providers and typed parameters. */
	@Test
	@DisplayName("Generic steps preserve supported parameter values")
	void shouldDescribeCustomStepsAndTypedParameters() {
		final AnalysisStep step = step("custom", parameter("limit", 2), parameter("factor", new BigDecimal("1.5")));
		assertEquals(Map.of("limit", 2, "factor", new BigDecimal("1.5")), step.parameters());
		assertEquals("custom(limit=2, factor=1.5)", step.toString());
		assertNotEquals(step, lowercase());
		assertNotEquals("custom", step);
		assertEquals(parameter("limit", 2), parameter("limit", 2));
		assertNotEquals(parameter("limit", 2), parameter("limit", 3));
		assertNotEquals(parameter("limit", 2), parameter("other", 2));
		assertNotEquals("limit", parameter("limit", 2));
	}

	/** Descriptions own the map and any supported mutable array values. */
	@Test
	@DisplayName("Maps and arrays are defensively copied and exposed immutably")
	void shouldKeepDescriptionsImmutable() {
		final int[] numbers = {1, 2};
		final Map<String, Serializable> input = new LinkedHashMap<>();
		input.put("numbers", numbers);
		final AnalysisStep step = new AnalysisStep("custom", input);
		input.clear();
		numbers[0] = 9;
		assertArrayEquals(new int[]{1, 2}, (int[]) step.parameters().get("numbers"));
		((int[]) step.parameters().get("numbers"))[0] = 9;
		assertArrayEquals(new int[]{1, 2}, (int[]) step.parameters().get("numbers"));
		assertThrows(UnsupportedOperationException.class, () -> step.parameters().put("new", "value"));
		final AnalysisStep equal = step("custom", parameter("numbers", new int[]{1, 2}));
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
		assertThrows(EvitaInvalidUsageException.class, () -> step(null));
		assertThrows(EvitaInvalidUsageException.class, () -> step(" "));
		assertThrows(EvitaInvalidUsageException.class, () -> new AnalysisStep("x", null));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter(null, "x"));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter(" ", "x"));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter("x", null));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter("x", (Serializable) List.of("x")));
		assertThrows(EvitaInvalidUsageException.class, () -> parameter("x", new Object[0]));
		assertThrows(EvitaInvalidUsageException.class, () -> step("x", (AnalysisStepParameter[]) null));
		assertThrows(EvitaInvalidUsageException.class, () -> step("x", (AnalysisStepParameter) null));
		assertThrows(EvitaInvalidUsageException.class, () -> step("x", parameter("a", 1), parameter("a", 2)));
		assertThrows(EvitaInvalidUsageException.class, () -> removeCharacters(""));
		assertThrows(EvitaInvalidUsageException.class, () -> removeCharacters(null));
		assertEquals(" ", removeCharacters(" ").parameters().get(CHARACTERS));
	}
}
