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

package io.evitadb.store.dataType.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ImmutableSerializer;
import io.evitadb.dataType.DateTimeRange;

import javax.annotation.Nonnull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * This {@link Serializer} implementation reads/writes {@link DateTimeRange} from/to binary format.
 * The {@link OffsetDateTime} deserialization logic was taken out from the Kryo library so that we can replace the ZoneId
 * deserialization logic where we cache previously deserialized ZoneOffsets to avoid expensive lookups in Java itself.
 *
 * Unfortunately there is still one complex method used {@link OffsetDateTime#of(LocalDate, LocalTime, ZoneOffset)} that
 * performs recalculations on deserialize. I have no idea how this could be made faster.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class OffsetDateTimeSerializer extends ImmutableSerializer<OffsetDateTime> {

	static void write(Output out, OffsetDateTime obj) {
		LocalDateSerializer.write(out, obj.toLocalDate());
		LocalTimeSerializer.write(out, obj.toLocalTime());
		ZoneOffsetSerializer.write(out, obj.getOffset());
	}

	@Nonnull
	static OffsetDateTime read(Input in) {
		LocalDate date = LocalDateSerializer.read(in);
		LocalTime time = LocalTimeSerializer.read(in);
		ZoneOffset offset = ZoneOffsetSerializer.read(in);
		return OffsetDateTime.of(date, time, offset);
	}

	@Override
	public void write (Kryo kryo, Output out, OffsetDateTime obj) {
		write(out, obj);
	}

	@Override
	public OffsetDateTime read (Kryo kryo, Input in, Class type) {
		return read(in);
	}

}
