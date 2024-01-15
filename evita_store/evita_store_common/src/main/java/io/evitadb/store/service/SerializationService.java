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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.service;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Generic contract for all serialization services. We want to avoid fragmentation and keep (de)serialization logic
 * unified for all data types.
 *
 * TODO JNO - toto je úplně špatný koncept, který mě zavedl na scestí - tohle rozhraní ideálně úplně zrušit a i
 * catalog header ukládat přes StorageRecord
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface SerializationService<T> {

	/**
	 * Method serializes the object to the output stream. Output stream is closed in the process of serialization.
	 * Note: this implementation is suboptimal and should not be used in production code. It's much better to use
	 * instances borrowed from {@link com.esotericsoftware.kryo.util.Pool} and returned it back. Hence the method
	 * {@link #serialize(Object, Output)} is better suited for this case.
	 */
	default void serialize(@Nonnull T theObject, @Nonnull OutputStream os) {
		try (final ByteBufferOutput output = new ByteBufferOutput(os)) {
			serialize(theObject, output);
		}
	}

	/**
	 * Method serializes the object to the output stream. Output is not closed in the method, caller is responsible
	 * for closing it.
	 */
	void serialize(@Nonnull T theObject, @Nonnull Output output);

	/**
	 * Method deserializes the object from the input stream. Input stream is closed in the process of deserialization.
	 * Note: It's much better to use instances borrowed from {@link com.esotericsoftware.kryo.util.Pool} and returned
	 * it back. Hence the method {@link #deserialize(Input)} is better suited for this case.
	 */
	default T deserialize(@Nonnull InputStream is) {
		try (final ByteBufferInput input = new ByteBufferInput(is)) {
			return deserialize(input);
		}
	}

	/**
	 * Method deserializes the object from the input stream. Input is not closed in the method, caller is responsible
	 * for closing it.
	 */
	T deserialize(@Nonnull Input input);

}
