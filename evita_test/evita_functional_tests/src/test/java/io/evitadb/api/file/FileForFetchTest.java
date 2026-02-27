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

package io.evitadb.api.file;

import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FileForFetch} record covering construction, path resolution,
 * serialization round-trips, equality, and edge cases.
 *
 * @author Claude
 */
@DisplayName("FileForFetch")
class FileForFetchTest implements EvitaTestSupport {

	private static final UUID FILE_ID =
		UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
	private static final String NAME = "backup.zip";
	private static final String DESCRIPTION = "Daily backup";
	private static final String CONTENT_TYPE = "application/zip";
	private static final long SIZE = 1024L;
	private static final OffsetDateTime CREATED =
		OffsetDateTime.of(2025, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC);
	private static final String[] ORIGIN = new String[]{"backup", "catalog"};

	/**
	 * Creates a {@link FileForFetch} instance with all fields populated.
	 */
	private static FileForFetch createFullInstance() {
		return new FileForFetch(
			FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED, ORIGIN
		);
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with all fields")
		void shouldCreateWithAllFields() {
			final FileForFetch file = createFullInstance();

			assertEquals(FILE_ID, file.fileId());
			assertEquals("backup.zip", file.name());
			assertEquals(DESCRIPTION, file.description());
			assertEquals(CONTENT_TYPE, file.contentType());
			assertEquals(SIZE, file.totalSizeInBytes());
			assertEquals(CREATED, file.created());
			assertArrayEquals(ORIGIN, file.origin());
		}

		@Test
		@DisplayName("should sanitize name with unsupported characters")
		void shouldSanitizeNameViaConstructor() {
			final FileForFetch file = new FileForFetch(
				FILE_ID, "my file<>:\"|?.zip", DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, ORIGIN
			);

			// Unsupported characters should be replaced
			assertFalse(file.name().contains("<"));
			assertFalse(file.name().contains(">"));
			assertFalse(file.name().contains("\""));
			assertFalse(file.name().contains("|"));
			assertFalse(file.name().contains("?"));
			// Extension should be preserved
			assertTrue(file.name().endsWith(".zip"));
		}

		@Test
		@DisplayName("should accept null description")
		void shouldAcceptNullDescription() {
			final FileForFetch file = new FileForFetch(
				FILE_ID, NAME, null, CONTENT_TYPE, SIZE, CREATED, ORIGIN
			);

			assertNull(file.description());
		}

		@Test
		@DisplayName("should accept null origin")
		void shouldAcceptNullOrigin() {
			final FileForFetch file = new FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED, null
			);

			assertNull(file.origin());
		}
	}

	@Nested
	@DisplayName("Path resolution")
	class PathResolutionTest {

		@Test
		@DisplayName("should resolve metadata path")
		void shouldResolveMetadataPath() {
			final FileForFetch file = createFullInstance();
			final Path dir = Path.of("/tmp/export");

			final Path metadataPath = file.metadataPath(dir);

			assertEquals(
				dir.resolve(FILE_ID + ".metadata"),
				metadataPath
			);
		}

		@Test
		@DisplayName("should resolve content path with extension")
		void shouldResolveContentPathWithExtension() {
			final FileForFetch file = createFullInstance();
			final Path dir = Path.of("/tmp/export");

			final Path contentPath = file.path(dir);

			assertEquals(
				dir.resolve(FILE_ID + ".zip"),
				contentPath
			);
		}

		@Test
		@DisplayName("should resolve content path without extension")
		void shouldResolveContentPathWithoutExtension() {
			final FileForFetch file = new FileForFetch(
				FILE_ID, "backup", DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, ORIGIN
			);
			final Path dir = Path.of("/tmp/export");

			final Path contentPath = file.path(dir);

			assertEquals(
				dir.resolve(FILE_ID.toString()),
				contentPath
			);
		}
	}

	@Nested
	@DisplayName("Serialization")
	class SerializationTest {

		@Test
		@DisplayName("should serialize all fields to 7 lines")
		void shouldSerializeAllFieldsToLines() {
			final FileForFetch file = createFullInstance();

			final List<String> lines = file.toLines();

			assertEquals(7, lines.size());
			assertEquals(FILE_ID.toString(), lines.get(0));
			assertEquals("backup.zip", lines.get(1));
			assertEquals(DESCRIPTION, lines.get(2));
			assertEquals(CONTENT_TYPE, lines.get(3));
			assertEquals("1024", lines.get(4));
			assertNotNull(lines.get(5));
			assertEquals("backup,catalog", lines.get(6));
		}

		@Test
		@DisplayName("should deserialize from lines")
		void shouldDeserializeFromLines() {
			final List<String> lines = List.of(
				FILE_ID.toString(),
				"backup.zip",
				"Daily backup",
				"application/zip",
				"1024",
				"2025-01-15T10:30:00Z",
				"backup,catalog"
			);

			final FileForFetch file = FileForFetch.fromLines(lines);

			assertEquals(FILE_ID, file.fileId());
			assertEquals("backup.zip", file.name());
			assertEquals("Daily backup", file.description());
			assertEquals("application/zip", file.contentType());
			assertEquals(1024L, file.totalSizeInBytes());
			assertEquals(CREATED, file.created());
			assertArrayEquals(new String[]{"backup", "catalog"}, file.origin());
		}

		@Test
		@DisplayName("should round-trip with all fields")
		void shouldRoundTripWithAllFields() {
			final FileForFetch original = createFullInstance();

			final List<String> lines = original.toLines();
			final FileForFetch restored = FileForFetch.fromLines(lines);

			assertEquals(original.fileId(), restored.fileId());
			assertEquals(original.name(), restored.name());
			assertEquals(original.description(), restored.description());
			assertEquals(original.contentType(), restored.contentType());
			assertEquals(
				original.totalSizeInBytes(),
				restored.totalSizeInBytes()
			);
			assertEquals(original.created(), restored.created());
			assertArrayEquals(original.origin(), restored.origin());
		}

		@Test
		@DisplayName("should round-trip with null description")
		void shouldRoundTripWithNullDescription() {
			final FileForFetch original = new FileForFetch(
				FILE_ID, NAME, null, CONTENT_TYPE, SIZE, CREATED, ORIGIN
			);

			final List<String> lines = original.toLines();
			final FileForFetch restored = FileForFetch.fromLines(lines);

			assertNull(
				restored.description(),
				"Null description should be preserved through round-trip"
			);
		}

		@Test
		@DisplayName("should round-trip with null origin")
		void shouldRoundTripWithNullOrigin() {
			final FileForFetch original = new FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED, null
			);

			final List<String> lines = original.toLines();
			final FileForFetch restored = FileForFetch.fromLines(lines);

			assertNull(
				restored.origin(),
				"Null origin should be preserved through round-trip"
			);
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should be equal when same fileId")
		void shouldBeEqualWhenSameFileId() {
			final FileForFetch file1 = new FileForFetch(
				FILE_ID, "name1.zip", "desc1",
				"type/a", 100L, CREATED, new String[]{"a"}
			);
			final FileForFetch file2 = new FileForFetch(
				FILE_ID, "name2.zip", "desc2",
				"type/b", 200L, CREATED, new String[]{"b"}
			);

			assertEquals(file1, file2);
		}

		@Test
		@DisplayName("should not be equal when different fileId")
		void shouldNotBeEqualWhenDifferentFileId() {
			final FileForFetch file1 = createFullInstance();
			final FileForFetch file2 = new FileForFetch(
				UUID.randomUUID(), NAME, DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, ORIGIN
			);

			assertNotEquals(file1, file2);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final FileForFetch file = createFullInstance();

			assertNotEquals(null, file);
		}

		@Test
		@DisplayName("should not be equal to different type")
		void shouldNotBeEqualToDifferentType() {
			final FileForFetch file = createFullInstance();

			assertNotEquals("not a file", file);
		}

		@Test
		@DisplayName("should have consistent hashCode")
		void shouldHaveConsistentHashCode() {
			final FileForFetch file1 = new FileForFetch(
				FILE_ID, "name1.zip", "desc1",
				"type/a", 100L, CREATED, new String[]{"a"}
			);
			final FileForFetch file2 = new FileForFetch(
				FILE_ID, "name2.zip", "desc2",
				"type/b", 200L, CREATED, new String[]{"b"}
			);

			assertEquals(file1.hashCode(), file2.hashCode());
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName("should handle zero size")
		void shouldHandleZeroSize() {
			final FileForFetch file = new FileForFetch(
				FILE_ID, NAME, DESCRIPTION,
				CONTENT_TYPE, 0L, CREATED, ORIGIN
			);

			assertEquals(0L, file.totalSizeInBytes());

			// Verify round-trip with zero size
			final List<String> lines = file.toLines();
			final FileForFetch restored = FileForFetch.fromLines(lines);
			assertEquals(0L, restored.totalSizeInBytes());
		}

		@Test
		@DisplayName("should handle empty origin array")
		void shouldHandleEmptyOriginArray() {
			final FileForFetch file = new FileForFetch(
				FILE_ID, NAME, DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, new String[]{}
			);

			assertNotNull(file.origin());
			assertEquals(0, file.origin().length);
		}
	}
}
