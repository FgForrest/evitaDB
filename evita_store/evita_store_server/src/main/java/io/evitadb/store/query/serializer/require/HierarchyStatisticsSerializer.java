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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.HierarchyStatistics;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * This {@link Serializer} implementation reads/writes {@link HierarchyStatistics} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HierarchyStatisticsSerializer extends Serializer<HierarchyStatistics> {

	@Override
	public void write(Kryo kryo, Output output, HierarchyStatistics object) {
		kryo.writeObject(output, object.getStatisticsBase());
		final StatisticsType[] statisticsType = Arrays.stream(object.getArguments())
				.filter(StatisticsType.class::isInstance)
				.map(StatisticsType.class::cast)
				.toArray(StatisticsType[]::new);
		output.writeVarInt(statisticsType.length, true);
		for (StatisticsType type : statisticsType) {
			kryo.writeObject(output, type);
		}
	}

	@Override
	public HierarchyStatistics read(Kryo kryo, Input input, Class<? extends HierarchyStatistics> type) {
		final StatisticsBase statisticsBase = kryo.readObject(input, StatisticsBase.class);
		final int typeCount = input.readVarInt(true);
		final StatisticsType[] types = new StatisticsType[typeCount];
		for (int i = 0; i < typeCount; i++) {
			types[i] = kryo.readObject(input, StatisticsType.class);
		}
		return new HierarchyStatistics(statisticsBase, types);
	}

}
