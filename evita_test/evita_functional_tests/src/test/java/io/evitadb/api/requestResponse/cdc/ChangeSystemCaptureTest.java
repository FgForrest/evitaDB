/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.mutation.StreamDirection;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ChangeSystemCapture} verifying factory method, the `as()` method behavior,
 * and equality/hashCode semantics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ChangeSystemCapture")
class ChangeSystemCaptureTest implements EvitaTestSupport {

	private static final OffsetDateTime TIMESTAMP = OffsetDateTime.now();

	@Nested
	@DisplayName("Factory method")
	class FactoryMethod {

		@Test
		@DisplayName("should create system capture without body")
		void shouldCreateSystemCaptureWithoutBody() {
			final MutationPredicateContext context = createContext(1L);
			final ChangeSystemCapture capture = ChangeSystemCapture.systemCapture(
				context, Operation.UPSERT, null
			);

			assertNotNull(capture);
			assertEquals(1L, capture.version());
			assertEquals(0, capture.index());
			assertEquals(Operation.UPSERT, capture.operation());
			assertNull(capture.body());
		}
	}

	@Nested
	@DisplayName("as() method")
	class AsMethod {

		@Test
		@DisplayName("should throw when requesting BODY with null body")
		void shouldThrowWhenRequestingBodyWithNullBody() {
			final ChangeSystemCapture capture = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);

			assertThrows(Exception.class, () -> capture.as(ChangeCaptureContent.BODY));
		}

		@Test
		@DisplayName("should return self when requesting HEADER without body")
		void shouldReturnSelfWhenRequestingHeaderWithoutBody() {
			final ChangeSystemCapture capture = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);
			final ChangeSystemCapture result = capture.as(ChangeCaptureContent.HEADER);

			assertSame(capture, result);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityAndHashCode {

		@Test
		@DisplayName("should be equal for identical captures")
		void shouldBeEqualForIdenticalCaptures() {
			final ChangeSystemCapture capture1 = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);
			final ChangeSystemCapture capture2 = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);

			assertEquals(capture1, capture2);
			assertEquals(capture1.hashCode(), capture2.hashCode());
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final ChangeSystemCapture capture = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);

			assertEquals(capture, capture);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final ChangeSystemCapture capture = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);

			assertNotEquals(null, capture);
		}

		@Test
		@DisplayName("should not be equal to different type")
		void shouldNotBeEqualToDifferentType() {
			final ChangeSystemCapture capture = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);

			assertNotEquals("not a capture", capture);
		}

		@Test
		@DisplayName("should not be equal when version differs")
		void shouldNotBeEqualWhenVersionDiffers() {
			final ChangeSystemCapture capture1 = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);
			final ChangeSystemCapture capture2 = new ChangeSystemCapture(
				2L, 0, TIMESTAMP, Operation.UPSERT, null
			);

			assertNotEquals(capture1, capture2);
		}

		@Test
		@DisplayName("should not be equal when operation differs")
		void shouldNotBeEqualWhenOperationDiffers() {
			final ChangeSystemCapture capture1 = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.UPSERT, null
			);
			final ChangeSystemCapture capture2 = new ChangeSystemCapture(
				1L, 0, TIMESTAMP, Operation.REMOVE, null
			);

			assertNotEquals(capture1, capture2);
		}
	}

	/**
	 * Helper method to create a {@link MutationPredicateContext} for testing.
	 */
	private static MutationPredicateContext createContext(long version) {
		final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
		context.setVersion(version, 10, TIMESTAMP);
		return context;
	}
}
