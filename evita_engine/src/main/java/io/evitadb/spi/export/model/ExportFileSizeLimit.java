/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.spi.export.model;

import javax.annotation.Nonnull;

/**
 * Per-file export allowance and the configuration that controls it. This contract performs no storage access.
 *
 * @param maxFileSizeInBytes largest payload that fits together with this export's backend metadata
 * @param configuredLimitInBytes overall configured storage limit
 * @param configurationProperty server configuration property controlling the limit
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ExportFileSizeLimit(
	long maxFileSizeInBytes,
	long configuredLimitInBytes,
	@Nonnull String configurationProperty
) {
}
