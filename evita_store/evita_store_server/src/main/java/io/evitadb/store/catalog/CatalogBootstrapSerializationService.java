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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.CatalogState;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.service.SerializationService;
import io.evitadb.store.spi.model.CatalogBootstrap;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * This class takes care of (de)serialization of dictionary contents from and to binary format.
 * Currently simple implementation that keeps single kryo instance with all necessary classes registered. Implementation
 * is not thread safe.
 *
 * TOBEDONE JNO - we should keep catalog bootstrap in more resilient form - i.e. keep multiple records inside one file and vacuum it from time to time
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@RequiredArgsConstructor
public class CatalogBootstrapSerializationService implements SerializationService<CatalogBootstrap> {
	private final Consumer<Kryo> kryoConfigurer;

	@Override
	public void serialize(@Nonnull CatalogBootstrap header, @Nonnull Output output) {
		final Kryo kryo = KryoFactory.createKryo();
		ofNullable(kryoConfigurer).ifPresent(it -> it.accept(kryo));

		kryo.writeObject(output, header.getCatalogHeader());
		kryo.writeObject(output, header.getCatalogState());
		output.writeVarLong(header.getVersionId(), true);

		final Map<String, EntityCollectionHeader> collectionHeaders = header.getCollectionHeaders();
		output.writeVarInt(collectionHeaders.size(), true);
		for (EntityCollectionHeader catalogEntityHeader : collectionHeaders.values()) {
			kryo.writeObject(output, catalogEntityHeader);
		}
	}

	@Override
	public CatalogBootstrap deserialize(@Nonnull Input input) {
		final Kryo kryo = KryoFactory.createKryo(kryoConfigurer);

		final CatalogHeader catalogHeader = kryo.readObject(input, CatalogHeader.class);
		final CatalogState catalogState = kryo.readObject(input, CatalogState.class);
		final long lastTransactionId = input.readVarLong(true);

		final int entityTypeCount = input.readVarInt(true);
		final List<EntityCollectionHeader> entityTypeHeaders = new ArrayList<>(entityTypeCount);
		for (int i = 0; i < entityTypeCount; i++) {
			entityTypeHeaders.add(
				kryo.readObject(input, EntityCollectionHeader.class)
			);
		}
		return new CatalogBootstrap(catalogState, lastTransactionId, catalogHeader, entityTypeHeaders);
	}

}
