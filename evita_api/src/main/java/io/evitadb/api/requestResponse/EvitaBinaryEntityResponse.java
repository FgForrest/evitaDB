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

package io.evitadb.api.requestResponse;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.dataType.DataChunk;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Response carries entities in a binary format and is part of the PRIVATE API that is used by Java driver. The client
 * that receives the binary data must know how to deserialize them using Kryo deserializers which are internal to
 * the evitaDB (and even if they had been public they could not have been used because Kryo is not ported to other
 * platforms than Java). The response is triggered by {@link io.evitadb.api.query.require.BinaryForm} requirement.
 *
 * Passing binary format between server and the client is beneficial because we avoid several layers of (de)serialization
 * at the price of slight network waste. The general chain looks like this:
 *
 * 1. query
 * 2. fetch binaries from disk
 * 3. deserialize to Java objects on heap
 * 4. serialize Java objects to gRPC DTOs
 * 5. serialize gRPC DTOs to binary format
 * 6. deserialize gRPC from binary format to DTOs
 * 7. convert DTOs to Java objects on heap
 *
 * The chains where we pass directly the binary content from the disk looks like this:
 *
 * 1. query
 * 2. fetch binaries from disk
 * 3. serialize gRPC DTOs to binary format
 * 4. deserialize gRPC from binary format to DTOs
 * 5. convert DTOs to Java objects on heap using Kryo deserializers
 *
 * The network might be wasted because the query could ask only for a few attributes / references but due to
 * a storage format more information is fetched from the disk and thus sent over the network.
 *
 * TOBEDONE JNO - create the performance test that proves that binary format over gRPC will pay off
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class EvitaBinaryEntityResponse extends EvitaResponse<BinaryEntity> {
	@Getter private final int[] primaryKeys;

	public EvitaBinaryEntityResponse(
		@Nonnull Query sourceQuery,
		@Nonnull DataChunk<BinaryEntity> recordPage,
		@Nonnull int[] primaryKeys
	) {
		super(sourceQuery, recordPage);
		this.primaryKeys = primaryKeys;
	}

	public EvitaBinaryEntityResponse(
		@Nonnull Query sourceQuery,
		@Nonnull DataChunk<BinaryEntity> recordPage,
		@Nonnull int[] primaryKeys,
		@Nonnull EvitaResponseExtraResult... extraResults
	) {
		super(sourceQuery, recordPage, extraResults);
		this.primaryKeys = primaryKeys;
	}

}
