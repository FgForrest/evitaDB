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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link ChangeCatalogCaptureRequest} and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class ChangeCatalogCaptureRequestTest {

	@Test
	void shouldBuildWithDefaults() {
		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.build();

		assertNotNull(request);
		assertNull(request.sinceVersion());
		assertNull(request.sinceIndex());
		assertNull(request.criteria());
		assertEquals(ChangeCaptureContent.HEADER, request.content());
	}

	@Test
	void shouldBuildWithSinceVersion() {
		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.build();

		assertNotNull(request);
		assertEquals(100L, request.sinceVersion());
		assertNull(request.sinceIndex());
		assertNull(request.criteria());
		assertEquals(ChangeCaptureContent.HEADER, request.content());
	}

	@Test
	void shouldBuildWithSinceIndex() {
		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.sinceIndex(5)
			.build();

		assertNotNull(request);
		assertNull(request.sinceVersion());
		assertEquals(5, request.sinceIndex());
		assertNull(request.criteria());
		assertEquals(ChangeCaptureContent.HEADER, request.content());
	}

	@Test
	void shouldBuildWithContent() {
		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.content(ChangeCaptureContent.BODY)
			.build();

		assertNotNull(request);
		assertNull(request.sinceVersion());
		assertNull(request.sinceIndex());
		assertNull(request.criteria());
		assertEquals(ChangeCaptureContent.BODY, request.content());
	}

	@Test
	void shouldBuildWithSingleCriterion() {
		final ChangeCatalogCaptureCriteria criterion = ChangeCatalogCaptureCriteria.builder()
			.dataArea()
			.build();

		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.criteria(criterion)
			.build();

		assertNotNull(request);
		assertNull(request.sinceVersion());
		assertNull(request.sinceIndex());
		assertNotNull(request.criteria());
		assertEquals(1, request.criteria().length);
		assertEquals(criterion, request.criteria()[0]);
		assertEquals(ChangeCaptureContent.HEADER, request.content());
	}

	@Test
	void shouldBuildWithMultipleCriteria() {
		final ChangeCatalogCaptureCriteria criterion1 = ChangeCatalogCaptureCriteria.builder()
			.dataArea()
			.build();

		final ChangeCatalogCaptureCriteria criterion2 = ChangeCatalogCaptureCriteria.builder()
			.schemaArea()
			.build();

		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.criteria(criterion1, criterion2)
			.build();

		assertNotNull(request);
		assertNull(request.sinceVersion());
		assertNull(request.sinceIndex());
		assertNotNull(request.criteria());
		assertEquals(2, request.criteria().length);
		assertArrayEquals(new ChangeCatalogCaptureCriteria[]{criterion1, criterion2}, request.criteria());
		assertEquals(ChangeCaptureContent.HEADER, request.content());
	}

	@Test
	void shouldBuildWithAllParameters() {
		final ChangeCatalogCaptureCriteria criterion = ChangeCatalogCaptureCriteria.builder()
			.dataArea()
			.build();

		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.sinceIndex(5)
			.criteria(criterion)
			.content(ChangeCaptureContent.BODY)
			.build();

		assertNotNull(request);
		assertEquals(100L, request.sinceVersion());
		assertEquals(5, request.sinceIndex());
		assertNotNull(request.criteria());
		assertEquals(1, request.criteria().length);
		assertEquals(criterion, request.criteria()[0]);
		assertEquals(ChangeCaptureContent.BODY, request.content());
	}

	@Test
	void shouldHaveCorrectEquality() {
		final ChangeCatalogCaptureCriteria criterion = ChangeCatalogCaptureCriteria.builder()
			.dataArea()
			.build();

		final ChangeCatalogCaptureRequest request1 = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.sinceIndex(5)
			.criteria(criterion)
			.content(ChangeCaptureContent.BODY)
			.build();

		final ChangeCatalogCaptureRequest request2 = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.sinceIndex(5)
			.criteria(criterion)
			.content(ChangeCaptureContent.BODY)
			.build();

		assertEquals(request1, request2);
		assertEquals(request1.hashCode(), request2.hashCode());
	}

	@Test
	void shouldHaveCorrectInequality() {
		final ChangeCatalogCaptureCriteria criterion1 = ChangeCatalogCaptureCriteria.builder()
			.dataArea()
			.build();

		final ChangeCatalogCaptureCriteria criterion2 = ChangeCatalogCaptureCriteria.builder()
			.schemaArea()
			.build();

		final ChangeCatalogCaptureRequest request1 = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.criteria(criterion1)
			.build();

		final ChangeCatalogCaptureRequest request2 = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(200L)
			.criteria(criterion1)
			.build();

		final ChangeCatalogCaptureRequest request3 = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.criteria(criterion2)
			.build();

		final ChangeCatalogCaptureRequest request4 = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.criteria(criterion1)
			.content(ChangeCaptureContent.BODY)
			.build();

		assertNotEquals(request1, request2);
		assertNotEquals(request1, request3);
		assertNotEquals(request1, request4);
	}

	@Test
	void shouldBuildMultipleInstancesIndependently() {
		final ChangeCatalogCaptureCriteria criterion1 = ChangeCatalogCaptureCriteria.builder()
			.dataArea()
			.build();

		final ChangeCatalogCaptureCriteria criterion2 = ChangeCatalogCaptureCriteria.builder()
			.schemaArea()
			.build();

		final ChangeCatalogCaptureRequest request1 = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(100L)
			.criteria(criterion1)
			.build();

		final ChangeCatalogCaptureRequest request2 = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(200L)
			.sinceIndex(10)
			.criteria(criterion2)
			.content(ChangeCaptureContent.BODY)
			.build();

		assertEquals(100L, request1.sinceVersion());
		assertNull(request1.sinceIndex());
		assertEquals(1, request1.criteria().length);
		assertEquals(criterion1, request1.criteria()[0]);
		assertEquals(ChangeCaptureContent.HEADER, request1.content());

		assertEquals(200L, request2.sinceVersion());
		assertEquals(10, request2.sinceIndex());
		assertEquals(1, request2.criteria().length);
		assertEquals(criterion2, request2.criteria()[0]);
		assertEquals(ChangeCaptureContent.BODY, request2.content());
	}

}
