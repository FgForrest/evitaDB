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
import io.evitadb.api.query.filter.HierarchySpecificationFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * This {@link Serializer} implementation reads/writes {@link HierarchyWithin} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HierarchyWithinSerializer extends Serializer<HierarchyWithin> {

	@Override
	public void write(Kryo kryo, Output output, HierarchyWithin object) {
		final Optional<String> referenceName = object.getReferenceName();
		if (referenceName.isEmpty()) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			output.writeString(referenceName.get());
		}
		final FilterConstraint[] children = object.getChildren();
		output.writeVarInt(children.length, true);
		for (FilterConstraint child : children) {
			kryo.writeClassAndObject(output, child);
		}
	}

	@Override
	public HierarchyWithin read(Kryo kryo, Input input, Class<? extends HierarchyWithin> type) {
		final String referenceName;
		if (input.readBoolean()) {
			referenceName = input.readString();
		} else {
			referenceName = null;
		}
		final FilterConstraint[] children = new FilterConstraint[input.readVarInt(true)];
		for (int i = 0; i < children.length; i++) {
			children[i] = (FilterConstraint) kryo.readClassAndObject(input);
		}

		final FilterConstraint parentFilter = Arrays.stream(children)
			.filter(it -> !(it instanceof HierarchySpecificationFilterConstraint))
			.findFirst()
			.orElseThrow();
		final HierarchySpecificationFilterConstraint[] specs = Arrays.stream(children)
			.filter(HierarchySpecificationFilterConstraint.class::isInstance)
			.map(HierarchySpecificationFilterConstraint.class::cast)
			.toArray(HierarchySpecificationFilterConstraint[]::new);

		return referenceName == null ? new HierarchyWithin(parentFilter, specs) : new HierarchyWithin(referenceName, parentFilter, specs);
	}

}
