/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.traffic.serializer;


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.traffic.TrafficRecordingSerializationKryoConfigurer;
import io.evitadb.store.wal.WalKryoConfigurer;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Abstract test class for container serializers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
abstract class AbstractContainerSerializerTest {
	protected final Kryo kryo = KryoFactory.createKryo(
		WalKryoConfigurer.INSTANCE
			.andThen(QuerySerializationKryoConfigurer.INSTANCE)
			.andThen(TrafficRecordingSerializationKryoConfigurer.INSTANCE)
	);

	protected void assertSerializationRound(@Nonnull Object object) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.kryo.writeObject(output, object);
		}
		try (final Input input = new Input(os.toByteArray())) {
			final Object deserialized = this.kryo.readObject(input, object.getClass());
			assertEquals(object, deserialized);
		}
	}
}
