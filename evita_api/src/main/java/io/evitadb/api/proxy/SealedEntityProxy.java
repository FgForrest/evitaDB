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

package io.evitadb.api.proxy;

import io.evitadb.api.requestResponse.data.SealedEntity;

import javax.annotation.Nonnull;

/**
 * This interface is implemented by all proxy types that wrap a sealed entity and provide access to an instance of
 * the {@link SealedEntity} trapped in the proxy state object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SealedEntityProxy extends EvitaProxy {

	/**
	 * Returns the underlying sealed entity that is wrapped into a requested proxy type.
	 * @return the underlying sealed entity
	 */
	@Nonnull
	SealedEntity getSealedEntity();

}
