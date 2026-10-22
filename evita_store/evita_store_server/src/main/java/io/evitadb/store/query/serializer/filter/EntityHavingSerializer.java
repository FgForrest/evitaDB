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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes {@link EntityHaving} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class EntityHavingSerializer extends Serializer<EntityHaving> {

	/**
	 * Writes exactly one filter to preserve the recording format, combining multiple children with AND.
	 * Empty scope filters cannot be recorded.
	 */
	@Override
	public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull EntityHaving object) {
		final FilterConstraint[] children = object.getChildren();
		Assert.isTrue(children.length > 0, "Cannot serialize an empty EntityHaving constraint.");
		kryo.writeClassAndObject(output, children.length == 1 ? children[0] : new And(children));
	}

	@Override
	public EntityHaving read(Kryo kryo, Input input, Class<? extends EntityHaving> type) {
		return new EntityHaving((FilterConstraint) kryo.readClassAndObject(input));
	}

}
