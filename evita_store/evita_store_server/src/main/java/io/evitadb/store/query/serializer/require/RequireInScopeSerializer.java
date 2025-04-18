
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
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.RequireInScope;
import io.evitadb.dataType.Scope;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link RequireInScope} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class RequireInScopeSerializer extends Serializer<RequireInScope> {

	@Override
	public void write(Kryo kryo, Output output, RequireInScope inScope) {
		final Scope scope = inScope.getScope();
		kryo.writeObject(output, scope);
		final RequireConstraint[] requirements = inScope.getRequire();
		output.writeVarInt(requirements.length, true);
		for (RequireConstraint require : requirements) {
			kryo.writeClassAndObject(output, require);
		}
	}

	@Override
	public RequireInScope read(Kryo kryo, Input input, Class<? extends RequireInScope> type) {
		final Scope scope = kryo.readObject(input, Scope.class);
		final int requireLength = input.readVarInt(true);
		final RequireConstraint[] require = new RequireConstraint[requireLength];
		for (int i = 0; i < requireLength; i++) {
			require[i] = (RequireConstraint) kryo.readClassAndObject(input);
		}
		return new RequireInScope(scope, require);
	}

}
