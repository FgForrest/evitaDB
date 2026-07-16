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

import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fixture for the class schema analyzer that declares no conflict-resolution annotations at all. It guards
 * against accidental non-inherited defaults: every item must resolve to {@link ConflictResolutionOverride#INHERITED}
 * and the entity must carry no entity-level conflict resolution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = "ConflictResolutionDefaultEntity")
@Data
@NoArgsConstructor
public class ConflictResolutionDefaultEntity {

	@PrimaryKey(autoGenerate = false)
	private int id;

	@Attribute(name = "plainCode")
	private String plainCode;

	@Attribute(name = "plainName")
	private String plainName;

	@AssociatedData(name = "plainData")
	private String plainData;

}
