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

package io.evitadb.store.catalog;

import io.evitadb.api.query.Query;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.headerStorageProtocol;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.pks;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.requireAlive;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.upgrade.Release2026_1FixtureRecipes.INHERITED_SORT_TWINS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies a real 2026.1 catalog retains every sort record through the production upgrade chain and later writes. */
@DisplayName("Inherited sort blocks survive upgrade, reopen and mutation")
@Tag(STORAGE)
@Tag(INDEXING)
class Migration_2026_3_InheritedSortUpgradeTest implements EvitaTestSupport {
	/** Copies immutable released catalogs into the test's storage directory. */
	@Nonnull
	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/** Verifies complete merged ordering before and after reopening and persisting one changed range. */
	@Test
	void shouldUpgradeInheritedSortTwinsAndKeepEveryRecordAfterMutation() {
		final TestPaths paths = this.harness.copy(INHERITED_SORT_TWINS);
		final Query ordered = query(collection("product"), orderBy(attributeNatural("sortValidity")));
		try {
			assertEquals(4, this.harness.lastBootstrapStorageProtocol(paths, INHERITED_SORT_TWINS));
			try (final Evita evita = this.harness.boot(paths)) {
				requireAlive(evita, INHERITED_SORT_TWINS);
				assertEquals(7, headerStorageProtocol(evita, INHERITED_SORT_TWINS));
				assertEquals(List.of(1, 2, 3, 4, 5), pks(evita, INHERITED_SORT_TWINS, ordered));
			}
			try (final Evita evita = this.harness.boot(paths)) {
				requireAlive(evita, INHERITED_SORT_TWINS);
				assertEquals(List.of(1, 2, 3, 4, 5), pks(evita, INHERITED_SORT_TWINS, ordered));
				ReleaseFixtureHarness.update(
					evita, INHERITED_SORT_TWINS,
					session -> ReleaseFixtureHarness.setAttribute(
						session, 1, "sortValidity",
						DateTimeRange.until(OffsetDateTime.parse("2027-01-01T11:00:00+02:00"))
					)
				);
				assertEquals(List.of(2, 3, 4, 5, 1), pks(evita, INHERITED_SORT_TWINS, ordered));
			}
			try (final Evita evita = this.harness.boot(paths)) {
				requireAlive(evita, INHERITED_SORT_TWINS);
				assertEquals(7, headerStorageProtocol(evita, INHERITED_SORT_TWINS));
				assertEquals(List.of(2, 3, 4, 5, 1), pks(evita, INHERITED_SORT_TWINS, ordered));
			}
		} finally {
			this.harness.cleanup(paths);
		}
	}
}
