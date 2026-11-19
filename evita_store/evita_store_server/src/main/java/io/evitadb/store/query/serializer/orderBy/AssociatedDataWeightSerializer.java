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
import io.evitadb.api.query.order.AssociatedDataWeight;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;

import java.math.BigDecimal;

/**
 * This {@link Serializer} implementation reads/writes {@link AssociatedDataWeight} from/to binary format: the
 * associated data name followed by the BigDecimal weight.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class AssociatedDataWeightSerializer extends Serializer<AssociatedDataWeight> {
	/**
	 * Writes the named place and its effective query weight.
	 *
	 * @param kryo the serialization context used for nested weights
	 * @param output the target the constraint is written to
	 * @param constraint the constraint to write
	 */
	@Override
	public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull AssociatedDataWeight constraint) {
		output.writeString(constraint.getAssociatedDataName());
		kryo.writeObject(output, constraint.getWeight());
	}

	/**
	 * Restores the weight through its validating constructor.
	 *
	 * @param kryo the serialization context used for nested weights
	 * @param input the source the constraint is read from
	 * @param type the requested constraint class, unused
	 * @return the restored constraint
	 * @throws EvitaInvalidUsageException when the stored name is null or blank or the weight is null
	 */
	@Nonnull
	@Override
	public AssociatedDataWeight read(
		@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends AssociatedDataWeight> type
	) {
		return new AssociatedDataWeight(input.readString(), kryo.readObject(input, BigDecimal.class));
	}
}
