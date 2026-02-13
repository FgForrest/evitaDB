/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.exception;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when concurrent engine-level mutation operations conflict with each
 * other.
 *
 * Engine mutations operate at the evitaDB instance level rather than within a specific
 * catalog. They include operations like catalog creation, deletion, and global
 * configuration changes. Conflicts occur when two sessions concurrently attempt
 * incompatible engine-level operations.
 *
 * **Typical Causes:**
 * - Two sessions concurrently creating or deleting catalogs with the same name
 * - Concurrent modification of engine-wide configuration or state
 * - Race condition during evitaDB instance initialization or shutdown
 *
 * **Resolution:**
 * Retry the engine mutation operation. Engine-level operations are relatively rare, so
 * conflicts should be infrequent. If conflicts persist, consider serializing engine
 * mutations through a single coordination point in your application.
 *
 * **Design Note:**
 * Unlike catalog-level conflicts which provide fine-grained conflict keys, engine
 * mutations typically use coarser-grained conflict detection since they are infrequent and
 * often inherently global in scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ConflictingEngineMutationException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = 6171875392157371536L;

	/**
	 * Creates a new exception describing an engine mutation conflict.
	 *
	 * @param publicMessage detailed description of the conflict, typically including the
	 *                      conflicting operation type and conflict key
	 */
	public ConflictingEngineMutationException(@Nonnull String publicMessage) {
		super(publicMessage);
	}
}
