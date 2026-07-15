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

package io.evitadb.externalApi.grpc.requestResponse;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcConflictPolicy;
import io.evitadb.externalApi.grpc.generated.GrpcConflictResolutionOverride;
import io.evitadb.externalApi.grpc.generated.GrpcGranularConflictPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the conflict-resolution enum mappings in {@link EvitaEnumConverter}. The happy-path mappings are already
 * exercised indirectly by the mutation converter tests; this test focuses on the two things those cannot reach: the
 * defensive throw branches (unrecognized remote values and the transitional granular policy constants that must never
 * travel the wire as a coarse policy) and the exhaustive coarse round-trips that guard against future enum additions
 * silently falling through.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EvitaEnumConverter conflict resolution mappings")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
class EvitaEnumConverterConflictTest {

	@Test
	@DisplayName("should reject an unrecognized remote conflict policy")
	void shouldThrowWhenConflictPolicyIsUnrecognized() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> EvitaEnumConverter.toConflictPolicy(GrpcConflictPolicy.UNRECOGNIZED)
		);
	}

	@Test
	@DisplayName("should reject an unrecognized remote conflict resolution override")
	void shouldThrowWhenConflictResolutionOverrideIsUnrecognized() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> EvitaEnumConverter.toConflictResolutionOverride(GrpcConflictResolutionOverride.UNRECOGNIZED)
		);
	}

	@Test
	@DisplayName("should reject an unrecognized remote granular conflict policy")
	void shouldThrowWhenGranularConflictPolicyIsUnrecognized() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> EvitaEnumConverter.toGranularConflictPolicy(GrpcGranularConflictPolicy.UNRECOGNIZED)
		);
	}

	@ParameterizedTest
	@EnumSource(value = ConflictPolicy.class, names = {"NONE", "CATALOG", "COLLECTION", "ENTITY"})
	@DisplayName("should round-trip every coarse conflict policy")
	void shouldRoundTripCoarseConflictPolicy(@Nonnull ConflictPolicy policy) {
		assertEquals(policy, EvitaEnumConverter.toConflictPolicy(EvitaEnumConverter.toGrpcConflictPolicy(policy)));
	}

	@ParameterizedTest
	@EnumSource(ConflictResolutionOverride.class)
	@DisplayName("should round-trip every conflict resolution override")
	void shouldRoundTripConflictResolutionOverride(@Nonnull ConflictResolutionOverride override) {
		assertEquals(
			override,
			EvitaEnumConverter.toConflictResolutionOverride(EvitaEnumConverter.toGrpcConflictResolutionOverride(override))
		);
	}

	@ParameterizedTest
	@EnumSource(GranularConflictPolicy.class)
	@DisplayName("should round-trip every granular conflict policy")
	void shouldRoundTripGranularConflictPolicy(@Nonnull GranularConflictPolicy policy) {
		assertEquals(
			policy,
			EvitaEnumConverter.toGranularConflictPolicy(EvitaEnumConverter.toGrpcGranularConflictPolicy(policy))
		);
	}

}
