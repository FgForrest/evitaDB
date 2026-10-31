/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures how long the entity schema builder takes to replay a long list of attribute changes. Every call that
 * restores a property replaces an earlier mutation, so the builder replays its whole list at the next read; a replay
 * that built the entity schema after each changing mutation made such a sequence cubic in the number of attributes.
 *
 * The measure is processor time, and it still depends on the machine: in a loaded run of the fast suite the same
 * restore took 77 s of processor time against under 4 s alone, most likely because the JIT compiler got no processor.
 * That is why the test lives here and not next to `EntitySchemaBuilderReplayTest`, which checks the replay result
 * deterministically.
 *
 * Calibration: restoring a property of 800 attributes takes about 3.5 s of processor time with the batched replay
 * and about 40 s with a replay that builds the entity schema after every changing mutation, on an otherwise idle
 * machine with the JaCoCo agent attached. The limit of 15 s fails the latter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity schema builder replay should")
@Tag(SLOW)
@Tag(CONTRACT)
@Tag(SCHEMA)
class EntitySchemaBuilderReplaySpeedTest {
	private static final String ENTITY_TYPE = "replayedEntity";
	private static final int ATTRIBUTE_COUNT = 800;
	private final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
		"replayCatalog",
		NamingConvention.generate("replayCatalog"),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class),
		new EntitySchemaProvider() {
			@Nonnull
			@Override
			public Collection<EntitySchemaContract> getEntitySchemas() {
				return List.of();
			}

			@Nonnull
			@Override
			public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
				return Optional.empty();
			}
		}
	);

	@DisplayName("restore a property of many attributes without rebuilding the entity schema per replayed mutation")
	@Test
	// only a guard against hanging; the assertion below measures processor time
	@Timeout(value = 10, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void shouldRestorePropertyOfManyAttributesQuickly() {
		final EntitySchemaContract storedSchema = entityWithAttributes();
		final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(this.catalogSchema, storedSchema);
		final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		final long start = threads.getCurrentThreadCpuTime();

		for (int i = 0; i < ATTRIBUTE_COUNT; i++) {
			builder.withAttribute(attributeName(i), String.class, whichIs -> whichIs.filterable().nonSortable());
		}
		for (int i = 0; i < ATTRIBUTE_COUNT; i++) {
			builder.withAttribute(attributeName(i), String.class, whichIs -> whichIs.filterable().sortable());
		}

		assertTrue(builder.toMutation().isEmpty(), "Restoring every attribute must leave nothing to send.");
		final long processorSeconds = TimeUnit.NANOSECONDS.toSeconds(threads.getCurrentThreadCpuTime() - start);
		assertTrue(processorSeconds < 15, "Restoring took " + processorSeconds + " s of processor time.");
	}

	/**
	 * Returns the name of the attribute with the given index.
	 */
	@Nonnull
	private static String attributeName(int index) {
		return "attribute" + index;
	}

	/**
	 * Returns a stored entity schema with {@link #ATTRIBUTE_COUNT} filterable and sortable attributes.
	 */
	@Nonnull
	private EntitySchemaContract entityWithAttributes() {
		final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(this.catalogSchema, EntitySchema._internalBuild(ENTITY_TYPE));
		for (int i = 0; i < ATTRIBUTE_COUNT; i++) {
			builder.withAttribute(attributeName(i), String.class, whichIs -> whichIs.filterable().sortable());
		}
		EntitySchemaContract schema = EntitySchema._internalBuild(ENTITY_TYPE);
		for (LocalEntitySchemaMutation mutation : builder.toMutation().orElseThrow().getSchemaMutations()) {
			schema = mutation.mutate(this.catalogSchema, schema);
		}
		return schema;
	}

}
