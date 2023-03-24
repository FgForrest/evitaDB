/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.parser;

import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link Classifier}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ClassifierTest {

	@Test
	void shouldAcceptAndReturnedSupportedClassifierValues() {
		final Classifier value1 = new Classifier("name");
		assertEquals("name", value1.asSingleClassifier());

		final Classifier value2 = new Classifier(List.of("a", "b"));
		assertArrayEquals(new String[] { "a", "b" }, value2.asClassifierArray());

		final Classifier value3 = new Classifier(new String[] { "a", "b" });
		assertArrayEquals(new String[] { "a", "b" }, value3.asClassifierArray());
	}

	@Test
	void shouldNotCastToDifferentType() {
		assertThrows(EvitaInternalError.class, () -> new Classifier("name").asClassifierArray());
		assertThrows(EvitaInternalError.class, () -> new Classifier(List.of("name")).asSingleClassifier());
	}
}