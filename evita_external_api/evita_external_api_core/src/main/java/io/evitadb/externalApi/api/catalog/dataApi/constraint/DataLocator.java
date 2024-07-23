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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import io.evitadb.api.query.descriptor.ConstraintDomain;

import javax.annotation.Nonnull;

/**
 * Specifies how to get original data to generate constraints from. Currently, used to find correct attribute and reference
 * schemas in any position in query tree. It is basically an "implementation" of the {@link ConstraintDomain} to actually locate data
 * in that domain.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface DataLocator {
	/**
	 * Domain in which we need to build constraints, only constraint that are supported in this domain should be considered.
	 */
	@Nonnull
	ConstraintDomain targetDomain();

	@Nonnull
	String entityType();
}
