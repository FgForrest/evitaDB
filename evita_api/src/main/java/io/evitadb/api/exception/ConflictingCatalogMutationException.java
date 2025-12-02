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
import lombok.Getter;

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
public class ConflictingCatalogMutationException extends TransactionException {
	@Serial private static final long serialVersionUID = 4792726509766583503L;
	/**
	 * Name of the catalog where the conflict occurred.
	 */
	@Getter private final String catalogName;

	/**
	 * Key identifying the conflicting mutation scope within the catalog.
	 */
	@Getter private final ConflictKey conflictKey;

	/**
	 * The catalog version at which the change with which current transaction conflicts has been accepted (committed).
	 */
	@Getter private final long catalogVersion;

	/**
	 * Creates a new exception describing a mutation conflict for the given catalog and conflict key.
	 *
	 * @param catalogName name of the catalog where the conflict occurred
	 * @param conflictKey key identifying the conflicting mutation scope
	 */
	public ConflictingCatalogMutationException(
		@Nonnull String catalogName,
		@Nonnull ConflictKey conflictKey,
		long catalogVersion
	) {
		super(
			"Conflicting mutations detected in catalog `" + catalogName + "` for conflict key: " + conflictKey + " " +
				"between your transaction and transactions that committed before you. " +
				"Conflicting change occurred exactly at catalog version: " + catalogVersion + ". "
		);
		this.catalogName = catalogName;
		this.conflictKey = conflictKey;
		this.catalogVersion = catalogVersion;
	}

    /**
     * Creates a new exception describing a mutation conflict for the given catalog and conflict key.
     *
     * @param catalogName name of the catalog where the conflict occurred
     * @param conflictKey key identifying the conflicting mutation scope
     */
    protected ConflictingCatalogMutationException(
        @Nonnull String catalogName,
        @Nonnull ConflictKey conflictKey,
        long catalogVersion,
        @Nonnull String additionalMessage
    ) {
        super(
            "Conflicting mutations detected in catalog `" + catalogName + "` for conflict key: `" + conflictKey + "` " +
                "between your transaction and transactions that committed before you. " +
                "Conflicting change occurred exactly at catalog version: " + catalogVersion + ". " + additionalMessage
        );
        this.catalogName = catalogName;
        this.conflictKey = conflictKey;
        this.catalogVersion = catalogVersion;
    }

}
