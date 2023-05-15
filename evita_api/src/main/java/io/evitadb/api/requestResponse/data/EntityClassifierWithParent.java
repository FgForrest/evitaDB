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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.structure.Entity;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Common ancestor for contracts that either directly represent {@link EntityContract} or reference to it and may
 * contain reference to parent entities. We don't use sealed interface here because there are multiple implementations
 * of those interfaces but only these two aforementioned extending interfaces could extend from this one.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface EntityClassifierWithParent extends EntityClassifier {

	/**
	 * Optional reference to {@link Entity#getParent()} of the referenced entity.
	 */
	@Nonnull
	Optional<EntityClassifierWithParent> getParentEntity();
}
