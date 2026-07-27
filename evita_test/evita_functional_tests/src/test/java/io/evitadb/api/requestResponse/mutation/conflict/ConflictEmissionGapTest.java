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

import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.dataType.IntegerNumberRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two conflict-key emission gaps in granular conflict resolution: a range-constrained delta
 * must emit its conflict key regardless of the resolved policy (a range guard is a hard invariant, not
 * a policy opt-out), and a forced creation ({@link EntityExistence#MUST_NOT_EXIST}) must contribute the
 * coarse entity key even when every field mutation already produced a granular key (two concurrent
 * creations of the same primary key contend on the entity's very existence).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(CONTRACT)
@Tag(TRANSACTION)
class ConflictEmissionGapTest {
	private static final String ENTITY = "Product";

	@Nested
	@DisplayName("Range-constrained delta emission")
	class ConstrainedDeltaEmission {

		@Test
		@DisplayName("Range-constrained delta emits its key even under NONE policy")
		void shouldEmitConstrainedDeltaKeyUnderNonePolicy() {
			final ConflictGenerationContext context = new ConflictGenerationContext(
				new ConflictResolution(ConflictPolicy.NONE)
			);
			final List<ConflictKey> keys = context.withEntityType(ENTITY, 1, ctx ->
				new ApplyDeltaAttributeMutation<>("stock", 5, IntegerNumberRange.between(0, 100))
					.collectConflictKeys(ctx)
					.toList()
			);
			assertEquals(1, keys.size());
			assertInstanceOf(AttributeDeltaConflictKey.class, keys.get(0));
		}

		@Test
		@DisplayName("Unconstrained delta emits nothing under NONE policy")
		void shouldNotEmitUnconstrainedDeltaKeyUnderNonePolicy() {
			final ConflictGenerationContext context = new ConflictGenerationContext(
				new ConflictResolution(ConflictPolicy.NONE)
			);
			final List<ConflictKey> keys = context.withEntityType(ENTITY, 1, ctx ->
				new ApplyDeltaAttributeMutation<>("stock", 5)
					.collectConflictKeys(ctx)
					.toList()
			);
			assertTrue(keys.isEmpty());
		}
	}

	@Nested
	@DisplayName("Forced-creation entity key emission")
	class ForcedCreationEmission {

		/**
		 * Under {@code ENTITY} coarse policy refined by {@code ENTITY_ATTRIBUTE} granularity a plain
		 * attribute upsert already produces its own granular key, so the coarse entity fallback is not
		 * triggered by a missing key — it is triggered solely by the forced-creation expectation.
		 */
		private static final ConflictResolution FULLY_GRANULAR = new ConflictResolution(
			ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)
		);

		@Test
		@DisplayName("Forced creation adds the entity key even when the mutation is fully granular")
		void shouldEmitEntityKeyForForcedCreation() {
			final List<? extends LocalMutation<?, ?>> mutations = List.of(
				new UpsertAttributeMutation("name", "foo")
			);
			final ConflictGenerationContext context = new ConflictGenerationContext(FULLY_GRANULAR);
			final List<ConflictKey> keys = context.withEntityType(ENTITY, 1, ctx ->
				EntityMutation.getConflictKeyStream(
					ENTITY, 1, mutations, EntityExistence.MUST_NOT_EXIST, ctx
				).toList()
			);
			assertTrue(keys.contains(new AttributeConflictKey(ENTITY, 1, "name")));
			assertTrue(keys.contains(new EntityConflictKey(ENTITY, 1)));
		}

		@Test
		@DisplayName("Non-forced creation of a fully granular mutation does not add the entity key")
		void shouldNotEmitEntityKeyForNonForcedCreation() {
			final List<? extends LocalMutation<?, ?>> mutations = List.of(
				new UpsertAttributeMutation("name", "foo")
			);
			final ConflictGenerationContext context = new ConflictGenerationContext(FULLY_GRANULAR);
			final List<ConflictKey> keys = context.withEntityType(ENTITY, 1, ctx ->
				EntityMutation.getConflictKeyStream(
					ENTITY, 1, mutations, EntityExistence.MAY_EXIST, ctx
				).toList()
			);
			assertTrue(keys.contains(new AttributeConflictKey(ENTITY, 1, "name")));
			assertFalse(keys.contains(new EntityConflictKey(ENTITY, 1)));
		}
	}
}
