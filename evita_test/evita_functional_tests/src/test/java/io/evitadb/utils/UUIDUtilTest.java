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

package io.evitadb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test verifies contract of {@link UUIDUtil} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("UUIDUtil contract tests")
class UUIDUtilTest {

	@Nested
	@DisplayName("Random UUID generation tests")
	class RandomUuidGenerationTests {

		@Test
		@DisplayName("Should generate non-null UUID")
		void shouldGenerateNonNullUuid() {
			final UUID uuid = UUIDUtil.randomUUID();
			assertNotNull(uuid);
		}

		@Test
		@DisplayName("Should generate version 4 UUID")
		void shouldGenerateVersion4Uuid() {
			final UUID uuid = UUIDUtil.randomUUID();
			assertEquals(4, uuid.version());
		}

		@Test
		@DisplayName("Should generate unique UUIDs")
		void shouldGenerateUniqueUuids() {
			final Set<UUID> generatedUuids = new HashSet<>();
			final int count = 1000;

			for (int i = 0; i < count; i++) {
				final UUID uuid = UUIDUtil.randomUUID();
				generatedUuids.add(uuid);
			}

			assertEquals(count, generatedUuids.size(), "All generated UUIDs should be unique");
		}

		@Test
		@DisplayName("Should generate UUID with proper variant")
		void shouldGenerateUuidWithProperVariant() {
			final UUID uuid = UUIDUtil.randomUUID();
			// Variant 2 (IETF standard) has variant bits set to 10x
			assertEquals(2, uuid.variant());
		}
	}

	@Nested
	@DisplayName("UUID parsing tests")
	class UuidParsingTests {

		@Test
		@DisplayName("Should parse valid UUID")
		void shouldParseValidUuid() {
			final String uuidString = "123e4567-e89b-12d3-a456-426614174000";
			final UUID uuid = UUIDUtil.uuid(uuidString);

			assertNotNull(uuid);
			assertEquals(uuidString, uuid.toString());
		}

		@Test
		@DisplayName("Should parse UUID with uppercase letters")
		void shouldParseUuidWithUppercaseLetters() {
			final String uuidString = "123E4567-E89B-12D3-A456-426614174000";
			final UUID uuid = UUIDUtil.uuid(uuidString);

			assertNotNull(uuid);
			// toString always returns lowercase
			assertEquals(uuidString.toLowerCase(), uuid.toString());
		}

		@Test
		@DisplayName("Should throw exception for invalid format")
		void shouldThrowExceptionForInvalidFormat() {
			assertThrows(NumberFormatException.class, () -> UUIDUtil.uuid("invalid-uuid"));
			assertThrows(NumberFormatException.class, () -> UUIDUtil.uuid("12345678901234567890123456789012345"));
			assertThrows(NumberFormatException.class, () -> UUIDUtil.uuid("123456789012345678901234567890123456"));
			assertThrows(NumberFormatException.class, () -> UUIDUtil.uuid("123e4567_e89b_12d3_a456_426614174000"));
		}

		@Test
		@DisplayName("Should throw exception for non-hex characters")
		void shouldThrowExceptionForNonHexCharacters() {
			assertThrows(NumberFormatException.class, () -> UUIDUtil.uuid("123g4567-e89b-12d3-a456-426614174000"));
			assertThrows(NumberFormatException.class, () -> UUIDUtil.uuid("123e4567-e89b-12d3-a456-42661417400z"));
		}

		@Test
		@DisplayName("Should throw exception for missing hyphens")
		void shouldThrowExceptionForMissingHyphens() {
			assertThrows(NumberFormatException.class, () -> UUIDUtil.uuid("123e45670e89b-12d3-a456-426614174000"));
			assertThrows(NumberFormatException.class, () -> UUIDUtil.uuid("123e4567-e89b012d3-a456-426614174000"));
		}

		@Test
		@DisplayName("Should match standard UUID.fromString")
		void shouldMatchStandardFromString() {
			final String uuidString = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11";
			final UUID standardUuid = UUID.fromString(uuidString);
			final UUID utilUuid = UUIDUtil.uuid(uuidString);

			assertEquals(standardUuid, utilUuid);
		}
	}

	@Nested
	@DisplayName("constructUUID tests")
	class ConstructUuidTests {

		@Test
		@DisplayName("Should construct valid UUID")
		void shouldConstructValidUuid() {
			final long l1 = 0x123456789ABCDEF0L;
			final long l2 = 0xFEDCBA9876543210L;
			final UUID uuid = UUIDUtil.constructUUID(l1, l2);

			assertNotNull(uuid);
		}

		@Test
		@DisplayName("Should set version to 4")
		void shouldSetVersionTo4() {
			final long l1 = 0x123456789ABCDEF0L;
			final long l2 = 0xFEDCBA9876543210L;
			final UUID uuid = UUIDUtil.constructUUID(l1, l2);

			assertEquals(4, uuid.version());
		}

		@Test
		@DisplayName("Should set proper variant")
		void shouldSetProperVariant() {
			final long l1 = 0x123456789ABCDEF0L;
			final long l2 = 0xFEDCBA9876543210L;
			final UUID uuid = UUIDUtil.constructUUID(l1, l2);

			// Variant 2 (IETF standard)
			assertEquals(2, uuid.variant());
		}

		@Test
		@DisplayName("Should handle edge case inputs")
		void shouldHandleEdgeCaseInputs() {
			// All zeros
			final UUID zeroUuid = UUIDUtil.constructUUID(0L, 0L);
			assertNotNull(zeroUuid);
			assertEquals(4, zeroUuid.version());

			// All ones
			final UUID maxUuid = UUIDUtil.constructUUID(-1L, -1L);
			assertNotNull(maxUuid);
			assertEquals(4, maxUuid.version());

			// Min and max values
			final UUID minMaxUuid = UUIDUtil.constructUUID(Long.MIN_VALUE, Long.MAX_VALUE);
			assertNotNull(minMaxUuid);
			assertEquals(4, minMaxUuid.version());
		}

		@Test
		@DisplayName("Should produce different UUIDs for different inputs")
		void shouldProduceDifferentUuidsForDifferentInputs() {
			final UUID uuid1 = UUIDUtil.constructUUID(1L, 1L);
			final UUID uuid2 = UUIDUtil.constructUUID(2L, 2L);
			final UUID uuid3 = UUIDUtil.constructUUID(1L, 2L);
			final UUID uuid4 = UUIDUtil.constructUUID(2L, 1L);

			assertNotEquals(uuid1, uuid2);
			assertNotEquals(uuid1, uuid3);
			assertNotEquals(uuid1, uuid4);
			assertNotEquals(uuid2, uuid3);
			assertNotEquals(uuid2, uuid4);
			assertNotEquals(uuid3, uuid4);
		}

		@Test
		@DisplayName("Should be deterministic")
		void shouldBeDeterministic() {
			final long l1 = 0x123456789ABCDEF0L;
			final long l2 = 0xFEDCBA9876543210L;

			final UUID uuid1 = UUIDUtil.constructUUID(l1, l2);
			final UUID uuid2 = UUIDUtil.constructUUID(l1, l2);

			assertEquals(uuid1, uuid2);
		}
	}
}
