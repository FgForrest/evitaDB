/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.core.traffic;


import javax.annotation.Nonnull;

/**
 * TrafficRecordingExportSettings class encapsulates configuration settings for a one-shot, on-demand export of
 * the currently buffered traffic recording window (see {@link io.evitadb.core.traffic.task.TrafficRecordingExportTask}).
 *
 * @param catalogName          Specifies the name of the catalog whose currently buffered traffic recording window
 *                              is being exported.
 * @param chunkFileSizeInBytes Defines the size of each chunk file used to store the exported traffic recording
 *                              data within the resulting zip archive. Exported data is divided into files of this
 *                              size, aiding in data management and processing efficiency.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record TrafficRecordingExportSettings(
	@Nonnull String catalogName,
	long chunkFileSizeInBytes
) {
}
