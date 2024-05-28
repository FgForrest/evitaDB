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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.proxy;

import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * The WithEntityBuilder interface extends the WithEntityContract interface and defines methods
 * for accessing the EntityBuilder instance to capture mutations of the wrapped entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface WithEntityBuilder extends WithEntityContract {

	/**
	 * Returns the {@link EntityBuilder} instance that is used to capture mutations of the wrapped {@link #entity()}.
	 *
	 * @return existing or new {@link EntityBuilder} instance
	 */
	@Nonnull
	EntityBuilder entityBuilder();

	/**
	 * Returns the {@link EntityBuilder} instance that is used to capture mutations of the wrapped {@link #entity()}.
	 * If no builder has been created yet (no modification occurred), the optional is empty.
	 * @return the {@link EntityBuilder} instance or empty value if no mutations were performed
	 */
	@Nonnull
	Optional<EntityBuilder> entityBuilderIfPresent();

}
