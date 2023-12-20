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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.service.SerializationService;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * This class takes care of (de)serialization WAL from and to binary format.
 * Currently, simple implementation that keeps single kryo instance with all necessary classes registered. Implementation
 * is not thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class WalSerializationService implements SerializationService<Mutation> {
	private final Kryo kryo;

	public WalSerializationService() {
		this.kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
	}

	@Override
	public void serialize(@Nonnull Mutation mutation, @Nonnull Output output) {
		kryo.writeClassAndObject(output, mutation);
	}

	@Override
	public Mutation deserialize(@Nonnull Input input) {
		return (Mutation) kryo.readClassAndObject(input);
	}

}
