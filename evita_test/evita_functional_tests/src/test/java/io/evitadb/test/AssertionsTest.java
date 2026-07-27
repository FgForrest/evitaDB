/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import javax.annotation.Nullable;

import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.CONTRACT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the deep-content assertion helpers of {@link Assertions}, which delegate to the
 * {@link ContentComparator#differsFrom} contract rather than {@link Object#equals}: `assertDiffers`
 * must pass exactly when the contents differ and `assertExactlyEquals` must pass exactly when they
 * are equal, and the failure branches must surface the supplied custom message.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(CONTRACT)
@Tag(COMPARATOR)
@DisplayName("Assertions deep-content comparison helpers")
class AssertionsTest {

	/**
	 * Minimal content-carrying double implementing the {@link ContentComparator} contract by comparing
	 * its own content against another box's content. Using a real comparison (rather than a canned
	 * boolean) keeps the test exercising genuine `differsFrom` behaviour instead of a hard-coded stub.
	 *
	 * @param content the content compared deep-wise against other boxes
	 */
	private record ContentBox(@Nullable String content) implements ContentComparator<ContentBox> {

		@Override
		public boolean differsFrom(@Nullable ContentBox otherObject) {
			if (otherObject == null) {
				return true;
			}
			if (this.content == null) {
				return otherObject.content != null;
			}
			return !this.content.equals(otherObject.content);
		}
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(COMPARATOR)
	@DisplayName("assertDiffers")
	class AssertDiffers {

		@Test
		@DisplayName("passes when the compared contents differ")
		void shouldPassWhenContentsDiffer() {
			assertDoesNotThrow(
				() -> Assertions.assertDiffers(new ContentBox("a"), new ContentBox("b"))
			);
		}

		@Test
		@DisplayName("fails when the compared contents are equal")
		void shouldFailWhenContentsEqual() {
			assertThrows(
				AssertionFailedError.class,
				() -> Assertions.assertDiffers(new ContentBox("a"), new ContentBox("a"))
			);
		}
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(COMPARATOR)
	@DisplayName("assertExactlyEquals")
	class AssertExactlyEquals {

		@Test
		@DisplayName("passes when the compared contents are equal")
		void shouldPassWhenContentsEqual() {
			assertDoesNotThrow(
				() -> Assertions.assertExactlyEquals(new ContentBox("a"), new ContentBox("a"))
			);
		}

		@Test
		@DisplayName("fails when the compared contents differ")
		void shouldFailWhenContentsDiffer() {
			assertThrows(
				AssertionFailedError.class,
				() -> Assertions.assertExactlyEquals(new ContentBox("a"), new ContentBox("b"))
			);
		}

		@Test
		@DisplayName("includes the supplied custom message on failure")
		void shouldIncludeCustomMessageOnFailure() {
			final AssertionFailedError error = assertThrows(
				AssertionFailedError.class,
				() -> Assertions.assertExactlyEquals(
					new ContentBox("a"), new ContentBox("b"), "boxes must match"
				)
			);
			assertTrue(error.getMessage().contains("boxes must match"));
		}
	}
}
