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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;
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
	PropertyDescriptor PRICE_FOR_SALE = PropertyDescriptor.extend(EntityDescriptor.PRICE_FOR_SALE)
		.type(nullableRef(PriceForSaleDescriptor.THIS))
		.build();
	PropertyDescriptor ALL_PRICES_FOR_SALE = PropertyDescriptor.builder()
		.name("allPricesForSale")
		.description("""
            All prices for which the entity could be sold. This method can be used only when appropriate
            price related constraints are present or appropriate arguments are passed so that `currency` and `priceList`
            priority can be extracted.
            The moment is either extracted from the query/arguments as well (if present) or current date and time is used.
            """)
		.type(nonNullListRef(PriceForSaleDescriptor.THIS))
		.build();
	// TOBEDONE #538: deprecated, remove
	PropertyDescriptor PRICE = PropertyDescriptor.builder()
		.name("price")
		.description("""
            Single price corresponding to defined arguments picked up from set of all `prices`.
            If more than one price is found, the valid one is picked. Validity is check based on query, if desired
            validity is not specified in query, current time is used. 
            """)
		.deprecate("""
			This field doesn't correctly return price according to computed price for sale and it doesn't
			respect price inner record handling. Use `accompanyingPrice` fields within the `priceForSale` field instead.
			""")
		.type(nullableRef(PriceDescriptor.THIS))
		.build();

	ObjectDescriptor THIS_NON_HIERARCHICAL = ObjectDescriptor.from(THIS_CLASSIFIER)
		.name("NonHierarchical*")
		.build();
}
