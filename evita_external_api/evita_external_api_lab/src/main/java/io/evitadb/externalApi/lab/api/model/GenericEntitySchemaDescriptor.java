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

package io.evitadb.externalApi.lab.api.model;

import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

import java.util.List;

/**
 * Generic equivalent of {@link EntitySchemaDescriptor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface GenericEntitySchemaDescriptor extends EntitySchemaDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.extend(EntitySchemaDescriptor.THIS_SPECIFIC)
		.name("EntitySchema")
		.staticFields(List.of(
			VERSION,
			NAME,
			NAME_VARIANTS,
			DESCRIPTION,
			DEPRECATION_NOTICE,
			WITH_GENERATED_PRIMARY_KEY,
			WITH_HIERARCHY,
			WITH_PRICE,
			INDEXED_PRICE_PLACES,
			LOCALES,
			CURRENCIES,
			EVOLUTION_MODE
		))
		.build();
}
