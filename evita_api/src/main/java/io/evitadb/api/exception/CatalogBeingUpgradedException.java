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
 * Transient exception thrown when an operation targets a catalog that is currently being upgraded from an older
 * storage protocol to the one the engine supports. The catalog is temporarily in
 * `{@link CatalogState#BEING_UPGRADED}` while `UpgradeCatalogFormatMutation` is running.
 *
 * Because the upgrade is expected to complete successfully and restore the catalog to its prior operational state,
 * callers may retry the failed operation once the upgrade finishes. This exception signals a transient unavailability,
 * not a fatal configuration error — contrast with `CatalogRequiresUpgradeException`, which indicates that the upgrade
 * has not yet been initiated and the catalog stays unreachable until operator action triggers it.
 *
 * **Typical Causes:**
 *
 * - A session was issued against the catalog while an in-progress upgrade mutation was still executing.
 * - A CDC consumer observed the `OUT_OF_DATE → BEING_UPGRADED` transition and attempted to read from the catalog
 * before the completion phase committed.
 *
 * **Resolution:**
 *
 * Wait for the upgrade to finish (observable via the engine progress API or the system change observer) and retry
 * the operation. Most client-facing code paths should treat this as a short-lived retryable error.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class CatalogBeingUpgradedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4912731569421111137L;
	/**
	 * Name of the catalog that is currently in the BEING_UPGRADED state.
	 */
	@Getter private final String catalogName;

	/**
	 * Creates a new exception for a catalog that is currently being upgraded.
	 *
	 * @param catalogName name of the catalog currently being upgraded
	 */
	public CatalogBeingUpgradedException(@Nonnull String catalogName) {
		super(
			"Catalog `" + catalogName + "` is being upgraded to a newer storage protocol. The catalog will become " +
				"available again once the upgrade completes — please retry the operation."
		);
		this.catalogName = catalogName;
	}
}
