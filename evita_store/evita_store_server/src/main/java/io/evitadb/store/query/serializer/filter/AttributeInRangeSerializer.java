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

package io.evitadb.store.query.serializer.filter;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.filter.AttributeInRange;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * This {@link Serializer} implementation reads/writes {@link AttributeInRange} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class AttributeInRangeSerializer extends Serializer<AttributeInRange> {

	@Override
	public void write(Kryo kryo, Output output, AttributeInRange object) {
		output.writeString(object.getAttributeName());
		final Serializable unknownArgument = object.getUnknownArgument();
		if (unknownArgument == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			kryo.writeClassAndObject(output, unknownArgument);
		}
	}

	@Override
	public AttributeInRange read(Kryo kryo, Input input, Class<? extends AttributeInRange> type) {
		final String attributeName = input.readString();
		if (input.readBoolean()) {
			final Serializable theValue = (Serializable) kryo.readClassAndObject(input);
			if (theValue instanceof Number) {
				return new AttributeInRange(attributeName, (Number) theValue);
			} else if (theValue instanceof OffsetDateTime) {
				return new AttributeInRange(attributeName, (OffsetDateTime) theValue);
			} else {
				throw new GenericEvitaInternalError("Unsupported filter value: " + theValue);
			}
		} else {
			return new AttributeInRange(attributeName);
		}
	}

}
