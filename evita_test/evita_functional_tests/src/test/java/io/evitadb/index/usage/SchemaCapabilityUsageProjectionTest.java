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

package io.evitadb.index.usage;

import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two things the projection decides that nothing else can: **which order the rows arrive in**, and **how a
 * holder's raw millis are rendered**.
 *
 * The order is not cosmetic. An operator reads a capability against its neighbours - the `FILTERABLE` and `SORTABLE`
 * rows of one attribute belong together, and a reference's own `INDEXED` / `FACETED` rows belong at the head of the
 * attributes declared inside it rather than scattered among the entity's own. Two of the comparator's clauses exist
 * only for the latter and are reachable from nowhere else: the grouping that files a reference row under its own name
 * despite its null container, and the rank that puts a container's flags ahead of what it contains.
 *
 * The rendering half is what turns the holder's `never` sentinel into an explicit absence. A zero rendered as an
 * instant would report every untouched capability as last used at the epoch.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityUsageProjection
 */
@DisplayName("Schema capability usage projection")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class SchemaCapabilityUsageProjectionTest {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_STOCKS = "stocks";
	private static final String ATTRIBUTE_EAN = "ean";
	private static final String ATTRIBUTE_QUANTITY = "quantity";
	private static final String COMPOUND_QUANTITY_WITH_WAREHOUSE = "quantityWithWarehouse";
	/** A name deliberately worn by an attribute **and** a compound, which nothing but the kind tells apart. */
	private static final String SHARED_NAME = "codeAndName";

	private final SchemaCapabilityUsageRegistry registry = new SchemaCapabilityUsageRegistry();

	@Nested
	@DisplayName("The order rows arrive in")
	class RowOrder {

		@Test
		@DisplayName("A reference's own flags arrive at the head of its attributes, not among the entity's")
		void shouldGroupAReferenceOwnFlagsAheadOfItsAttributes() {
			// resolved in an order that is neither the expected one nor its reverse, so that a projection handing the
			// registry's own iteration order back cannot pass
			resolve(SchemaCapabilityKey.referenceAttribute(
				REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.FILTERABLE, Scope.LIVE
			));
			resolve(SchemaCapabilityKey.entity(ENTITY_TYPE, Capability.PRICE_INDEXED, Scope.LIVE));
			resolve(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.INDEXED, Scope.LIVE));
			resolve(SchemaCapabilityKey.sortableCompound(
				REFERENCE_STOCKS, COMPOUND_QUANTITY_WITH_WAREHOUSE, Scope.LIVE
			));
			resolve(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE));
			resolve(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.FACETED, Scope.LIVE));

			final List<SchemaCapabilityUsageStatistics> rows = project();

			// the entity level first - a null container sorts ahead of a named one - and within it the entity's own
			// flag ahead of the attribute it contains; then the reference, its own two flags ahead of the attribute
			// and the compound it declares
			assertEquals(
				List.of(
					describe(ElementKind.ENTITY, null, ENTITY_TYPE, Capability.PRICE_INDEXED, Scope.LIVE),
					describe(ElementKind.ATTRIBUTE, null, ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE),
					describe(ElementKind.REFERENCE, null, REFERENCE_STOCKS, Capability.FACETED, Scope.LIVE),
					describe(ElementKind.REFERENCE, null, REFERENCE_STOCKS, Capability.INDEXED, Scope.LIVE),
					describe(
						ElementKind.ATTRIBUTE, REFERENCE_STOCKS, ATTRIBUTE_QUANTITY, Capability.FILTERABLE, Scope.LIVE
					),
					describe(
						ElementKind.SORTABLE_COMPOUND, REFERENCE_STOCKS, COMPOUND_QUANTITY_WITH_WAREHOUSE,
						Capability.SORTABLE, Scope.LIVE
					)
				),
				describe(rows),
				"A reference's own flags were filed by their null container rather than under the reference they name"
			);
		}

		@Test
		@DisplayName("An attribute and a compound sharing a name are told apart, and both survive")
		void shouldTieBreakAnAttributeAndACompoundSharingAName() {
			// everything the comparator looks at before the kind is identical here, and the scope pair below is the
			// only thing separating the last two - the two clauses that exist purely as tiebreakers
			resolve(SchemaCapabilityKey.sortableCompound(null, SHARED_NAME, Scope.ARCHIVED));
			resolve(SchemaCapabilityKey.entityAttribute(SHARED_NAME, Capability.SORTABLE, Scope.LIVE));
			resolve(SchemaCapabilityKey.sortableCompound(null, SHARED_NAME, Scope.LIVE));

			final List<SchemaCapabilityUsageStatistics> rows = project();

			assertEquals(
				List.of(
					describe(ElementKind.ATTRIBUTE, null, SHARED_NAME, Capability.SORTABLE, Scope.LIVE),
					describe(ElementKind.SORTABLE_COMPOUND, null, SHARED_NAME, Capability.SORTABLE, Scope.LIVE),
					describe(ElementKind.SORTABLE_COMPOUND, null, SHARED_NAME, Capability.SORTABLE, Scope.ARCHIVED)
				),
				describe(rows),
				"Three elements the schema keeps apart collapsed or reordered on a name they happen to share"
			);
		}

		@Test
		@DisplayName("An unchanged registry projects to the same order every time")
		void shouldReturnTheSameOrderOnEveryProjection() {
			// the reason the comparator exists at all: a table an operator polls must not reshuffle between two reads
			// of a registry nothing has touched, and a hash order would satisfy every case above by luck
			resolve(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE));
			resolve(SchemaCapabilityKey.reference(REFERENCE_STOCKS, Capability.INDEXED, Scope.LIVE));
			resolve(SchemaCapabilityKey.entity(ENTITY_TYPE, Capability.HIERARCHY_INDEXED, Scope.LIVE));
			resolve(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_QUANTITY, Capability.SORTABLE, Scope.ARCHIVED));

			assertEquals(describe(project()), describe(project()));
		}

	}

	@Nested
	@DisplayName("What a row renders")
	class Rendering {

		@Test
		@DisplayName("The never sentinel renders as an absence, a recorded instant as itself")
		void shouldRenderTheNeverSentinelAsAnAbsence() {
			final long requestedAt = 1_700_000_000_000L;
			final long updatedAt = 1_700_000_060_000L;
			final SchemaCapabilityKey untouched = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			final SchemaCapabilityKey touched = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_QUANTITY, Capability.SORTABLE, Scope.LIVE
			);
			final long observationOpenedNoEarlierThan = System.currentTimeMillis();
			resolve(untouched);
			final SchemaCapabilityUsage holder = resolve(touched);
			holder.recordRequested(requestedAt);
			holder.recordUpdated(updatedAt);

			final SchemaCapabilityUsageStatistics untouchedRow = rowOf(untouched);
			final SchemaCapabilityUsageStatistics touchedRow = rowOf(touched);

			assertNull(
				untouchedRow.lastRequestedAt(),
				"The `never` sentinel was rendered as an instant, which reads as a request made in 1970"
			);
			assertNull(untouchedRow.lastUpdatedAt(), "The `never` sentinel was rendered as an instant");
			// the window has no sentinel to decode - it is always set, and it is the denominator that makes the two
			// zeroes above readable as `idle` rather than as `unknown`
			assertNotNull(untouchedRow.observedSince(), "A capability is observed from the moment it is minted");
			assertTrue(
				untouchedRow.observedSince().toInstant().toEpochMilli() >= observationOpenedNoEarlierThan,
				"The observation window opened before the holder existed"
			);

			assertEquals(Instant.ofEpochMilli(requestedAt), touchedRow.lastRequestedAt().toInstant());
			assertEquals(Instant.ofEpochMilli(updatedAt), touchedRow.lastUpdatedAt().toInstant());
			assertEquals(1L, touchedRow.requestedCount());
			assertEquals(1L, touchedRow.updatedCount());
		}

		@Test
		@DisplayName("Every row names the owner the projection was asked for")
		void shouldNameTheOwnerOfEveryRow() {
			// one projection serves both owners, and the entity type is the whole of the difference - a collection's
			// registry and the catalog's produce rows identical in every other field
			resolve(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE));
			resolve(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.UNIQUE, Scope.LIVE));

			for (final SchemaCapabilityUsageStatistics row : project()) {
				assertEquals(ENTITY_TYPE, row.entityType());
			}
			for (final SchemaCapabilityUsageStatistics row : projectAsCatalog()) {
				assertNull(row.entityType(), "A catalog-owned row named a collection that does not own it");
			}
		}

		@Test
		@DisplayName("A registry that has observed nothing projects to no rows")
		void shouldProjectAnEmptyRegistryToAnEmptyList() {
			assertTrue(project().isEmpty());
		}

	}

	/**
	 * Mints the holder for the key, which is what puts a row in the projection.
	 *
	 * @param key the capability to observe
	 * @return its holder
	 */
	@Nonnull
	private SchemaCapabilityUsage resolve(@Nonnull SchemaCapabilityKey key) {
		return this.registry.resolve(key);
	}

	/**
	 * Projects the registry as the collection's, which is how every case but one reads it.
	 *
	 * @return the rows
	 */
	@Nonnull
	private List<SchemaCapabilityUsageStatistics> project() {
		return SchemaCapabilityUsageProjection.project(ENTITY_TYPE, this.registry);
	}

	/**
	 * Projects the same registry as the catalog's - the one call the owner is absent from.
	 *
	 * @return the rows
	 */
	@Nonnull
	private List<SchemaCapabilityUsageStatistics> projectAsCatalog() {
		return SchemaCapabilityUsageProjection.project(null, this.registry);
	}

	/**
	 * Reads the single row describing the key.
	 *
	 * @param key the capability to look for
	 * @return its row
	 */
	@Nonnull
	private SchemaCapabilityUsageStatistics rowOf(@Nonnull SchemaCapabilityKey key) {
		for (final SchemaCapabilityUsageStatistics row : project()) {
			if (describe(row).equals(describe(key))) {
				return row;
			}
		}
		throw new AssertionError("No row was projected for " + key + ".");
	}

	/**
	 * Renders the identity of a row - everything but its counts and stamps - so that an ordering assertion can be
	 * written as one comparison of two lists and report the whole difference when it fails.
	 *
	 * @param elementKind   kind of the element
	 * @param containerName name of the declaring reference, or null
	 * @param elementName   name of the element
	 * @param capability    the flag
	 * @param scope         the scope
	 * @return the identity, rendered
	 */
	@Nonnull
	private static String describe(
		@Nonnull ElementKind elementKind,
		@Nullable String containerName,
		@Nonnull String elementName,
		@Nonnull Capability capability,
		@Nonnull Scope scope
	) {
		return elementKind + "/" + containerName + "/" + elementName + "/" + capability + "/" + scope;
	}

	/**
	 * @param row the row to render
	 * @return the same rendering {@link #describe(ElementKind, String, String, Capability, Scope)} produces
	 */
	@Nonnull
	private static String describe(@Nonnull SchemaCapabilityUsageStatistics row) {
		return describe(row.elementKind(), row.containerName(), row.elementName(), row.capability(), row.scope());
	}

	/**
	 * @param key the key to render
	 * @return the same rendering {@link #describe(ElementKind, String, String, Capability, Scope)} produces
	 */
	@Nonnull
	private static String describe(@Nonnull SchemaCapabilityKey key) {
		return describe(key.elementKind(), key.containerName(), key.elementName(), key.capability(), key.scope());
	}

	/**
	 * @param rows the rows to render
	 * @return their identities, in the order they arrived
	 */
	@Nonnull
	private static List<String> describe(@Nonnull List<SchemaCapabilityUsageStatistics> rows) {
		final List<String> result = new ArrayList<>(rows.size());
		for (final SchemaCapabilityUsageStatistics row : rows) {
			result.add(describe(row));
		}
		return result;
	}

}
