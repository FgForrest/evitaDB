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

package io.evitadb.store.wal.data.attribute;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.dataType.NumberRange;

import java.util.Locale;

/**
 * Serializer for {@link ApplyDeltaAttributeMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("rawtypes")
public class ApplyDeltaAttributeMutationSerializer extends Serializer<ApplyDeltaAttributeMutation> {

	@Override
	public void write(Kryo kryo, Output output, ApplyDeltaAttributeMutation mutation) {
		final AttributeKey attributeKey = mutation.getAttributeKey();
		output.writeString(attributeKey.attributeName());
		kryo.writeObjectOrNull(output, attributeKey.locale(), Locale.class);
		kryo.writeClassAndObject(output, mutation.getDelta());
		kryo.writeClassAndObject(output, mutation.getRequiredRangeAfterApplication());
	}

	@Override
	public ApplyDeltaAttributeMutation<?> read(Kryo kryo, Input input, Class<? extends ApplyDeltaAttributeMutation> type) {
		//noinspection rawtypes
		return new ApplyDeltaAttributeMutation(
			new AttributeKey(
				input.readString(),
				kryo.readObjectOrNull(input, Locale.class)
			),
			(Number) kryo.readClassAndObject(input),
			(NumberRange<?>) kryo.readClassAndObject(input)
		);
	}

}
