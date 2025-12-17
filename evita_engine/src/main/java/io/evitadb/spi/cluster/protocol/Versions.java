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

package io.evitadb.spi.cluster.protocol;

import io.evitadb.spi.cluster.protocol.recovery.RecoveryResponse;
import io.evitadb.spi.cluster.protocol.viewChange.DoViewChangeRequest;

import java.io.Serializable;

/**
 * Represents a pair of version numbers tracking both observed and committed state in VSR protocol.
 *
 * In Viewstamped Replication, two version numbers are maintained for each replicated state:
 *
 * - **Operation number (op-number)**: The highest operation that has been received and logged
 * - **Commit number**: The highest operation that has been committed (executed durably)
 *
 * The gap between these numbers represents operations that have been prepared but not yet committed.
 * This gap exists because VSR uses a two-phase protocol where operations are first prepared on
 * a quorum of replicas before being committed.
 *
 * **evitaDB Terminology Mapping:**
 *
 * - `lastObserved` corresponds to VSR's **op-number** - the last operation received and logged
 * - `lastCommitted` corresponds to VSR's **commit-number** - the last operation durably executed
 *
 * This record is used in messages like {@link DoViewChangeRequest} and
 * {@link RecoveryResponse} where replicas need to communicate their full version state.
 *
 * @param lastObserved the highest observed operation version (op-number in VSR terminology)
 * @param lastCommitted the highest committed operation version (commit-number in VSR terminology)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record Versions(
	long lastObserved,
	long lastCommitted
) implements Serializable {
}
