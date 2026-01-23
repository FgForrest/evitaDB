/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;

import javax.annotation.Nonnull;

/**
 * Entity with global attribute for testing duplicate mutation prevention.
 * When the same entity class is analyzed twice, no duplicate mutations should be generated
 * for the global attribute that is already defined.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = EntityWithGlobalAttributeForEvolution.ENTITY_NAME)
public interface EntityWithGlobalAttributeForEvolution {

	String ENTITY_NAME = "EntityWithGlobalAttr";

	/**
	 * Returns the primary key of the entity.
	 *
	 * @return primary key
	 */
	@PrimaryKey
	int getId();

	/**
	 * Returns the global code attribute. This attribute is defined as global,
	 * meaning it is shared across all entities in the catalog. It is also marked
	 * as representative to help identify entities in developer tools.
	 *
	 * @return global code
	 */
	@Attribute(global = true, filterable = true, representative = true)
	@Nonnull
	String getGlobalCode();

}
