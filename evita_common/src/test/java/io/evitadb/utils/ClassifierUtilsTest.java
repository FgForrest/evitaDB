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

package io.evitadb.utils;

import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.InvalidClassifierFormatException;
import org.junit.jupiter.api.Test;

import static io.evitadb.utils.ClassifierUtils.validateClassifierFormat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ClassifierUtils}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ClassifierUtilsTest {

	@Test
	void shouldRefuseNationalCharacters() {
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, "Žlutý_kůňPěl-ódy"));
	}

	@Test
	void shouldHaveValidClassifierFormat() {
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "foobar"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "fooBar"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "foo:::bar-09_1.2/foo\\bar`20"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "fooBar09"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "f9ooBar09"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "foo-bar"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "foo_bar"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "FOO_BAR"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "FOO_BAR09"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "FOO_BAR_09"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "foo09Bar"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "FBar"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "Foo_Bar"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "URLBar"));
		assertDoesNotThrow(() -> validateClassifierFormat(ClassifierType.ENTITY, "fooB2C"));
	}

	@Test
	void shouldNotHaveValidClassifierFormat() {
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, ""));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, "  "));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, " fooBar "));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, "09fooBar"));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, "-foo-bar"));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, "_foo_bar"));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, "íáš"));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ENTITY, "foo bar"));
	}

	@Test
	void shouldRefuseInvalidCatalogName() {
		assertThrows(InvalidClassifierFormatException.class, () -> ClassifierUtils.validateClassifierFormat(ClassifierType.CATALOG, "ščř"));
		assertThrows(InvalidClassifierFormatException.class, () -> ClassifierUtils.validateClassifierFormat(ClassifierType.CATALOG, "a".repeat(300)));
	}

}