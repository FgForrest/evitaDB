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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.EnumSet;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the invariants and the legacy write-direction bridge of the {@link ConflictResolution} value object. The
 * read-direction bridge ({@link ConflictResolution#fromLegacyPolicySet}) is already covered through the YAML
 * deserializer, so this test focuses on the parts nothing else reaches: the granularity-implies-entity invariant, the
 * null-granularity normalization, and {@link ConflictResolution#toLegacyPolicySet()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ConflictResolution value object")
@Tag(CONTRACT)
@Tag(SCHEMA)
class ConflictResolutionTest {

	@ParameterizedTest
	@EnumSource(value = ConflictPolicy.class, names = {"NONE", "CATALOG", "COLLECTION"})
	@DisplayName("should reject a granularity declared under a non-entity coarse policy")
	void shouldRejectGranularityUnderNonEntityPolicy(@Nonnull ConflictPolicy coarsePolicy) {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new ConflictResolution(coarsePolicy, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE))
		);
	}

	@Test
	@DisplayName("should accept a granularity declared under the entity policy")
	void shouldAcceptGranularityUnderEntityPolicy() {
		final ConflictResolution resolution = new ConflictResolution(
			ConflictPolicy.ENTITY,
			EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE, GranularConflictPolicy.PRICE)
		);
		assertTrue(resolution.hasGranularity());
		assertEquals(
			EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE, GranularConflictPolicy.PRICE),
			resolution.granularity()
		);
	}

	@Test
	@DisplayName("should normalize a null granularity to an empty set")
	void shouldNormalizeNullGranularityToEmptySet() {
		// a non-entity policy proves the null is normalized to an empty set BEFORE the invariant check — otherwise the
		// granularity-implies-entity assertion would reject a null granularity under CATALOG
		final ConflictResolution resolution = new ConflictResolution(ConflictPolicy.CATALOG, null);
		assertFalse(resolution.hasGranularity());
		assertTrue(resolution.granularity().isEmpty());
	}

	@Test
	@DisplayName("should report no granularity for a coarse-only resolution")
	void shouldReportNoGranularityForCoarseOnlyResolution() {
		final ConflictResolution resolution = new ConflictResolution(ConflictPolicy.CATALOG);
		assertFalse(resolution.hasGranularity());
		assertTrue(resolution.granularity().isEmpty());
	}

	@Test
	@DisplayName("should flatten a NONE policy to an empty legacy set")
	void shouldFlattenNoneToEmptyLegacySet() {
		assertEquals(
			EnumSet.noneOf(ConflictPolicy.class),
			new ConflictResolution(ConflictPolicy.NONE).toLegacyPolicySet()
		);
	}

	@Test
	@DisplayName("should flatten a coarse catalog policy to a singleton legacy set")
	void shouldFlattenCatalogToSingletonLegacySet() {
		assertEquals(
			EnumSet.of(ConflictPolicy.CATALOG),
			new ConflictResolution(ConflictPolicy.CATALOG).toLegacyPolicySet()
		);
	}

	@Test
	@DisplayName("should flatten a coarse collection policy to a singleton legacy set")
	void shouldFlattenCollectionToSingletonLegacySet() {
		assertEquals(
			EnumSet.of(ConflictPolicy.COLLECTION),
			new ConflictResolution(ConflictPolicy.COLLECTION).toLegacyPolicySet()
		);
	}

	@Test
	@DisplayName("should flatten an entity policy with granularity to entity plus the granular constants")
	void shouldFlattenEntityWithGranularityToLegacySet() {
		final ConflictResolution resolution = new ConflictResolution(
			ConflictPolicy.ENTITY,
			EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE, GranularConflictPolicy.PRICE)
		);
		assertEquals(
			EnumSet.of(ConflictPolicy.ENTITY, ConflictPolicy.ENTITY_ATTRIBUTE, ConflictPolicy.PRICE),
			resolution.toLegacyPolicySet()
		);
	}

}
