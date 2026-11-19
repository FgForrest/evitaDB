/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api.system.model;

import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.UUID;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor for header arguments of {@link SystemRootDescriptor#ON_ENGINE_MUTATION_PROGRESS_CHANGE} subscription.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface OnEngineMutationProgressChangeSubscriptionHeaderDescriptor {

	PropertyDescriptor PROGRESS_ID = PropertyDescriptor.builder()
		.name("progressId")
		.description("""
			Id of the operation to observe, as returned in the handle of a `...WithProgress` mutation.
			""")
		.type(nonNull(UUID.class))
		.build();
}
