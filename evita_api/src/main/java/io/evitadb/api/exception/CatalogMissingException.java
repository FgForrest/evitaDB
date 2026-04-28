/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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


import io.evitadb.api.CatalogState;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when an operation targets a catalog whose on-disk folder is no longer present and which has been
 * marked `{@link CatalogState#MISSING}` by the engine.
 *
 * A `MISSING` catalog remains registered in the engine's on-disk state so that the divergence between what the engine
 * knows about and what is actually on disk is visible to operators. The catalog cannot be used until it is either:
 *
 * - restored manually by putting the expected folder back in place and restarting the engine (future auto-discovery),
 * or
 * - removed explicitly via the normal catalog-removal mutation.
 *
 * **Typical Causes:**
 *
 * - The catalog folder was deleted externally while the engine was shut down.
 * - A storage volume was unmounted or became unreachable before the engine booted.
 * - Disk corruption removed or renamed the catalog directory.
 *
 * **Resolution:**
 *
 * Restore the missing folder from backup, re-create the catalog with the same name, or remove the stale registration
 * from the engine state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class CatalogMissingException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -2075431894556001137L;
	/**
	 * Name of the catalog that is in the MISSING state.
	 */
	@Getter private final String catalogName;

	/**
	 * Creates a new exception for a catalog that is in the MISSING state.
	 *
	 * @param catalogName name of the catalog whose on-disk folder is no longer present
	 */
	public CatalogMissingException(@Nonnull String catalogName) {
		super(
			"Catalog `" + catalogName + "` is marked as MISSING — its on-disk folder is no longer present. " +
				"Restore the catalog folder from a backup, recreate the catalog, or remove it from the engine state."
		);
		this.catalogName = catalogName;
	}
}
