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

package io.evitadb.core.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ReferencedEntityFetcher.GroupMapping} covering:
 * - single default group mapping
 * - multiple groups per different entityPrimaryKeys
 * - multiple groups for the same entityPrimaryKey
 */
@DisplayName("GroupMapping should correctly map entities to groups in all scenarios")
class ReferencedEntityFetcherGroupMappingTest {
	@Test
	@DisplayName("shouldReturnDefaultGroupWhenEntityMappedToDefaultGroupOnly")
	void shouldReturnDefaultGroupWhenEntityMappedToDefaultGroupOnly() {
		final int defaultGroup = 100;
		final int e1 = 1;
		final int e2 = 2;

		final ReferencedEntityFetcher.GroupMapping mapping =
			new ReferencedEntityFetcher.GroupMapping(e1, defaultGroup, 4);

		// add another entity to the same default group
		mapping.addMapping(e2, defaultGroup);

		assertArrayEquals(new int[]{defaultGroup}, mapping.getGroupId(e1).toArray());
		assertArrayEquals(new int[]{defaultGroup}, mapping.getGroupId(e2).toArray());
		// unknown entity should have no mapping
		assertEquals(0, mapping.getGroupId(999).count());
	}

	@Test
	@DisplayName("shouldReturnDistinctGroupPerEntityWhenMappedToDifferentGroups")
	void shouldReturnDistinctGroupPerEntityWhenMappedToDifferentGroups() {
		final int defaultGroup = 100;
		final int e0 = 0;
		final int e1 = 1;
		final int e2 = 2;
		final int g1 = 101;
		final int g2 = 102;

		final ReferencedEntityFetcher.GroupMapping mapping =
			new ReferencedEntityFetcher.GroupMapping(e0, defaultGroup, 8);

		// map entities to groups that are different from the default
		mapping.addMapping(e1, g1);
		mapping.addMapping(e2, g2);

		assertArrayEquals(new int[]{defaultGroup}, mapping.getGroupId(e0).toArray());
		assertArrayEquals(new int[]{g1}, mapping.getGroupId(e1).toArray());
		assertArrayEquals(new int[]{g2}, mapping.getGroupId(e2).toArray());
	}

	@Test
	@DisplayName("shouldReturnMultipleGroupsForSameEntityWhenMappedToMultipleGroups")
	void shouldReturnMultipleGroupsForSameEntityWhenMappedToMultipleGroups() {
		final int defaultGroup = 100;
		final int eSame = 42;
		final int g1 = 200;
		final int g2 = 201;

		final ReferencedEntityFetcher.GroupMapping mapping =
			new ReferencedEntityFetcher.GroupMapping(0, defaultGroup, 8);

		// same entity mapped to two different, non-default groups
		mapping.addMapping(eSame, g1);
		mapping.addMapping(eSame, g2);

		final int[] groups = mapping.getGroupId(eSame).sorted().toArray();
		assertArrayEquals(new int[]{g1, g2}, groups);
	}
}
