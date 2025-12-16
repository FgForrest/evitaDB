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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.model.ObjectDescriptor;

/**
 * Wraps {@link GraphQLExtraResultsDescriptor extra result fields} into scoped container.
 *
 * Note: an object from this descriptor must be built dynamically based on available extra result fields from
 * {@link GraphQLExtraResultsDescriptor#THIS} object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface InScopeDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*ExtraResultsInScope")
		.description("Encloses set of extra results that should be computed only for entities in specific scope.")
		.build();
}
