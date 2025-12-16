/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.requestResponse.system;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * This record represents the particular version of the data structure and the date / time when such version was
 * introduced to the system. Introduction of the non-transactional version is the moment when the version block was
 * introduced in the visible catalog snapshot.
 *
 * @param startVersion first version number that is included in this block
 * @param endVersion   last version number that is included in this block
 * @param introducedAt the date / time when all the versions were introduced in the visible catalog snapshot
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record MaterializedVersionBlock(
	long startVersion,
	long endVersion,
	@Nonnull OffsetDateTime introducedAt
) implements Serializable {
}
