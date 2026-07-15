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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE.md
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.transaction;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.AbstractTransactionalBPlusTree.BPlusTreeCorruptedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused test of the post-replay poison-pill wrapping in {@link TransactionTrunkFinalizer}.
 *
 * A post-replay B+ tree boundary failure is raised as a NEUTRAL, tree-level {@link BPlusTreeCorruptedException}
 * (the same merge code fires on the isolated-finalizer path where write-ahead-log wording would be false). The trunk
 * incorporation path re-wraps it with the poison-pill remediation caveat — durable in the write-ahead log AND possibly
 * already in the flushed data files, because the catalog flush precedes the merge. That wrapping is exercised in
 * production only when a real merge throws, which the tree-level unit tests never reach (they observe the raw neutral
 * error); this test pins the operator-facing wording of {@link TransactionTrunkFinalizer#wrapPostReplayCorruption}
 * directly, and that it chains the neutral cause.
 */
@Tag(INDEXING)
@Tag(TRANSACTION)
@DisplayName("Trunk finalizer post-replay corruption poison-pill wrapping")
class TransactionTrunkFinalizerTest {

	@Test
	@DisplayName("wraps a neutral post-replay corruption error with the poison-pill flushed-data caveat and chains the cause")
	void shouldWrapPostReplayCorruptionWithPoisonPillCaveat() {
		final BPlusTreeCorruptedException cause = new BPlusTreeCorruptedException(
			"neutral tree-level cross-leaf boundary message"
		);
		final GenericEvitaInternalError wrapped = TransactionTrunkFinalizer.wrapPostReplayCorruption(cause);
		final String message = wrapped.getMessage();
		// the poison-pill caveat must not understate the blast radius: durable in the WAL AND possibly already flushed
		assertTrue(
			message.contains("write-ahead log"),
			"the poison-pill message must state the transaction is durable in the write-ahead log; got: " + message
		);
		assertTrue(
			message.contains("flushed data files"),
			"the poison-pill message must warn corrupt pages may already be in the flushed data files; got: " + message
		);
		assertTrue(
			message.contains("restore") || message.contains("rebuild"),
			"the poison-pill message must offer restore/rebuild remediation; got: " + message
		);
		// the neutral tree-level error is chained for the structural detail
		assertSame(cause, wrapped.getCause(), "the neutral tree-level corruption must be chained as the cause");
	}

}
