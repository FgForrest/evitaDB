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

package io.evitadb.index.virtual;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks what {@link PartitionIdentityLedger} reports when the primary keys of partitions change between two
 * checkpoints of one collection: an undeclared change of a key's primary key, and a primary key that reappears under
 * another key - which a crash recovery may legitimately produce for the partitions it replays, and nothing produces
 * for a partition emptied in between.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Identity ledger of reference partitions")
@Tag(TEST_HARNESS)
class PartitionIdentityLedgerTest {
	/**
	 * The entity type owning the partitions of every case.
	 */
	private static final String ENTITY_TYPE = "product";
	/**
	 * The partition key of category 1.
	 */
	private static final RepresentativeReferenceKey FIRST = category(1);
	/**
	 * The partition key of category 2.
	 */
	private static final RepresentativeReferenceKey SECOND = category(2);

	@Test
	@DisplayName("two replayed partitions minted anew may take each other's former primary keys")
	void shouldAcceptReplayedPartitionsTakingTheFormerPrimaryKeysOfEachOther() {
		final PartitionIdentityLedger ledger = new PartitionIdentityLedger();
		assertNoFinding(ledger, observation(FIRST, 26, SECOND, 27));

		// the recovery mints both partitions again from the persisted sequence, in the order the log replays them
		ledger.declareReplayed(ENTITY_TYPE, Scope.LIVE, FIRST);
		ledger.declareReplayed(ENTITY_TYPE, Scope.LIVE, SECOND);
		assertNoFinding(ledger, observation(FIRST, 25, SECOND, 26));

		assertEquals(2, ledger.getComparedObservations(), "both partitions must have been compared");
	}

	@Test
	@DisplayName("the former primary key of an emptied partition reappearing under another key is reported")
	void shouldReportTheFormerPrimaryKeyOfAnEmptiedPartitionUnderAnotherKey() {
		final PartitionIdentityLedger ledger = new PartitionIdentityLedger();
		assertNoFinding(ledger, observation(FIRST, 26));

		// the partition was emptied and entered again under a new primary key - its former one was never handed out
		// again, so another partition holding it is a reuse
		ledger.declareEmptied(ENTITY_TYPE, Scope.LIVE, FIRST);
		final List<String> findings = check(ledger, observation(FIRST, 30, SECOND, 26));

		assertEquals(1, findings.size(), "exactly the reuse must be reported: " + findings);
		assertTrue(
			findings.getFirst().contains("primary key 26 was observed as the partition " + identityKeyOf(FIRST)),
			"the reuse must name the partition the primary key was first observed under: " + findings
		);
	}

	@Test
	@DisplayName("a primary key that changes without a declaration is reported")
	void shouldReportAnUndeclaredChangeOfPrimaryKey() {
		final PartitionIdentityLedger ledger = new PartitionIdentityLedger();
		assertNoFinding(ledger, observation(FIRST, 26));

		final List<String> findings = check(ledger, observation(FIRST, 27));

		assertEquals(1, findings.size(), "exactly the change must be reported: " + findings);
		assertTrue(
			findings.getFirst().contains("primary key 26 at the previous checkpoint, 27 now"),
			"the change must name both primary keys: " + findings
		);
	}

	/**
	 * Records an observation of the collection at a checkpoint and asserts the ledger reports nothing.
	 *
	 * @param ledger   the ledger
	 * @param observed the primary key of every partition, by its identity key
	 */
	private static void assertNoFinding(
		@Nonnull PartitionIdentityLedger ledger,
		@Nonnull SortedMap<String, Integer> observed
	) {
		final List<String> findings = check(ledger, observed);
		assertTrue(findings.isEmpty(), "the ledger must report nothing: " + findings);
	}

	/**
	 * Records an observation of the collection at a checkpoint.
	 *
	 * @param ledger   the ledger
	 * @param observed the primary key of every partition, by its identity key
	 * @return what the ledger reports
	 */
	@Nonnull
	private static List<String> check(
		@Nonnull PartitionIdentityLedger ledger,
		@Nonnull SortedMap<String, Integer> observed
	) {
		final List<String> findings = new ArrayList<>(2);
		ledger.check(ENTITY_TYPE, observed, findings);
		return findings;
	}

	/**
	 * Creates the observation of one partition.
	 *
	 * @param key        the partition key
	 * @param primaryKey its primary key
	 * @return the primary key by identity key
	 */
	@Nonnull
	private static SortedMap<String, Integer> observation(@Nonnull RepresentativeReferenceKey key, int primaryKey) {
		final SortedMap<String, Integer> observed = new TreeMap<>();
		observed.put(identityKeyOf(key), primaryKey);
		return observed;
	}

	/**
	 * Creates the observation of two partitions.
	 *
	 * @param firstKey         the first partition key
	 * @param firstPrimaryKey  its primary key
	 * @param secondKey        the second partition key
	 * @param secondPrimaryKey its primary key
	 * @return the primary key by identity key
	 */
	@Nonnull
	private static SortedMap<String, Integer> observation(
		@Nonnull RepresentativeReferenceKey firstKey,
		int firstPrimaryKey,
		@Nonnull RepresentativeReferenceKey secondKey,
		int secondPrimaryKey
	) {
		final SortedMap<String, Integer> observed = observation(firstKey, firstPrimaryKey);
		observed.put(identityKeyOf(secondKey), secondPrimaryKey);
		return observed;
	}

	/**
	 * Returns the identity key of a partition of the live scope.
	 *
	 * @param key the partition key
	 * @return the identity key
	 */
	@Nonnull
	private static String identityKeyOf(@Nonnull RepresentativeReferenceKey key) {
		return PartitionIdentityLedger.identityKeyOf(Scope.LIVE, key);
	}

	/**
	 * Creates the partition key of a category.
	 *
	 * @param category the category
	 * @return the partition key
	 */
	@Nonnull
	private static RepresentativeReferenceKey category(int category) {
		return new RepresentativeReferenceKey(new ReferenceKey("categories", category));
	}
}
