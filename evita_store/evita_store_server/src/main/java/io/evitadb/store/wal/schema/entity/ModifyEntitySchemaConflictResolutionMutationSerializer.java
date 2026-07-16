/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.wal.schema.entity;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaConflictResolutionMutation;

import java.util.EnumSet;

/**
 * Serializer for {@link ModifyEntitySchemaConflictResolutionMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ModifyEntitySchemaConflictResolutionMutationSerializer
	extends Serializer<ModifyEntitySchemaConflictResolutionMutation> {

	@Override
	public void write(Kryo kryo, Output output, ModifyEntitySchemaConflictResolutionMutation mutation) {
		final ConflictResolution conflictResolution = mutation.getConflictResolution();
		if (conflictResolution == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			kryo.writeObject(output, conflictResolution.policy());
			final EnumSet<GranularConflictPolicy> granularity = conflictResolution.granularity();
			output.writeVarInt(granularity.size(), true);
			for (final GranularConflictPolicy granularEntry : granularity) {
				kryo.writeObject(output, granularEntry);
			}
		}
	}

	@Override
	public ModifyEntitySchemaConflictResolutionMutation read(
		Kryo kryo, Input input, Class<? extends ModifyEntitySchemaConflictResolutionMutation> type
	) {
		final ConflictResolution conflictResolution;
		if (input.readBoolean()) {
			final ConflictPolicy policy = kryo.readObject(input, ConflictPolicy.class);
			final int granularityCount = input.readVarInt(true);
			final EnumSet<GranularConflictPolicy> granularity = EnumSet.noneOf(GranularConflictPolicy.class);
			for (int i = 0; i < granularityCount; i++) {
				granularity.add(kryo.readObject(input, GranularConflictPolicy.class));
			}
			conflictResolution = new ConflictResolution(policy, granularity);
		} else {
			conflictResolution = null;
		}
		return new ModifyEntitySchemaConflictResolutionMutation(conflictResolution);
	}

}
