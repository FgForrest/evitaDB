/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Defines an interface with fields to access a {@link ReferenceDefinitionDescriptor named reference},
 * but it is not directly associated with a specific entity type like {@link EntityReferenceDescriptor}.
 * Instead, entity objects implement this interface if they match the reference definition.
 * This allows us to reuse this interface for multiple entity types with the same reference definition (based on data equality)
 * even though they are not explicitly associated with each other.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface WithNamedReferenceDescriptor {

	PropertyDescriptor REFERENCE = PropertyDescriptor.builder()
		.name("*")
		.description("References")
		// type is expected to be reference or list of reference definitions
		.build();
	PropertyDescriptor REFERENCE_PAGE = PropertyDescriptor.builder()
		.name("*Page")
		.description("Paginated list of references.")
		// type is expected to be paginated list of reference definitions
		.build();
	PropertyDescriptor REFERENCE_STRIP = PropertyDescriptor.builder()
		.name("*Strip")
		.description("Strip list of references.")
		// type is expected to be strip list of reference definitions
		.build();

	ObjectDescriptor THIS_INTERFACE = ObjectDescriptor.builder()
		.name("With*Reference")
		.description("Enriches entity with fields to reference of name %s and type %s with parameters defined by %s hash.")
		.build();
}
