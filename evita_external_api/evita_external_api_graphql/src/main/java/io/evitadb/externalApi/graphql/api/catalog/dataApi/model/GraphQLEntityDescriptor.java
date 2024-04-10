/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Extension of {@link EntityDescriptor} which contains GraphQL-specific fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface GraphQLEntityDescriptor extends EntityDescriptor {

	PropertyDescriptor PARENT_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("parentPrimaryKey")
		.description("""
            Returns primary key of direct parent entity or `null` if the entity is root entity.
            
            Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and
            may be referred by multiple child entities. Hierarchy is always composed of entities of same type.
            Each entity must be part of at most single hierarchy (tree).
            """)
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor PARENTS = PropertyDescriptor.builder()
		.name("parents")
		.description("""
            Returns list of parent hierarchical entities, possibly entire parent axis of the entity to the root if requested.
            
            Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and
            may be referred by multiple child entities. Hierarchy is always composed of entities of same type.
            Each entity must be part of at most single hierarchy (tree).
            """)
		// type is expected to be a list of non-hierarchical version of this entity
		.build();
	PropertyDescriptor ALL_PRICES_FOR_SALE = PropertyDescriptor.builder()
		.name("allPricesForSale")
		.description("""
            All prices for which the entity could be sold. This method can be used only when appropriate
            price related constraints are present or appropriate arguments are passed so that `currency` and `priceList`
            priority can be extracted.
            The moment is either extracted from the query/arguments as well (if present) or current date and time is used.
            """)
		.type(nonNullListRef(PriceDescriptor.THIS))
		.build();

	ObjectDescriptor THIS_NON_HIERARCHICAL = ObjectDescriptor.extend(THIS_CLASSIFIER)
		.name("NonHierarchical*")
		.build();
}
