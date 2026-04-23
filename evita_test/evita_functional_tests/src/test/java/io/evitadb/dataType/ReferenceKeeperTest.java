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

package io.evitadb.dataType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link ReferenceKeeper} verifying basic operations,
 * lambda usage, and generic type compatibility.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReferenceKeeper functionality")
class ReferenceKeeperTest {

	@Nested
	@DisplayName("Basic operations")
	class BasicOperationsTest {

		@Test
		@DisplayName(
			"should construct with non-null reference"
		)
		void shouldConstructWithNonNullReference() {
			final ReferenceKeeper<String> keeper =
				new ReferenceKeeper<>("hello");

			assertEquals("hello", keeper.getReference());
		}

		@Test
		@DisplayName(
			"should construct with null reference"
		)
		void shouldConstructWithNullReference() {
			final ReferenceKeeper<String> keeper =
				new ReferenceKeeper<>(null);

			assertNull(keeper.getReference());
		}

		@Test
		@DisplayName(
			"should return initial reference via getter"
		)
		void shouldReturnInitialReference() {
			final String initial = "initial";
			final ReferenceKeeper<String> keeper =
				new ReferenceKeeper<>(initial);

			assertSame(initial, keeper.getReference());
		}

		@Test
		@DisplayName(
			"should update reference via setter"
		)
		void shouldUpdateReference() {
			final ReferenceKeeper<String> keeper =
				new ReferenceKeeper<>("first");

			keeper.setReference("second");

			assertEquals("second", keeper.getReference());
		}

		@Test
		@DisplayName(
			"should allow setting null after non-null"
		)
		void shouldAllowSettingNullAfterNonNull() {
			final ReferenceKeeper<String> keeper =
				new ReferenceKeeper<>("initial");

			keeper.setReference(null);

			assertNull(keeper.getReference());
		}

		@Test
		@DisplayName(
			"should allow setting non-null after null"
		)
		void shouldAllowSettingNonNullAfterNull() {
			final ReferenceKeeper<String> keeper =
				new ReferenceKeeper<>(null);

			keeper.setReference("value");

			assertEquals("value", keeper.getReference());
		}
	}

	@Nested
	@DisplayName("Lambda usage")
	class LambdaUsageTest {

		@Test
		@DisplayName(
			"should work in Runnable that calls " +
				"setReference"
		)
		void shouldWorkInRunnable() {
			final ReferenceKeeper<String> keeper =
				new ReferenceKeeper<>(null);

			final Runnable updater =
				() -> keeper.setReference("updated");
			updater.run();

			assertEquals("updated", keeper.getReference());
		}

		@Test
		@DisplayName(
			"should work with Stream.forEach"
		)
		void shouldWorkWithStreamForEach() {
			final ReferenceKeeper<Integer> keeper =
				new ReferenceKeeper<>(0);

			final List<Integer> numbers =
				Arrays.asList(1, 2, 3, 4, 5);
			numbers.forEach(
				n -> keeper.setReference(
					keeper.getReference() + n
				)
			);

			assertEquals(15, keeper.getReference());
		}

		@Test
		@DisplayName(
			"should accumulate result in lambda"
		)
		void shouldAccumulateResultInLambda() {
			final ReferenceKeeper<StringBuilder> keeper =
				new ReferenceKeeper<>(new StringBuilder());

			final List<String> words =
				Arrays.asList("hello", " ", "world");
			words.forEach(
				w -> keeper.getReference().append(w)
			);

			assertEquals(
				"hello world",
				keeper.getReference().toString()
			);
		}
	}

	@Nested
	@DisplayName("Generic types")
	class GenericTypesTest {

		@Test
		@DisplayName("should work with String type")
		void shouldWorkWithString() {
			final ReferenceKeeper<String> keeper =
				new ReferenceKeeper<>("text");

			assertEquals("text", keeper.getReference());
		}

		@Test
		@DisplayName("should work with Integer type")
		void shouldWorkWithInteger() {
			final ReferenceKeeper<Integer> keeper =
				new ReferenceKeeper<>(42);

			assertEquals(42, keeper.getReference());
		}

		@Test
		@DisplayName("should work with List type")
		void shouldWorkWithList() {
			final List<String> list = new ArrayList<>();
			list.add("a");
			final ReferenceKeeper<List<String>> keeper =
				new ReferenceKeeper<>(list);

			assertSame(list, keeper.getReference());
			assertEquals(1, keeper.getReference().size());

			keeper.getReference().add("b");
			assertEquals(2, keeper.getReference().size());
		}

		@Test
		@DisplayName(
			"should replace reference to different " +
				"object instance"
		)
		void shouldReplaceReferenceInstance() {
			final List<String> list1 = new ArrayList<>();
			final List<String> list2 = new ArrayList<>();
			list2.add("x");

			final ReferenceKeeper<List<String>> keeper =
				new ReferenceKeeper<>(list1);

			keeper.setReference(list2);

			assertSame(list2, keeper.getReference());
			assertEquals(1, keeper.getReference().size());
		}
	}
}
