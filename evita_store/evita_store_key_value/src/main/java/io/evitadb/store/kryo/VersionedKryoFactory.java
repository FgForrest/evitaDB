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

package io.evitadb.store.kryo;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.DefaultClassResolver;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.store.service.KryoFactory;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

import static io.evitadb.store.service.KryoFactory.initializeKryo;

/**
 * This class mimics {@link KryoFactory} but produces {@link VersionedKryo} instances
 * instead of pure {@link Kryo} ones.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class VersionedKryoFactory {

	/**
	 * Method creates default VersionedKryo instance with all default serializers registered. This instance of the VersionedKryo
	 * should be able to (de)serialize all {@link EvitaDataTypes#getSupportedDataTypes()} data types.
	 */
	public static VersionedKryo createKryo(long version) {
		return initializeKryo(
			new VersionedKryo(
				version, new DefaultClassResolver(), null
			)
		);
	}

	/**
	 * Method creates default VersionedKryo instance ({@link #createKryo(long)} and created instance hands to passed consumer
	 * implementation.
	 */
	public static VersionedKryo createKryo(long version, @Nonnull Consumer<Kryo> andThen) {
		final VersionedKryo kryo = createKryo(version);
		andThen.accept(kryo);
		return kryo;
	}

	private VersionedKryoFactory() {
	}

}
