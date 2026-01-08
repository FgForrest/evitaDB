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

package io.evitadb.api.configuration;

/**
 * Defines the type of backup to be performed during scheduled backup operations.
 *
 * - **FULL**: Complete backup of all data files including historical ones. This backup type
 *   copies all files from the catalog storage directory, including bootstrap files, catalog files,
 *   entity collection files, and WAL files. Use this for comprehensive disaster recovery scenarios.
 *
 * - **SNAPSHOT**: Active backup of only currently used data files. This backup type creates
 *   a point-in-time snapshot containing only the active storage parts, excluding historical
 *   versions. Use this for faster, more compact backups when historical data is not needed.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum BackupType {
	/**
	 * Complete backup of all data files including historical ones.
	 */
	FULL,
	/**
	 * Backup of only currently used data files and active records.
	 */
	SNAPSHOT
}
