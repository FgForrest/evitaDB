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

package io.evitadb.externalApi.rest.api.system.model;

import io.evitadb.api.CatalogState;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of request body of {@link SystemRootDescriptor#UPDATE_CATALOG}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface UpdateCatalogRequestDescriptor {

	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("""
			New name of selected catalog.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor OVERWRITE_TARGET = PropertyDescriptor.builder()
		.name("overwriteTarget")
		.description("""
			If `true`, existing catalog under passed new `name` will be overwritten with this one.
			""")
		.type(nullable(Boolean.class))
		.build();

	PropertyDescriptor CATALOG_STATE = PropertyDescriptor.builder()
		.name("catalogState")
		.description("""
			New state of selected catalog. Can be used only for switching from `WARMING_UP` to `ALIVE` state.
			""")
		.type(nonNull(CatalogState.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("UpdateCatalogRequest")
		.description("""
			Requests creation of new catalog.
			""")
		.staticFields(List.of(NAME, OVERWRITE_TARGET, CATALOG_STATE))
		.build();
}
