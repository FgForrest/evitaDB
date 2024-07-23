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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.PrimitiveIterator.OfInt;

/**
 * This test verifies {@link Iterators} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class IteratorsTest {

	@Test
	void shouldConcatMultipleIterators() {
		final OfInt it1 = "123".codePoints().iterator();
		final OfInt it2 = "456".codePoints().iterator();
		final OfInt it3 = "789".codePoints().iterator();

		final OfInt concatenatedIt = Iterators.concat(it1, it2, it3);
		final StringBuilder sb = new StringBuilder();
		while (concatenatedIt.hasNext()) {
			int character = concatenatedIt.next();
			sb.append((char) character);
		}

		Assertions.assertEquals("123456789", sb.toString());
	}

	@Test
	void shouldConcatMultiplePartiallyDepletedIterators() {
		final OfInt it1 = "123".codePoints().iterator();
		final OfInt it2 = "456".codePoints().iterator();
		final OfInt it3 = "789".codePoints().iterator();

		it1.next();
		it2.next();
		it3.next();

		final OfInt concatenatedIt = Iterators.concat(it1, it2, it3);
		final StringBuilder sb = new StringBuilder();
		while (concatenatedIt.hasNext()) {
			int character = concatenatedIt.next();
			sb.append((char) character);
		}

		Assertions.assertEquals("235689", sb.toString());
	}

}
