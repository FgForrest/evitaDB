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
import com.esotericsoftware.kryo.serializers.ImmutableSerializer;

import java.time.LocalDate;

/**
 * This {@link Serializer} implementation reads/writes {@link LocalDate} from/to binary format.
 * The serializer is here due to make {@link OffsetDateTimeSerializer} faster in deserialization - we expect that used
 * ZoneId will repeat massively thorough the data, and we want to cache previously deserialized ZoneIds to speed up
 * the overall deserialization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class LocalDateSerializer extends ImmutableSerializer<LocalDate> {
	public static final int RECORD_SIZE = 4 + 1 + 1;

	@Override
	public void write (Kryo kryo, Output out, LocalDate date) {
		write(out, date);
	}

	static void write (Output out, LocalDate date) {
		out.writeInt(date.getYear(), true);
		out.writeByte(date.getMonthValue());
		out.writeByte(date.getDayOfMonth());
	}

	@Override
	public LocalDate read (Kryo kryo, Input in, Class type) {
		return read(in);
	}

	static LocalDate read (Input in) {
		int year = in.readInt(true);
		int month = in.readByte();
		int dayOfMonth = in.readByte();
		return LocalDate.of(year, month, dayOfMonth);
	}

}
