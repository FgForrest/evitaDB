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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.dataType.ClassifierType;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.model.OnChangeHeaderDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Subscription header arguments of registering subscription for listening {@link io.evitadb.api.requestResponse.data.EntityContract}
 * changes.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface OnDataChangeHeaderDescriptor extends OnChangeHeaderDescriptor {

	PropertyDescriptor ENTITY_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("entityPrimaryKey")
		.description("""
			The primary key of the intercepted entity.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor CONTAINER_TYPE = PropertyDescriptor.builder()
		.name("containerType")
		.description("""
			The intercepted container type of the entity data.
			""")
		.type(nullable(ClassifierType[].class))
		.build();
}
