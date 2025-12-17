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

package io.evitadb.spi.cluster.exception;

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Base exception class for all cluster-related errors in the VSR protocol implementation.
 *
 * This abstract exception serves as the root of the cluster exception hierarchy, providing
 * a common type for catching all cluster-related errors. Subclasses represent specific
 * failure modes that can occur during cluster operations.
 *
 * **Common Failure Scenarios:**
 *
 * - Hash chain verification failures ({@link CorruptedHashChainException})
 * - View number mismatches (replica has stale view)
 * - Epoch mismatches (replica has stale configuration)
 * - Communication failures between replicas
 * - Quorum not reachable
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see CorruptedHashChainException
 */
public abstract class ClusterException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -3535311158403102118L;

	protected ClusterException(
		@Nonnull String privateMessage,
		@Nonnull String publicMessage
	) {
		super(privateMessage, publicMessage);
	}

	protected ClusterException(@Nonnull String publicMessage) {
		super(publicMessage);
	}

	protected ClusterException(
		@Nonnull String privateMessage,
		@Nonnull String publicMessage,
		@Nonnull Throwable cause
	) {
		super(privateMessage, publicMessage, cause);
	}

	protected ClusterException(
		@Nonnull String publicMessage,
		@Nonnull Throwable cause
	) {
		super(publicMessage, cause);
	}

	protected ClusterException(
		@Nonnull String privateMessage,
		@Nonnull String publicMessage,
		@Nonnull String errorCode
	) {
		super(privateMessage, publicMessage, errorCode);
	}

}
