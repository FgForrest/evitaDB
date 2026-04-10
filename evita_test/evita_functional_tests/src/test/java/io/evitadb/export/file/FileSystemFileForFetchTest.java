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

package io.evitadb.export.file;

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
 * Tests for {@link FileSystemFileForFetch} record covering construction,
 * path resolution, serialization round-trips, equality, and edge cases.
 *
 * @author Claude
 */
@DisplayName("FileSystemFileForFetch")
class FileSystemFileForFetchTest implements EvitaTestSupport {

	private static final UUID FILE_ID =
		UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
	private static final String NAME = "backup.zip";
	private static final String DESCRIPTION = "Daily backup";
	private static final String CONTENT_TYPE = "application/zip";
	private static final long SIZE = 1024L;
	private static final OffsetDateTime CREATED =
		OffsetDateTime.of(2025, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC);
	private static final String[] ORIGIN = new String[]{"backup", "catalog"};
	private static final String CATALOG_NAME = "testCatalog";
	private static final long CRC32 = 123456789L;
	private static final boolean EXTERNALLY_MANAGED = false;

	/**
	 * Creates a {@link FileSystemFileForFetch} instance with all fields populated.
	 */
	private static FileSystemFileForFetch createFullInstance() {
		return new FileSystemFileForFetch(
			FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
			ORIGIN, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
		);
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with all fields")
		void shouldCreateWithAllFields() {
			final FileSystemFileForFetch file = createFullInstance();

			assertEquals(FILE_ID, file.fileId());
			assertEquals("backup.zip", file.name());
			assertEquals(DESCRIPTION, file.description());
			assertEquals(CONTENT_TYPE, file.contentType());
			assertEquals(SIZE, file.totalSizeInBytes());
			assertEquals(CREATED, file.created());
			assertArrayEquals(ORIGIN, file.origin());
			assertEquals(CATALOG_NAME, file.catalogName());
			assertEquals(CRC32, file.crc32());
			assertEquals(EXTERNALLY_MANAGED, file.externallyManaged());
		}

		@Test
		@DisplayName("should sanitize name with unsupported characters")
		void shouldSanitizeNameViaConstructor() {
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, "my file<>:\"|?.zip", DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, ORIGIN,
				CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
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
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, NAME, null, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertNull(file.description());
		}

		@Test
		@DisplayName("should accept null origin")
		void shouldAcceptNullOrigin() {
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				null, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertNull(file.origin());
		}

		@Test
		@DisplayName("should accept null catalog name")
		void shouldAcceptNullCatalogName() {
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, null, CRC32, EXTERNALLY_MANAGED
			);

			assertNull(file.catalogName());
		}
	}

	@Nested
	@DisplayName("Path resolution")
	class PathResolutionTest {

		@Test
		@DisplayName("should resolve metadata path with catalog name")
		void shouldResolveMetadataPathWithCatalogName() {
			final FileSystemFileForFetch file = createFullInstance();
			final Path dir = Path.of("/tmp/export");

			final Path metadataPath = file.metadataPath(dir);

			assertEquals(
				dir.resolve(CATALOG_NAME).resolve(FILE_ID + ".metadata"),
				metadataPath
			);
		}

		@Test
		@DisplayName("should resolve metadata path without catalog name")
		void shouldResolveMetadataPathWithoutCatalogName() {
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, null, CRC32, EXTERNALLY_MANAGED
			);
			final Path dir = Path.of("/tmp/export");

			final Path metadataPath = file.metadataPath(dir);

			assertEquals(
				dir.resolve(FILE_ID + ".metadata"),
				metadataPath
			);
		}

		@Test
		@DisplayName("should resolve content path with extension and catalog name")
		void shouldResolveContentPathWithExtensionAndCatalogName() {
			final FileSystemFileForFetch file = createFullInstance();
			final Path dir = Path.of("/tmp/export");

			final Path contentPath = file.path(dir);

			assertEquals(
				dir.resolve(CATALOG_NAME).resolve(FILE_ID + ".zip"),
				contentPath
			);
		}

		@Test
		@DisplayName("should resolve content path with extension without catalog name")
		void shouldResolveContentPathWithExtensionWithoutCatalogName() {
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, null, CRC32, EXTERNALLY_MANAGED
			);
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
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, "backup", DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, null, CRC32, EXTERNALLY_MANAGED
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
		@DisplayName("should serialize all fields to 10 lines")
		void shouldSerializeAllFieldsToLines() {
			final FileSystemFileForFetch file = createFullInstance();

			final List<String> lines = file.toLines();

			assertEquals(10, lines.size());
			assertEquals(FILE_ID.toString(), lines.get(0));
			assertEquals("backup.zip", lines.get(1));
			assertEquals(DESCRIPTION, lines.get(2));
			assertEquals(CONTENT_TYPE, lines.get(3));
			assertEquals("1024", lines.get(4));
			assertNotNull(lines.get(5));
			assertEquals("backup,catalog", lines.get(6));
			assertEquals(CATALOG_NAME, lines.get(7));
			assertEquals(Long.toString(CRC32), lines.get(8));
			assertEquals(Boolean.toString(EXTERNALLY_MANAGED), lines.get(9));
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
				"backup,catalog",
				CATALOG_NAME,
				Long.toString(CRC32),
				Boolean.toString(EXTERNALLY_MANAGED)
			);

			final FileSystemFileForFetch file =
				FileSystemFileForFetch.fromLines(lines);

			assertEquals(FILE_ID, file.fileId());
			assertEquals("backup.zip", file.name());
			assertEquals("Daily backup", file.description());
			assertEquals("application/zip", file.contentType());
			assertEquals(1024L, file.totalSizeInBytes());
			assertEquals(CREATED, file.created());
			assertArrayEquals(
				new String[]{"backup", "catalog"}, file.origin()
			);
			assertEquals(CATALOG_NAME, file.catalogName());
			assertEquals(CRC32, file.crc32());
			assertEquals(EXTERNALLY_MANAGED, file.externallyManaged());
		}

		@Test
		@DisplayName("should round-trip with all fields")
		void shouldRoundTripWithAllFields() {
			final FileSystemFileForFetch original = createFullInstance();

			final List<String> lines = original.toLines();
			final FileSystemFileForFetch restored =
				FileSystemFileForFetch.fromLines(lines);

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
			assertEquals(original.catalogName(), restored.catalogName());
			assertEquals(original.crc32(), restored.crc32());
			assertEquals(
				original.externallyManaged(),
				restored.externallyManaged()
			);
		}

		@Test
		@DisplayName("should round-trip with null catalog name")
		void shouldRoundTripWithNullCatalogName() {
			final FileSystemFileForFetch original = new FileSystemFileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, null, CRC32, EXTERNALLY_MANAGED
			);

			final List<String> lines = original.toLines();
			final FileSystemFileForFetch restored =
				FileSystemFileForFetch.fromLines(lines);

			assertNull(
				restored.catalogName(),
				"Null catalog name should be preserved through round-trip"
			);
		}

		@Test
		@DisplayName("should deserialize from minimal 7 lines")
		void shouldDeserializeFromMinimalLines() {
			final List<String> lines = List.of(
				FILE_ID.toString(),
				"backup.zip",
				"Daily backup",
				"application/zip",
				"1024",
				"2025-01-15T10:30:00Z",
				"backup,catalog"
			);

			final FileSystemFileForFetch file =
				FileSystemFileForFetch.fromLines(lines);

			assertEquals(FILE_ID, file.fileId());
			assertNull(file.catalogName());
			assertEquals(0L, file.crc32());
			assertFalse(file.externallyManaged());
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should be equal when same fileId")
		void shouldBeEqualWhenSameFileId() {
			final FileSystemFileForFetch file1 = new FileSystemFileForFetch(
				FILE_ID, "name1.zip", "desc1",
				"type/a", 100L, CREATED, new String[]{"a"},
				"cat1", 111L, false
			);
			final FileSystemFileForFetch file2 = new FileSystemFileForFetch(
				FILE_ID, "name2.zip", "desc2",
				"type/b", 200L, CREATED, new String[]{"b"},
				"cat2", 222L, true
			);

			assertEquals(file1, file2);
		}

		@Test
		@DisplayName("should not be equal when different fileId")
		void shouldNotBeEqualWhenDifferentFileId() {
			final FileSystemFileForFetch file1 = createFullInstance();
			final FileSystemFileForFetch file2 = new FileSystemFileForFetch(
				UUID.randomUUID(), NAME, DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, ORIGIN,
				CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertNotEquals(file1, file2);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final FileSystemFileForFetch file = createFullInstance();

			assertNotEquals(null, file);
		}

		@Test
		@DisplayName("should not be equal to different type")
		void shouldNotBeEqualToDifferentType() {
			final FileSystemFileForFetch file = createFullInstance();

			assertNotEquals("not a file", file);
		}

		@Test
		@DisplayName("should have consistent hashCode")
		void shouldHaveConsistentHashCode() {
			final FileSystemFileForFetch file1 = new FileSystemFileForFetch(
				FILE_ID, "name1.zip", "desc1",
				"type/a", 100L, CREATED, new String[]{"a"},
				"cat1", 111L, false
			);
			final FileSystemFileForFetch file2 = new FileSystemFileForFetch(
				FILE_ID, "name2.zip", "desc2",
				"type/b", 200L, CREATED, new String[]{"b"},
				"cat2", 222L, true
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
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, 0L, CREATED,
				ORIGIN, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertEquals(0L, file.totalSizeInBytes());

			// Verify round-trip with zero size
			final List<String> lines = file.toLines();
			final FileSystemFileForFetch restored =
				FileSystemFileForFetch.fromLines(lines);
			assertEquals(0L, restored.totalSizeInBytes());
		}

		@Test
		@DisplayName("should handle empty origin array")
		void shouldHandleEmptyOriginArray() {
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				new String[]{}, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertNotNull(file.origin());
			assertEquals(0, file.origin().length);
		}

		@Test
		@DisplayName("should handle externally managed flag")
		void shouldHandleExternallyManagedFlag() {
			final FileSystemFileForFetch file = new FileSystemFileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, CATALOG_NAME, CRC32, true
			);

			assertTrue(file.externallyManaged());

			// Verify round-trip preserves the flag
			final List<String> lines = file.toLines();
			final FileSystemFileForFetch restored =
				FileSystemFileForFetch.fromLines(lines);
			assertTrue(restored.externallyManaged());
		}
	}
}
