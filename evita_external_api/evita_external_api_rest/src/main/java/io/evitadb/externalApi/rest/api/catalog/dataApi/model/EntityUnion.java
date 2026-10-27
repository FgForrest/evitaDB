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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.model.UnionDescriptor;

/**
 * Descriptors of unions of {@link EntityContract}s of all collections present in evitaDB for schema-based external
 * APIs. The unions are the response types of the endpoints fetching an entity of unknown collection by a globally
 * unique attribute, therefore they are registered only when the catalog has at least one collection and a globally
 * unique attribute.
 *
 * The unions are deliberately named apart from the common base object `Entity`
 * ({@link EntityDescriptor#THIS_CLASSIFIER}) that every entity object extends via `allOf`. Sharing the name would make
 * every entity object extend the union containing it.
 *
 * The descriptors carry no member types: the builder adds the entity object of every collection when it registers
 * the union.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface EntityUnion {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("EntityUnion")
		.description("""
			Gathers all specific entity objects into one. Returned by endpoints fetching entities of unknown collection.
			""")
		.discriminator(EntityDescriptor.TYPE)
		.build();

	UnionDescriptor THIS_LOCALIZED = UnionDescriptor.builder()
		.name("LocalizedEntityUnion")
		.description("""
			Gathers all specific localized entity objects into one. Returned by localized endpoints fetching entities
			of unknown collection.
			""")
		.discriminator(EntityDescriptor.TYPE)
		.build();
}
