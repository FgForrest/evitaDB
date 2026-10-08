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

package io.evitadb.store.wal;

import javax.annotation.Nonnull;

/**
 * Gives tests outside this package the write-ahead log's own draining seam.
 *
 * Removing a rotated log file is scheduled work: rotation only queues the removal, and the remover runs on the
 * scheduler at the earliest after a minimal scheduling gap, so nothing in the write path can be waited on to tell
 * that it happened. A test that needs the retention to have removed a file - to see what a reader positioned in it
 * is told - performs the removal on its own thread instead of polling the folder. The method doing that is
 * package-private on purpose, so tests in other packages reach it through here.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class WalRetentionTestSupport {

	private WalRetentionTestSupport() {
	}

	/**
	 * Removes, on the calling thread, every queued log file whose versions have all been processed - what the
	 * scheduled remover would do on its next run.
	 *
	 * @param mutationLog the log to remove the files of
	 */
	public static void removeEligibleWalFiles(@Nonnull AbstractMutationLog<?> mutationLog) {
		mutationLog.removeWalFiles();
	}

}
