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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity;

import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;

/**
 * Extension of {@link PriceDescriptor} specific for "price for sale" prices.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public interface PriceForSaleDescriptor extends PriceDescriptor {

	PropertyDescriptor ACCOMPANYING_PRICE = PropertyDescriptor.builder()
		.name("accompanyingPrice")
		.description("""
			Allows to calculate and return additional accompanying prices that relate to the selected price for sale
			and adhere to particular price inner record handling logic.
			""")
		.type(nullableRef(PriceDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.from(PriceDescriptor.THIS)
		.name("PriceForSale")
		.build();
}
