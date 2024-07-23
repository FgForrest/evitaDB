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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents {@link io.evitadb.api.requestResponse.data.ReferenceContract}.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ReferenceDescriptor extends AttributesProviderDescriptor {

	PropertyDescriptor REFERENCED_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("referencedPrimaryKey")
		.description("""
            Returns primary key of the referenced (internal or external) entity.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor REFERENCED_ENTITY = PropertyDescriptor.builder()
		.name("referencedEntity")
		.description("""
			Returns body of the referenced entity in case its fetching was requested via entity_fetch constraint.
			""")
		// type is expected to be a sealed entity
		.build();
	PropertyDescriptor GROUP_ENTITY = PropertyDescriptor.builder()
		.name("groupEntity")
		.description("""
			Returns body of the referenced entity in case its fetching was requested via entity_groupFetch constraint.
			""")
		// type is expected to be a sealed entity
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*Reference")
		.staticFields(List.of(REFERENCED_PRIMARY_KEY))
		.build();
}
