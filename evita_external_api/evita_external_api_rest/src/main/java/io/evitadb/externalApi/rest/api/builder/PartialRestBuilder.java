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

package io.evitadb.externalApi.rest.api.builder;

import io.evitadb.externalApi.rest.api.Rest;

import javax.annotation.Nonnull;

/**
 * Builds only part of {@link Rest}. Actual building of REST API should be done by some wrapping builder.
 *
 * @author Lukáš Hornych, 2023
 */
public abstract class PartialRestBuilder<C extends RestBuildingContext> extends RestBuilder<C> {

	protected PartialRestBuilder(@Nonnull C buildingContext) {
		super(buildingContext);
	}

	/**
	 * Builds objects and endpoints and add them to building context.
	 */
	public abstract void build();
}
