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

package io.evitadb.store.query.serializer.orderBy;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.EntityWeight;
import io.evitadb.api.query.order.RelevanceTextTargetSpecificationOrderConstraint;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes {@link EntityWeight} from/to binary format: the child count,
 * then each child weight as class and object, in the order the query states them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class EntityWeightSerializer extends Serializer<EntityWeight> {
	/**
	 * Writes all child weights, including duplicates for planning-time validation.
	 *
	 * @param kryo the serialization context used for nested weights
	 * @param output the target the constraint is written to
	 * @param constraint the constraint to write
	 */
	@Override
	public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull EntityWeight constraint) {
		final OrderConstraint[] weights = constraint.getChildren();
		output.writeVarInt(weights.length, true);
		for (final OrderConstraint weight : weights) {
			kryo.writeClassAndObject(output, weight);
		}
	}

	/**
	 * Restores the level boundary and ordered child weights.
	 *
	 * @param kryo the serialization context used for nested weights
	 * @param input the source the constraint is read from
	 * @param type the requested constraint class, unused
	 * @return the restored constraint
	 * @throws ClassCastException when a stored child is not a weight allowed at this level
	 */
	@Nonnull
	@Override
	public EntityWeight read(@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends EntityWeight> type) {
		final RelevanceTextTargetSpecificationOrderConstraint[] weights =
			new RelevanceTextTargetSpecificationOrderConstraint[input.readVarInt(true)];
		for (int i = 0; i < weights.length; i++) {
			weights[i] = (RelevanceTextTargetSpecificationOrderConstraint) kryo.readClassAndObject(input);
		}
		return new EntityWeight(weights);
	}
}
