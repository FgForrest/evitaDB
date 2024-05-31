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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.test;

import io.evitadb.api.requestResponse.data.ContentComparator;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Class contains evitaDB specific assertion methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Assertions {

	/**
	 * Executes full contents comparison and fails the test if contents are equal.
	 */
	public static <T> void assertDiffers(ContentComparator<T> expected, T actual) {
		assertDiffers(expected, actual, "Object are equal!");
	}

	/**
	 * Executes full contents comparison and fails the test if contents are equal.
	 */
	public static <T> void assertDiffers(ContentComparator<T> expected, T actual, String message) {
		if (!expected.differsFrom(actual)) {
			org.junit.jupiter.api.Assertions.fail(message + "\nContents:\n" + actual);
		}
	}

	/**
	 * Executes full contents comparison and fails the test if contents are not equal.
	 */
	public static <T> void assertExactlyEquals(ContentComparator<T> expected, T actual) {
		assertExactlyEquals(expected, actual, "Object differ!");
	}

	/**
	 * Executes full contents comparison and fails the test if contents are not equal.
	 */
	public static <T> void assertExactlyEquals(ContentComparator<T> expected, T actual, String message) {
		if (expected.differsFrom(actual)) {
			org.junit.jupiter.api.Assertions.fail(message + "\nExpected:\n" + expected + "\n\nActual:\n" + actual.toString());
		}
	}

}
