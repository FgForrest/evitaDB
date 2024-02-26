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

import java.time.LocalTime;

/**
 * This {@link Serializer} implementation reads/writes {@link LocalTime} from/to binary format.
 * The serializer is here due to make {@link OffsetDateTimeSerializer} faster in deserialization - we expect that used
 * ZoneId will repeat massively thorough the data, and we want to cache previously deserialized ZoneIds to speed up
 * the overall deserialization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class LocalTimeSerializer extends ImmutableSerializer<LocalTime> {
	public static final int RECORD_SIZE = 3 + 3 + 4;

	@Override
	public void write (Kryo kryo, Output out, LocalTime time) {
		write(out, time);
	}

	static void write (Output out, LocalTime time) {
		if (time.getNano() == 0) {
			if (time.getSecond() == 0) {
				if (time.getMinute() == 0) {
					out.writeByte(~time.getHour());
				} else {
					out.writeByte(time.getHour());
					out.writeByte(~time.getMinute());
				}
			} else {
				out.writeByte(time.getHour());
				out.writeByte(time.getMinute());
				out.writeByte(~time.getSecond());
			}
		} else {
			out.writeByte(time.getHour());
			out.writeByte(time.getMinute());
			out.writeByte(time.getSecond());
			out.writeInt(time.getNano(), true);
		}
	}

	@Override
	public LocalTime read (Kryo kryo, Input in, Class type) {
		return read(in);
	}

	static LocalTime read (Input in) {
		int hour = in.readByte();
		int minute = 0;
		int second = 0;
		int nano = 0;
		if (hour < 0) {
			hour = ~hour;
		} else {
			minute = in.readByte();
			if (minute < 0) {
				minute = ~minute;
			} else {
				second = in.readByte();
				if (second < 0) {
					second = ~second;
				} else {
					nano = in.readInt(true);
				}
			}
		}
		return LocalTime.of(hour, minute, second, nano);
	}

}
