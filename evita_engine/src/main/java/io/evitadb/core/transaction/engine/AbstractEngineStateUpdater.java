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

package io.evitadb.core.transaction.engine;


import io.evitadb.api.requestResponse.mutation.EngineMutation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Convenience base class for {@link EngineStateUpdater} implementations that only need to carry
 * the transaction and mutation metadata. Subclasses are expected to implement the
 * {@link java.util.function.UnaryOperator#apply(Object)} method to compute the next
 * {@link io.evitadb.core.ExpandedEngineState}.
 *
 * The stored metadata is consumed by
 * {@link io.evitadb.core.transaction.engine.EngineTransactionManager} in the post-mutation phase to
 * append the WAL entry, notify observers and persist the new engine state.
 *
 * This class is immutable and thread-safe; fields are final and exposed via getters.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public abstract class AbstractEngineStateUpdater implements EngineStateUpdater {
	/**
	 * Unique identifier of the enclosing engine transaction.
	 */
	@Getter @Nonnull private final UUID transactionId;
	/**
	 * The concrete engine mutation being processed.
	 */
	@Getter @Nonnull private final EngineMutation<?> engineMutation;
}
