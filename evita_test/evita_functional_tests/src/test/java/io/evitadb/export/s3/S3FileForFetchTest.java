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

package io.evitadb.export.s3;

import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link S3FileForFetch} record covering construction,
 * name sanitization, equality semantics, toString output, and edge cases.
 *
 * @author Claude
 */
@DisplayName("S3FileForFetch")
class S3FileForFetchTest implements EvitaTestSupport {

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
	 * Creates a {@link S3FileForFetch} instance with all fields populated using
	 * the shared test constants.
	 */
	private static S3FileForFetch createFullInstance() {
		return new S3FileForFetch(
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
			final S3FileForFetch file = createFullInstance();

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
			final S3FileForFetch file = new S3FileForFetch(
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
		@DisplayName("should sanitize whitespace in name to underscores")
		void shouldSanitizeWhitespaceInName() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, "my backup file.zip", DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, ORIGIN,
				CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			// Whitespace should be replaced with underscores
			assertFalse(file.name().contains(" "));
			assertTrue(file.name().endsWith(".zip"));
		}

		@Test
		@DisplayName("should accept null description")
		void shouldAcceptNullDescription() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, null, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertNull(file.description());
		}

		@Test
		@DisplayName("should accept null origin")
		void shouldAcceptNullOrigin() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				null, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertNull(file.origin());
		}

		@Test
		@DisplayName("should accept null catalog name")
		void shouldAcceptNullCatalogName() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, null, CRC32, EXTERNALLY_MANAGED
			);

			assertNull(file.catalogName());
		}

		@Test
		@DisplayName("should accept all nullable fields as null simultaneously")
		void shouldAcceptAllNullableFieldsAsNull() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, null, CONTENT_TYPE, SIZE, CREATED,
				null, null, CRC32, EXTERNALLY_MANAGED
			);

			assertNull(file.description());
			assertNull(file.origin());
			assertNull(file.catalogName());
			// Non-nullable fields should still be populated
			assertEquals(FILE_ID, file.fileId());
			assertEquals("backup.zip", file.name());
			assertEquals(CONTENT_TYPE, file.contentType());
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should be equal when same fileId")
		void shouldBeEqualWhenSameFileId() {
			final S3FileForFetch file1 = new S3FileForFetch(
				FILE_ID, "name1.zip", "desc1",
				"type/a", 100L, CREATED, new String[]{"a"},
				"cat1", 111L, false
			);
			final S3FileForFetch file2 = new S3FileForFetch(
				FILE_ID, "name2.zip", "desc2",
				"type/b", 200L, CREATED, new String[]{"b"},
				"cat2", 222L, true
			);

			assertEquals(file1, file2);
		}

		@Test
		@DisplayName("should not be equal when different fileId")
		void shouldNotBeEqualWhenDifferentFileId() {
			final S3FileForFetch file1 = createFullInstance();
			final S3FileForFetch file2 = new S3FileForFetch(
				UUID.randomUUID(), NAME, DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, ORIGIN,
				CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertNotEquals(file1, file2);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final S3FileForFetch file = createFullInstance();

			assertNotEquals(null, file);
		}

		@Test
		@DisplayName("should not be equal to different type")
		void shouldNotBeEqualToDifferentType() {
			final S3FileForFetch file = createFullInstance();

			assertNotEquals("not a file", file);
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final S3FileForFetch file = createFullInstance();

			assertEquals(file, file);
		}

		@Test
		@DisplayName("should have consistent hashCode for equal instances")
		void shouldHaveConsistentHashCodeForEqualInstances() {
			final S3FileForFetch file1 = new S3FileForFetch(
				FILE_ID, "name1.zip", "desc1",
				"type/a", 100L, CREATED, new String[]{"a"},
				"cat1", 111L, false
			);
			final S3FileForFetch file2 = new S3FileForFetch(
				FILE_ID, "name2.zip", "desc2",
				"type/b", 200L, CREATED, new String[]{"b"},
				"cat2", 222L, true
			);

			assertEquals(file1.hashCode(), file2.hashCode());
		}

		@Test
		@DisplayName("should produce different hashCode for different fileIds")
		void shouldProduceDifferentHashCodeForDifferentFileIds() {
			final S3FileForFetch file1 = createFullInstance();
			final S3FileForFetch file2 = new S3FileForFetch(
				UUID.randomUUID(), NAME, DESCRIPTION,
				CONTENT_TYPE, SIZE, CREATED, ORIGIN,
				CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			// While hash collisions are theoretically possible,
			// two different UUIDs should almost never collide
			assertNotEquals(file1.hashCode(), file2.hashCode());
		}

		@Test
		@DisplayName("should produce stable hashCode across multiple calls")
		void shouldProduceStableHashCode() {
			final S3FileForFetch file = createFullInstance();

			final int hash1 = file.hashCode();
			final int hash2 = file.hashCode();

			assertEquals(hash1, hash2);
		}
	}

	@Nested
	@DisplayName("ToString")
	class ToStringTest {

		@Test
		@DisplayName("should include all fields in toString output")
		void shouldIncludeAllFieldsInToString() {
			final S3FileForFetch file = createFullInstance();

			final String result = file.toString();

			assertTrue(result.contains(FILE_ID.toString()));
			assertTrue(result.contains("backup.zip"));
			assertTrue(result.contains(DESCRIPTION));
			assertTrue(result.contains(CONTENT_TYPE));
			assertTrue(result.contains(String.valueOf(SIZE)));
			assertTrue(result.contains(CATALOG_NAME));
			assertTrue(result.contains(String.valueOf(CRC32)));
		}

		@Test
		@DisplayName("should handle null fields in toString without exception")
		void shouldHandleNullFieldsInToString() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, null, CONTENT_TYPE, SIZE, CREATED,
				null, null, CRC32, EXTERNALLY_MANAGED
			);

			final String result = file.toString();

			assertNotNull(result);
			assertTrue(result.contains(FILE_ID.toString()));
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName("should handle zero size")
		void shouldHandleZeroSize() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, 0L, CREATED,
				ORIGIN, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertEquals(0L, file.totalSizeInBytes());
		}

		@Test
		@DisplayName("should handle large size value")
		void shouldHandleLargeSize() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE,
				Long.MAX_VALUE, CREATED, ORIGIN,
				CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertEquals(Long.MAX_VALUE, file.totalSizeInBytes());
		}

		@Test
		@DisplayName("should handle empty origin array")
		void shouldHandleEmptyOriginArray() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				new String[]{}, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertNotNull(file.origin());
			assertEquals(0, file.origin().length);
		}

		@Test
		@DisplayName("should handle externally managed flag set to true")
		void shouldHandleExternallyManagedFlag() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, CATALOG_NAME, CRC32, true
			);

			assertTrue(file.externallyManaged());
		}

		@Test
		@DisplayName("should handle zero crc32")
		void shouldHandleZeroCrc32() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, CATALOG_NAME, 0L, EXTERNALLY_MANAGED
			);

			assertEquals(0L, file.crc32());
		}

		@Test
		@DisplayName("should handle name without extension")
		void shouldHandleNameWithoutExtension() {
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, "backup", DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				ORIGIN, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertEquals("backup", file.name());
		}

		@Test
		@DisplayName("should handle single-element origin array")
		void shouldHandleSingleElementOriginArray() {
			final String[] singleOrigin = new String[]{"export"};
			final S3FileForFetch file = new S3FileForFetch(
				FILE_ID, NAME, DESCRIPTION, CONTENT_TYPE, SIZE, CREATED,
				singleOrigin, CATALOG_NAME, CRC32, EXTERNALLY_MANAGED
			);

			assertArrayEquals(singleOrigin, file.origin());
		}
	}
}
