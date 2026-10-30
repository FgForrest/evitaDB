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

package io.evitadb.store.query.serializer.filter;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.api.query.filter.TextMatchesSpecificationFilterConstraint;

/**
 * This {@link Serializer} implementation reads/writes {@link TextMatches} from/to binary format. The children are
 * written with their class, because `textMatches` accepts any {@link TextMatchesSpecificationFilterConstraint}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TextMatchesSerializer extends Serializer<TextMatches> {

	@Override
	public void write(Kryo kryo, Output output, TextMatches textMatches) {
		output.writeString(textMatches.getText());
		final TextMatchesSpecificationFilterConstraint[] specifications = textMatches.getSpecifications();
		output.writeVarInt(specifications.length, true);
		for (final TextMatchesSpecificationFilterConstraint specification : specifications) {
			kryo.writeClassAndObject(output, specification);
		}
	}

	@Override
	public TextMatches read(Kryo kryo, Input input, Class<? extends TextMatches> type) {
		final String text = input.readString();
		final int specificationCount = input.readVarInt(true);
		final TextMatchesSpecificationFilterConstraint[] specifications =
			new TextMatchesSpecificationFilterConstraint[specificationCount];
		for (int i = 0; i < specificationCount; i++) {
			specifications[i] = (TextMatchesSpecificationFilterConstraint) kryo.readClassAndObject(input);
		}
		return new TextMatches(text, specifications);
	}

}
