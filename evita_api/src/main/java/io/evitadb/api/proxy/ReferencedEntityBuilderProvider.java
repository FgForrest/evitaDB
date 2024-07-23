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

package io.evitadb.api.proxy;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.proxy.SealedEntityProxy.EntityBuilderWithCallback;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.stream.Stream;

/**
 * This interface is implemented by {@link SealedEntityProxy} and {@link SealedEntityReferenceProxy} that both provide
 * access to external entity builders.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ReferencedEntityBuilderProvider {

	/**
	 * Returns stream of entity mutations that related not to internal wrapped entity but to
	 * entities that are referenced from this internal entity. This stream is used in method
	 * {@link EvitaSessionContract#upsertEntityDeeply(Serializable)} to store all changes that were made to the object
	 * tree originating from this proxy.
	 *
	 * @return stream of all mutations that were made to the object tree originating from this proxy, empty stream if
	 * no mutations were made or mutations were made only to the internally wrapped entity
	 */
	@Nonnull
	Stream<EntityBuilderWithCallback> getReferencedEntityBuildersWithCallback();

}
