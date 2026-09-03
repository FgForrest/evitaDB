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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity;

import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Extension of {@link EntityDescriptor} with REST-specific properties.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface RestEntityDescriptor extends EntityDescriptor {

	PropertyDescriptor PARENT_ENTITY = PropertyDescriptor.builder()
		.name("parentEntity")
		.description("""
			Returns parent entity body. The entity fetch needs to be triggered using `hierarchyContent` requirement.
			The property allows to fetch entire parent axis of the entity to the root if requested.

	        Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and
	        may be referred by multiple child entities. Hierarchy is always composed of entities of same type.
	        Each entity must be part of at most single hierarchy (tree).
	        """)
		// type is expected to be a same hierarchical entity as parent
		.build();

	PropertyDescriptor PARENT_ENTITY_COMPLETE = PropertyDescriptor.builder()
		.name("parentEntityComplete")
		.description("""
			Returns the same parent axis as `parentEntity`, but under the `COMPLETE` parents behaviour of
			the `hierarchyContent` requirement: an ancestor whose requested body could not be materialized - it holds
			no data in the queried locale, it was deleted, or the parent primary key never belonged to an entity - is
			reported as a bodyless pointer instead of ending the chain, and the axis continues above it. An ancestor
			carrying a full body may therefore sit above a pointer.

			The property is present only when the fetched chain actually contains such a pointer; otherwise the chain
			is fully materialized and `parentEntity` already reports all of it. `parentEntity` never contains
			a pointer - it is cut below the first one.
			""")
		// type is expected to be a union of the same hierarchical entity as parent and its bodyless pointer
		.build();

	PropertyDescriptor ACCOMPANYING_PRICES = PropertyDescriptor.builder()
		.name("accompanyingPrices")
		.description("""
			Returns named calculated additional accompanying prices that relate to the selected price for sale
			and adhere to particular price inner record handling logic.
			""")
		// type is expected to be a dictionary of accompanying price name -> price
		.build();
}
