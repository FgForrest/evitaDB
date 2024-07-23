/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
		for (ClassifierType classifierType : ClassifierType.values()) {
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "Žlutý_kůňPěl-ódy"));
		}
	}

	@Test
	void shouldHaveValidClassifierFormat() {
		for (ClassifierType classifierType : ClassifierType.values()) {
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "foobar"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "fooBar"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "fooBar09"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "f9ooBar09"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "foo-bar"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "foo_bar"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "FOO_BAR"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "FOO_BAR09"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "FOO_BAR_09"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "foo09Bar"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "FBar"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "Foo_Bar"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "URLBar"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "fooB2C"));
			assertDoesNotThrow(() -> validateClassifierFormat(classifierType, "a".repeat(255)));
		}
	}

	@Test
	void shouldNotHaveValidClassifierFormat() {
		for (ClassifierType classifierType : ClassifierType.values()) {
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, ""));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "  "));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, " fooBar "));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "09fooBar"));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "-foo-bar"));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "_foo_bar"));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "íáš"));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "foo bar"));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "foo:::bar-09_1.2/foo\\bar`20"));
			assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(classifierType, "b".repeat(256)));
		}
	}

	@Test
	void shouldNotAcceptKeywords() {
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ATTRIBUTE, "primaryKey"));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ATTRIBUTE, "PRIMARY_KEY"));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ATTRIBUTE, "primary-key"));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ATTRIBUTE, "primary_key"));
		assertThrows(InvalidClassifierFormatException.class, () -> validateClassifierFormat(ClassifierType.ATTRIBUTE, "PrimaryKey"));
	}
}
