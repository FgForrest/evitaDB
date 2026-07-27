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

package io.evitadb.api.requestResponse.schema.model;

import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityConflictResolution;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture for the class schema analyzer that declares an illegal entity-level conflict resolution: granular
 * refinements combined with a coarser-than-entity policy. Analysis must fail with the same invariant error as
 * the programmatic builder path.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(
	name = "ConflictResolutionIllegalEntity",
	conflictResolution = @EntityConflictResolution(
		inherited = false,
		policy = ConflictPolicy.CATALOG,
		granularity = { GranularConflictPolicy.PRICE }
	)
)
@Data
@NoArgsConstructor
public class ConflictResolutionIllegalEntity {

	@PrimaryKey(autoGenerate = false)
	private int id;

}
