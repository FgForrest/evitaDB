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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Interface is used to create proxy instances of sealed entities when client code calls query method on
 * {@link EvitaSessionContract} interface providing their custom class/contract as requested type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ProxyFactory {

	/**
	 * Creates proxy instance of sealed entity that implements `expectedType` contract. Entity proxy respects
	 * request context used in the query that fetched {@link SealedEntity}.
	 *
	 * @param expectedType contract that the proxy should implement
	 * @param entity sealed entity to create proxy for
	 * @return proxy instance of sealed entity
	 * @param <T> type of contract that the proxy should implement
	 * @throws EntityClassInvalidException if the proxy contract is not valid
	 */
	@Nonnull
	<T> T createEntityProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) throws EntityClassInvalidException;

}
