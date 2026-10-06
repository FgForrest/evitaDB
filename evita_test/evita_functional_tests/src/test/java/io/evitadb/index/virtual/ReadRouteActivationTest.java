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

// TODO JNO - remove at the end of #1615 (the whole file)
package io.evitadb.index.virtual;

import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.index.virtual.ReadRouteActivation.ReadRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link ReadRouteActivation} sets the read switch of a query line of the differential harness: on for
 * the line alone when every route of the line has landed, off for any other line, and never for an engine that
 * maintains nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read route activation of the differential harness")
@Tag(TEST_HARNESS)
@Tag(QUERY)
class ReadRouteActivationTest {
	private static final String ENTITY_TYPE = "product";

	@Test
	@DisplayName("the routes of step 4b have landed, and the facet and entity body lines still read the objects")
	void shouldLandTheRoutesOfStep4b() {
		assertEquals(
			EnumSet.of(
				ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.REFERENCE_HAVING_TARGET_INDEXES,
				ReadRoute.REFERENCED_ENTITY_IN_BODY, ReadRoute.PARTITION_TO_TARGET, ReadRoute.REFERENCE_CONTENT_FILTER
			),
			ReadRouteActivation.LANDED_ROUTES
		);
	}

	@Test
	@DisplayName("a line of landed routes runs with the read switch on, which is off again afterwards")
	void shouldReadVirtuallyOnALandedRouteOnly() {
		final VirtualReferencePartitionMaintenance maintenance = maintained();
		final ReadRouteActivation activation = new ReadRouteActivation(
			maintenance, Set.of(ENTITY_TYPE),
			EnumSet.of(ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.REFERENCE_HAVING_TARGET_INDEXES)
		);

		assertTrue(
			activation.run(
				EnumSet.of(ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.REFERENCE_HAVING_TARGET_INDEXES),
				() -> maintenance.isReadVirtually(ENTITY_TYPE)
			)
		);
		assertFalse(maintenance.isReadVirtually(ENTITY_TYPE), "The switch is off again after the line");
		assertFalse(
			activation.run(
				EnumSet.of(ReadRoute.REFERENCE_HAVING_BODY, ReadRoute.PARTITION_TO_TARGET),
				() -> maintenance.isReadVirtually(ENTITY_TYPE)
			),
			"A line with a route not landed reads the objects"
		);
		assertFalse(
			activation.run(EnumSet.of(ReadRoute.ENTITY_BODY), () -> maintenance.isReadVirtually(ENTITY_TYPE))
		);
	}

	@Test
	@DisplayName("a line that fails leaves the read switch off")
	void shouldTurnTheSwitchOffAfterAFailingLine() {
		final VirtualReferencePartitionMaintenance maintenance = maintained();
		final ReadRouteActivation activation = new ReadRouteActivation(
			maintenance, Set.of(ENTITY_TYPE), EnumSet.of(ReadRoute.ENTITY_BODY)
		);

		try {
			activation.run(
				EnumSet.of(ReadRoute.ENTITY_BODY),
				() -> {
					throw new IllegalStateException("the query failed");
				}
			);
		} catch (IllegalStateException expected) {
			// the failure itself is not under test
		}
		assertFalse(maintenance.isReadVirtually(ENTITY_TYPE));
	}

	@Test
	@DisplayName("an engine that maintains nothing never reads virtually")
	void shouldNeverReadVirtuallyWithoutMaintenance() {
		final VirtualReferencePartitionMaintenance maintenance = maintained();
		assertFalse(
			ReadRouteActivation.OFF.run(
				EnumSet.of(ReadRoute.ENTITY_BODY), () -> maintenance.isReadVirtually(ENTITY_TYPE)
			)
		);
	}

	/**
	 * Creates a switch holder maintaining the entity type.
	 *
	 * @return the holder
	 */
	private static VirtualReferencePartitionMaintenance maintained() {
		final VirtualReferencePartitionMaintenance maintenance = new VirtualReferencePartitionMaintenance();
		maintenance.setMaintained(ENTITY_TYPE, true);
		return maintenance;
	}

}
