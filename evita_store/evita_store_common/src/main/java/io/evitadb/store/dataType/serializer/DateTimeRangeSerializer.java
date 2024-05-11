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

package io.evitadb.store.dataType.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;

import java.time.OffsetDateTime;

/**
 * This {@link Serializer} implementation reads/writes {@link DateTimeRange} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class DateTimeRangeSerializer extends Serializer<DateTimeRange> {

	@Override
	public void write(Kryo kryo, Output output, DateTimeRange dateTimeRange) {
		final OffsetDateTime from = dateTimeRange.getPreciseFrom();
		if (from == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			OffsetDateTimeSerializer.write(output, from);
		}
		final OffsetDateTime to = dateTimeRange.getPreciseTo();
		if (to == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			OffsetDateTimeSerializer.write(output, to);
		}
	}

	@Override
	public DateTimeRange read(Kryo kryo, Input input, Class<? extends DateTimeRange> type) {
		final OffsetDateTime from = input.readBoolean() ? OffsetDateTimeSerializer.read(input) : null;
		final OffsetDateTime to = input.readBoolean() ? OffsetDateTimeSerializer.read(input) : null;
		if (from != null && to != null) {
			return DateTimeRange.between(from, to);
		} else if (from != null) {
			return DateTimeRange.since(from);
		} else if (to != null) {
			return DateTimeRange.until(to);
		} else {
			throw new GenericEvitaInternalError("The range have both bounds null. It should have not been created in the first place!");
		}
	}

}
