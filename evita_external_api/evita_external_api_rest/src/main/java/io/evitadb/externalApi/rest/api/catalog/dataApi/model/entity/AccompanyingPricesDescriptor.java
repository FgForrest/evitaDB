/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.externalApi.api.model.ObjectDescriptor;

/**
 * Descriptor of an object containing named accompanying prices for price for sale.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface AccompanyingPricesDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("AccompanyingPrices")
		.description("""
            Holds named accompanying prices for computed price for sale.
			""")
		.build();
}
