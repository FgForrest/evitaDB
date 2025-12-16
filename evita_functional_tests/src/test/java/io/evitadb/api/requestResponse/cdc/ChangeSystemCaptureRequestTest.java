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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test for {@link ChangeSystemCaptureRequest} and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class ChangeSystemCaptureRequestTest {

	@Test
	void shouldBuildWithDefaults() {
		final ChangeSystemCaptureRequest request = ChangeSystemCaptureRequest.builder()
			.build();

		assertNotNull(request);
		assertNull(request.sinceVersion());
		assertNull(request.sinceIndex());
		assertEquals(ChangeCaptureContent.HEADER, request.content());
	}

	@Test
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
