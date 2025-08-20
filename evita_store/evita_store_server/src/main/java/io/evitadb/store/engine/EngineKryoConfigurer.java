/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.engine;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.engine.serializer.EngineStateSerializer;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.utils.Assert;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link EngineState}.
 *
 * Kryo is used for efficient binary serialization and deserialization of objects. This configurer
 * registers custom serializers for engine-specific classes to ensure proper handling of their serialization.
 */
public class EngineKryoConfigurer implements Consumer<Kryo> {
	/**
	 * Singleton instance of this configurer that can be reused across the application.
	 */
	public static final EngineKryoConfigurer INSTANCE = new EngineKryoConfigurer();

	/**
	 * Base index for engine-related serializers in the Kryo registration.
	 * This ensures that engine serializers have a distinct range of indices.
	 */
	private static final int ENGINE_BASE = 7000;

	@Override
	public void accept(Kryo kryo) {
		int index = ENGINE_BASE;

		// Register EngineState serializer with a unique index
		kryo.register(EngineState.class, new SerialVersionBasedSerializer<>(new EngineStateSerializer(), EngineState.class), index++);

		// Ensure we haven't exceeded the allocated index range
		Assert.isPremiseValid(index < 7100, "Index count overflow.");
	}

}
