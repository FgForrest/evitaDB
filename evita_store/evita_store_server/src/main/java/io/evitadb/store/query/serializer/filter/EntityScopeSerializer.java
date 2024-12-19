
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
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.dataType.Scope;
import lombok.RequiredArgsConstructor;

import java.util.Set;

/**
 * This {@link Serializer} implementation reads/writes {@link EntityScope} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class EntityScopeSerializer extends Serializer<EntityScope> {

	@Override
	public void write(Kryo kryo, Output output, EntityScope entityScope) {
		final Set<Scope> scopes = entityScope.getScope();
		output.writeVarInt(scopes.size(), true);
		for (Scope theScope : scopes) {
			kryo.writeObject(output, theScope);
		}
	}

	@Override
	public EntityScope read(Kryo kryo, Input input, Class<? extends EntityScope> type) {
		final int scopeCount = input.readVarInt(true);
		final Scope[] scopes = new Scope[scopeCount];
		for (int i = 0; i < scopeCount; i++) {
			scopes[i] = kryo.readObject(input, Scope.class);
		}
		return new EntityScope(scopes);
	}

}
