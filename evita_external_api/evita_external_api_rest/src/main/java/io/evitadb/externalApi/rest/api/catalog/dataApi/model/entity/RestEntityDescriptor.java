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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity;

import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;

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

	PropertyDescriptor ACCOMPANYING_PRICES = PropertyDescriptor.builder()
		.name("accompanyingPrices")
		.description("""
			Returns named calculated additional accompanying prices that relate to the selected price for sale
			and adhere to particular price inner record handling logic.
			""")
		// type is expected to be a dictionary of accompanying price name -> price
		.build();
}
