/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model.header;

import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor for headers of fields that change their behaviour based on defined {@link Scope}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public interface ScopeAwareEndpointHeaderDescriptor {

	PropertyDescriptor SCOPE = PropertyDescriptor.builder()
		.name("scope")
		.description("""
			This `scope` parameter can be used to control the scope of the entity search. It accepts one or more scopes
			 where the entity should be searched. The following scopes are supported:
			
			- LIVE: entities that are currently active and reside in the live data set indexes
			- ARCHIVED: entities that are no longer active and reside in the archive indexes (with limited accessibility)
			
			By default, entities are searched only in the LIVE scope. The ARCHIVED scope is being searched only when explicitly
			requested. Archived entities are considered to be "soft-deleted", can be still queried if necessary, and can be
			restored back to the LIVE scope.
			""")
		.type(nullable(Scope[].class))
		.build();
}
