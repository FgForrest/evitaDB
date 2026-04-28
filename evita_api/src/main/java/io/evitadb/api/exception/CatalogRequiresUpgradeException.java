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
 * Fatal exception thrown when an operation targets a catalog whose on-disk storage protocol is older than the engine
 * supports and which has therefore been marked `{@link CatalogState#OUT_OF_DATE}` by the engine.
 *
 * An `OUT_OF_DATE` catalog remains registered in the engine's on-disk state and its data files are preserved, but the
 * engine refuses to serve reads or writes until the catalog is upgraded. This exception is raised on every access
 * attempt until the upgrade mutation (`UpgradeCatalogFormatMutation`) runs successfully against the catalog.
 *
 * **Why this is fatal rather than transient:**
 *
 * Unlike `CatalogBeingUpgradedException` — which covers the short-lived window while an upgrade is running — this
 * exception indicates that the catalog is parked in a configuration that requires operator action (running the
 * upgrade mutation) before it can resume normal service. Retrying the same request will keep failing until that
 * explicit upgrade happens.
 *
 * **Typical Causes:**
 *
 * - The engine was upgraded to a newer storage protocol but the catalog has not yet been migrated.
 * - A restore from backup replayed a catalog snapshot encoded in an older protocol.
 *
 * **Resolution:**
 *
 * Execute `UpgradeCatalogFormatMutation` for the affected catalog (either directly or via the future external-API
 * upgrade endpoint). When the upgrade completes successfully, the catalog returns to its prior operational state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class CatalogRequiresUpgradeException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -2301149612891001137L;
	/**
	 * Name of the catalog that is in the OUT_OF_DATE state.
	 */
	@Getter private final String catalogName;
	/**
	 * On-disk storage protocol version detected in the catalog. {@code -1} when not known by the
	 * thrower (e.g. generic OUT_OF_DATE reporting without inspection).
	 */
	@Getter private final int fromProtocolVersion;
	/**
	 * Storage protocol version the engine expects. {@code -1} when not known by the thrower.
	 */
	@Getter private final int toProtocolVersion;

	/**
	 * Creates a new exception for a catalog that is in the OUT_OF_DATE state without known version
	 * details — used by reporting paths that do not inspect the on-disk header.
	 *
	 * @param catalogName name of the catalog whose storage protocol is older than the engine supports
	 */
	public CatalogRequiresUpgradeException(@Nonnull String catalogName) {
		this(catalogName, -1, -1);
	}

	/**
	 * Creates a new exception for a catalog whose on-disk storage protocol version is known to be
	 * older than the engine's current version. The retry hook in `Evita#loadCatalogInternal` uses
	 * the from/to numbers to build a matching `UpgradeCatalogFormatMutation`.
	 *
	 * @param catalogName name of the catalog whose storage protocol is older than the engine supports
	 * @param fromProtocolVersion the old protocol version read from the catalog header
	 * @param toProtocolVersion the current protocol version the engine expects
	 */
	public CatalogRequiresUpgradeException(@Nonnull String catalogName, int fromProtocolVersion, int toProtocolVersion) {
		super(
			"Catalog `" + catalogName + "` is on storage protocol v" + fromProtocolVersion +
				" but the engine requires v" + toProtocolVersion + ". Run the catalog format upgrade " +
				"mutation to resume normal service."
		);
		this.catalogName = catalogName;
		this.fromProtocolVersion = fromProtocolVersion;
		this.toProtocolVersion = toProtocolVersion;
	}

	/**
	 * Whether both protocol versions carry concrete (non-sentinel) values. The single-arg constructor
	 * defaults both to `-1` for reporting paths that do not inspect the on-disk header; the auto-upgrade
	 * retry hook in `Evita` keys off this predicate to refuse synthesizing an `UpgradeCatalogFormatMutation`
	 * with malformed version numbers (which would leave an unreplayable record in the engine WAL).
	 *
	 * @return {@code true} when both `fromProtocolVersion` and `toProtocolVersion` are positive
	 */
	public boolean hasValidProtocolMetadata() {
		return this.fromProtocolVersion > 0 && this.toProtocolVersion > 0;
	}
}
