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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity;

import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Descriptor for header parameters of {@link io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor#PARENTS}
 * field.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ParentsFieldHeaderDescriptor {

	PropertyDescriptor STOP_AT = PropertyDescriptor.builder()
		.name("stopAt")
		// TOBEDONE JNO: stopAt constraint docs
		.description("""
			Defines how many parents in hierarchy will be returned.
			""")
		// type is expected to be a `stopAt` constraint
		.build();
}
