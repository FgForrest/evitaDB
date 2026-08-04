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
 * The {@link CatalogStatisticsComponent#SESSIONS} component - how many sessions are currently open against this
 * catalog.
 *
 * Read-write sessions matter beyond their own count: an open read-write session pins a catalog version, which keeps
 * superseded data files from being purged. Pair a stubbornly non-zero `activeReadWriteSessions` with
 * {@link HistoryStatistics#blockedByActiveReaderBytes()} when disk space refuses to come back.
 *
 * **Reading for a degraded catalog**
 *
 * Not delivered for an unusable catalog - it has no session registry. The component status carries
 * {@link ComponentAvailability#CATALOG_UNUSABLE}.
 *
 * @param activeSessions          total number of sessions currently open against the catalog
 * @param activeReadOnlySessions  sessions opened in read-only mode
 * @param activeReadWriteSessions sessions opened in read-write mode
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record SessionStatistics(
	int activeSessions,
	int activeReadOnlySessions,
	int activeReadWriteSessions
) {
}
