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

package io.evitadb.api.statistics;

/**
 * Outcome of computing a single {@link CatalogStatisticsComponent} that the client asked for.
 *
 * **Why this exists**
 *
 * Without an explicit per-component outcome a client cannot distinguish a component it never requested from one the
 * engine could not compute - both arrive as an absent sub-message. That ambiguity is what makes a corrupted catalog
 * render as an empty catalog, which is precisely the situation a management screen is opened to investigate.
 *
 * A component is reported here **only if it was requested**. An unrequested component has no status entry at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see ComponentStatus
 */
public enum ComponentAvailability {

	/**
	 * The component was requested and computed; its sub-message is present.
	 */
	DELIVERED,

	/**
	 * The catalog is unusable (corrupted) and this component reads state that only a loaded catalog has. Components
	 * that read the file system directly - notably {@link CatalogStatisticsComponent#STORAGE_SIZE} - remain
	 * {@link #DELIVERED} even for a corrupted catalog, because file sizes are readable regardless.
	 */
	CATALOG_UNUSABLE,

	/**
	 * The component depends on an engine feature that is switched off in the current configuration - for example
	 * time-travel history when WAL retention is disabled.
	 */
	FEATURE_DISABLED,

	/**
	 * This build cannot compute the component at all. Distinct from {@link #FEATURE_DISABLED}: no configuration change
	 * makes it available.
	 */
	NOT_SUPPORTED

}
