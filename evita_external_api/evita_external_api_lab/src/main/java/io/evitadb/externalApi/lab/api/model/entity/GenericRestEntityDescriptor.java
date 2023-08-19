/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.lab.api.model.entity;

import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.GenericObject;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.RestEntityDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Extension of {@link RestEntityDescriptor} for building generic entities without known entity schema or catalog .
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface GenericRestEntityDescriptor extends RestEntityDescriptor {

	PropertyDescriptor ATTRIBUTES = PropertyDescriptor.extend(AttributesProviderDescriptor.ATTRIBUTES)
		.type(nullable(GenericObject.class))
		.build();

	PropertyDescriptor ASSOCIATED_DATA = PropertyDescriptor.extend(EntityDescriptor.ASSOCIATED_DATA)
		.type(nullable(GenericObject.class))
		.build();

	PropertyDescriptor REFERENCES = PropertyDescriptor.builder()
		.name("references")
		.description("""
			The references represent relations to other evitaDB
			entities or external entities in different systems.
			""")
		.type(nullable(GenericObject.class))
		.build();
}
