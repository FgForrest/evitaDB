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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when conflicting catalog mutations are detected during transaction resolution.
 *
 * This exception signals that the current transaction attempted to commit changes to a catalog that
 * conflict with mutations that were already committed earlier for the same `ConflictKey`.
 * It is a specialization of `TransactionException` intended for catalog-level conflicts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ConflictingCatalogCommutativeMutationException extends ConflictingCatalogMutationException {
    @Serial
    private static final long serialVersionUID = 401973444573552277L;

    /**
     * Creates a new exception describing a mutation conflict for the given catalog and conflict key.
     *
     * @param catalogName    name of the catalog where the conflict occurred
     * @param conflictKey    key identifying the conflicting mutation scope
     * @param catalogVersion the catalog version at which the conflicting change was committed
     * @param message        detailed message describing the conflict
     */
    public ConflictingCatalogCommutativeMutationException(
        @Nonnull String catalogName,
        @Nonnull ConflictKey conflictKey,
        long catalogVersion,
        @Nonnull String message
    ) {
        super(catalogName, conflictKey, catalogVersion, message);
    }

}
