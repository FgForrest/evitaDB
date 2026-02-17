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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link ChangeSystemCaptureRequest} and its builder covering builder defaults,
 * equality, and edge cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ChangeSystemCaptureRequest")
class ChangeSystemCaptureRequestTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Builder")
	class Builder {

		@Test
		@DisplayName("should build with defaults")
		void shouldBuildWithDefaults() {
			final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
				.build();

			assertNotNull(request);
			assertNull(request.sinceVersion());
			assertNull(request.sinceIndex());
			assertEquals(ChangeCaptureContent.HEADER, request.content());
		}

		@Test
		@DisplayName("should build with sinceVersion")
		void shouldBuildWithSinceVersion() {
			final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.build();

			assertNotNull(request);
			assertEquals(100L, request.sinceVersion());
			assertNull(request.sinceIndex());
			assertEquals(ChangeCaptureContent.HEADER, request.content());
		}

		@Test
		@DisplayName("should build with sinceIndex")
		void shouldBuildWithSinceIndex() {
			final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
				.sinceIndex(5)
				.build();

			assertNotNull(request);
			assertNull(request.sinceVersion());
			assertEquals(5, request.sinceIndex());
			assertEquals(ChangeCaptureContent.HEADER, request.content());
		}

		@Test
		@DisplayName("should build with content")
		void shouldBuildWithContent() {
			final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
				.content(ChangeCaptureContent.BODY)
				.build();

			assertNotNull(request);
			assertNull(request.sinceVersion());
			assertNull(request.sinceIndex());
			assertEquals(ChangeCaptureContent.BODY, request.content());
		}

		@Test
		@DisplayName("should build with all parameters")
		void shouldBuildWithAllParameters() {
			final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.sinceIndex(5)
				.content(ChangeCaptureContent.BODY)
				.build();

			assertNotNull(request);
			assertEquals(100L, request.sinceVersion());
			assertEquals(5, request.sinceIndex());
			assertEquals(ChangeCaptureContent.BODY, request.content());
		}

		@Test
		@DisplayName("should build multiple instances independently")
		void shouldBuildMultipleInstancesIndependently() {
			final ChangeSystemCaptureRequest request1 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.build();

			final ChangeSystemCaptureRequest request2 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(200L)
				.sinceIndex(10)
				.content(ChangeCaptureContent.BODY)
				.build();

			assertEquals(100L, request1.sinceVersion());
			assertNull(request1.sinceIndex());
			assertEquals(ChangeCaptureContent.HEADER, request1.content());

			assertEquals(200L, request2.sinceVersion());
			assertEquals(10, request2.sinceIndex());
			assertEquals(ChangeCaptureContent.BODY, request2.content());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityAndHashCode {

		@Test
		@DisplayName("should be equal for identical requests")
		void shouldHaveCorrectEquality() {
			final ChangeSystemCaptureRequest request1 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.sinceIndex(5)
				.content(ChangeCaptureContent.BODY)
				.build();

			final ChangeSystemCaptureRequest request2 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.sinceIndex(5)
				.content(ChangeCaptureContent.BODY)
				.build();

			assertEquals(request1, request2);
			assertEquals(request1.hashCode(), request2.hashCode());
		}

		@Test
		@DisplayName("should not be equal when fields differ")
		void shouldHaveCorrectInequality() {
			final ChangeSystemCaptureRequest request1 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.build();

			final ChangeSystemCaptureRequest request2 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(200L)
				.build();

			final ChangeSystemCaptureRequest request3 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.content(ChangeCaptureContent.BODY)
				.build();

			assertNotEquals(request1, request2);
			assertNotEquals(request1, request3);
			assertNotEquals(request1.hashCode(), request2.hashCode());
		}

		@Test
		@DisplayName("should not be equal when sinceIndex differs")
		void shouldNotBeEqualWhenSinceIndexDiffers() {
			final ChangeSystemCaptureRequest request1 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.sinceIndex(1)
				.build();

			final ChangeSystemCaptureRequest request2 = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.sinceIndex(2)
				.build();

			assertNotEquals(request1, request2);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
				.build();

			assertNotEquals(null, request);
		}

		@Test
		@DisplayName("should not be equal to different type")
		void shouldNotBeEqualToDifferentType() {
			final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
				.build();

			assertNotEquals("not a request", request);
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
				.sinceVersion(100L)
				.build();

			assertEquals(request, request);
		}
	}
}
