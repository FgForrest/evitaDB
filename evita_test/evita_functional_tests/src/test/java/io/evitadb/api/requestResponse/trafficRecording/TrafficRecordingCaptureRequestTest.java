/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.trafficRecording;

import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link TrafficRecordingCaptureRequest} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TrafficRecordingCaptureRequest")
class TrafficRecordingCaptureRequestTest {

	@Nested
	@DisplayName("Builder defaults")
	class BuilderDefaults {

		@Test
		@DisplayName("should default content to HEADER")
		void shouldDefaultContentToHeader() {
			final TrafficRecordingCaptureRequest request =
				TrafficRecordingCaptureRequest.builder().build();
			assertEquals(TrafficRecordingContent.HEADER, request.content());
		}

		@Test
		@DisplayName("should default all optional fields to null")
		void shouldDefaultAllOptionalFieldsToNull() {
			final TrafficRecordingCaptureRequest request =
				TrafficRecordingCaptureRequest.builder().build();
			assertNull(request.since());
			assertNull(request.sinceSessionSequenceId());
			assertNull(request.sinceRecordSessionOffset());
			assertNull(request.types());
			assertNull(request.sessionId());
			assertNull(request.longerThan());
			assertNull(request.fetchingMoreBytesThan());
			assertNull(request.labels());
		}

		@Test
		@DisplayName("should have empty labelsGroupedByName when labels are null")
		void shouldHaveEmptyLabelsGroupedByNameWhenLabelsAreNull() {
			final TrafficRecordingCaptureRequest request =
				TrafficRecordingCaptureRequest.builder().build();
			assertTrue(request.labelsGroupedByName().isEmpty());
		}
	}

	@Nested
	@DisplayName("Builder setters")
	class BuilderSetters {

		@Test
		@DisplayName("should set all fields")
		void shouldSetAllFields() {
			final OffsetDateTime since = OffsetDateTime.now();
			final UUID sessionId = UUID.randomUUID();
			final Label[] labels = {new Label("env", "prod")};

			final TrafficRecordingCaptureRequest request =
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.since(since)
					.sinceSessionSequenceId(42L)
					.sinceRecordSessionOffset(5)
					.type(TrafficRecordingType.QUERY, TrafficRecordingType.MUTATION)
					.sessionId(sessionId)
					.longerThan(Duration.ofSeconds(10))
					.fetchingMoreBytesThan(1024)
					.labels(labels)
					.build();

			assertEquals(TrafficRecordingContent.BODY, request.content());
			assertEquals(since, request.since());
			assertEquals(42L, request.sinceSessionSequenceId());
			assertEquals(5, request.sinceRecordSessionOffset());
			assertArrayEquals(
				new TrafficRecordingType[]{TrafficRecordingType.QUERY, TrafficRecordingType.MUTATION},
				request.types()
			);
			assertArrayEquals(new UUID[]{sessionId}, request.sessionId());
			assertEquals(Duration.ofSeconds(10), request.longerThan());
			assertEquals(1024, request.fetchingMoreBytesThan());
			assertArrayEquals(labels, request.labels());
		}

		@Test
		@DisplayName("should support fluent chaining")
		void shouldSupportFluentChaining() {
			final TrafficRecordingCaptureRequest.Builder builder =
				TrafficRecordingCaptureRequest.builder();
			assertSame(builder, builder.content(TrafficRecordingContent.BODY));
			assertSame(builder, builder.since(OffsetDateTime.now()));
			assertSame(builder, builder.sinceSessionSequenceId(1L));
			assertSame(builder, builder.sinceRecordSessionOffset(0));
			assertSame(builder, builder.type(TrafficRecordingType.QUERY));
			assertSame(builder, builder.sessionId(UUID.randomUUID()));
			assertSame(builder, builder.longerThan(Duration.ofSeconds(1)));
			assertSame(builder, builder.fetchingMoreBytesThan(100));
			assertSame(builder, builder.labels(new Label("k", "v")));
		}
	}

	@Nested
	@DisplayName("Builder copy constructor")
	class BuilderCopyConstructor {

		@Test
		@DisplayName("should copy all fields from existing request")
		void shouldCopyAllFieldsFromExistingRequest() {
			final OffsetDateTime since = OffsetDateTime.now();
			final UUID sessionId = UUID.randomUUID();

			final TrafficRecordingCaptureRequest original =
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.since(since)
					.sinceSessionSequenceId(99L)
					.sinceRecordSessionOffset(3)
					.type(TrafficRecordingType.FETCH)
					.sessionId(sessionId)
					.longerThan(Duration.ofMinutes(1))
					.fetchingMoreBytesThan(2048)
					.build();

			final TrafficRecordingCaptureRequest copy =
				TrafficRecordingCaptureRequest.builder(original).build();

			assertEquals(original.content(), copy.content());
			assertEquals(original.since(), copy.since());
			assertEquals(original.sinceSessionSequenceId(), copy.sinceSessionSequenceId());
			assertEquals(original.sinceRecordSessionOffset(), copy.sinceRecordSessionOffset());
			assertArrayEquals(original.types(), copy.types());
			assertArrayEquals(original.sessionId(), copy.sessionId());
			assertEquals(original.longerThan(), copy.longerThan());
			assertEquals(original.fetchingMoreBytesThan(), copy.fetchingMoreBytesThan());
		}

		@Test
		@DisplayName("should copy labels from existing request")
		void shouldCopyLabelsFromExistingRequest() {
			final Label[] labels = {new Label("env", "prod"), new Label("team", "backend")};

			final TrafficRecordingCaptureRequest original =
				TrafficRecordingCaptureRequest.builder()
					.labels(labels)
					.build();

			final TrafficRecordingCaptureRequest copy =
				TrafficRecordingCaptureRequest.builder(original).build();

			assertArrayEquals(original.labels(), copy.labels());
		}
	}

	@Nested
	@DisplayName("Labels grouped by name")
	class LabelsGroupedByName {

		@Test
		@DisplayName("should group labels by name")
		void shouldGroupLabelsByName() {
			final Label[] labels = {
				new Label("env", "prod"),
				new Label("env", "staging"),
				new Label("team", "backend")
			};

			final TrafficRecordingCaptureRequest request =
				TrafficRecordingCaptureRequest.builder()
					.labels(labels)
					.build();

			final Map<String, List<java.io.Serializable>> grouped =
				request.labelsGroupedByName();
			assertEquals(2, grouped.size());
			assertEquals(List.of("prod", "staging"), grouped.get("env"));
			assertEquals(List.of("backend"), grouped.get("team"));
		}

		@Test
		@DisplayName("should return empty map when labels are null")
		void shouldReturnEmptyMapWhenLabelsAreNull() {
			final TrafficRecordingCaptureRequest request =
				TrafficRecordingCaptureRequest.builder().build();
			assertTrue(request.labelsGroupedByName().isEmpty());
		}

		@Test
		@DisplayName("should handle single label")
		void shouldHandleSingleLabel() {
			final TrafficRecordingCaptureRequest request =
				TrafficRecordingCaptureRequest.builder()
					.labels(new Label("key", "value"))
					.build();

			final Map<String, List<java.io.Serializable>> grouped =
				request.labelsGroupedByName();
			assertEquals(1, grouped.size());
			assertEquals(List.of("value"), grouped.get("key"));
		}
	}

	@Nested
	@DisplayName("Enums")
	class Enums {

		@Test
		@DisplayName("should have 8 TrafficRecordingType values")
		void shouldHave8TrafficRecordingTypeValues() {
			assertEquals(8, TrafficRecordingType.values().length);
		}

		@Test
		@DisplayName("should contain all expected TrafficRecordingType values")
		void shouldContainAllExpectedTrafficRecordingTypeValues() {
			assertNotNull(TrafficRecordingType.valueOf("SESSION_START"));
			assertNotNull(TrafficRecordingType.valueOf("SESSION_CLOSE"));
			assertNotNull(TrafficRecordingType.valueOf("SOURCE_QUERY"));
			assertNotNull(TrafficRecordingType.valueOf("SOURCE_QUERY_STATISTICS"));
			assertNotNull(TrafficRecordingType.valueOf("QUERY"));
			assertNotNull(TrafficRecordingType.valueOf("FETCH"));
			assertNotNull(TrafficRecordingType.valueOf("ENRICHMENT"));
			assertNotNull(TrafficRecordingType.valueOf("MUTATION"));
		}

		@Test
		@DisplayName("should have 2 TrafficRecordingContent values")
		void shouldHave2TrafficRecordingContentValues() {
			assertEquals(2, TrafficRecordingContent.values().length);
		}

		@Test
		@DisplayName("should contain HEADER and BODY content values")
		void shouldContainHeaderAndBodyContentValues() {
			assertNotNull(TrafficRecordingContent.valueOf("HEADER"));
			assertNotNull(TrafficRecordingContent.valueOf("BODY"));
		}
	}
}
