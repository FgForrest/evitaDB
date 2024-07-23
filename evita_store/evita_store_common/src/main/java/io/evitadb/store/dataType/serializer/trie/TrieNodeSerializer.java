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

package io.evitadb.store.dataType.serializer.trie;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.trie.TrieNode;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.lang.reflect.Array;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * This is serializer that is optimized to store tries in their original tree structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class TrieNodeSerializer<T extends Serializable> extends Serializer<TrieNode<T>> {
	private static final ThreadLocal<Class<?>> ARRAY_TYPE = new ThreadLocal<>();

	public static void deserializeWithArrayType(Class<? extends Serializable> arrayType, Runnable runnable) {
		try {
			isPremiseValid(ARRAY_TYPE.get() == null, "Should not ever happen!");
			ARRAY_TYPE.set(arrayType);
			runnable.run();
		} finally {
			ARRAY_TYPE.remove();
		}
	}

	@Override
	public void write(Kryo kryo, Output output, TrieNode<T> trieNode) {
		final int[] charIndex = trieNode.getCharIndex();
		output.writeVarInt(charIndex.length, true);
		output.writeInts(charIndex, 0, charIndex.length);

		final String[] edgeLabels = trieNode.getEdgeLabels();
		output.writeVarInt(edgeLabels.length, true);
		for (String sb : edgeLabels) {
			output.writeString(sb);
		}
		final T[] values = trieNode.getValues();
		output.writeVarInt(values.length, true);
		for (T value : values) {
			kryo.writeClassAndObject(output, value);
		}
		final TrieNode<T>[] children = trieNode.getChildren();
		output.writeVarInt(children.length, true);
		for (TrieNode<T> child : children) {
			kryo.writeObject(output, child);
		}
	}

	@Override
	public TrieNode<T> read(Kryo kryo, Input input, Class<? extends TrieNode<T>> type) {
		final int charLength = input.readVarInt(true);
		final int[] chars = input.readInts(charLength);
		final int edgeLabelsLength = input.readVarInt(true);
		final String[] edgeLabels = new String[edgeLabelsLength];
		for(int i = 0; i < edgeLabelsLength; i++) {
			edgeLabels[i] = input.readString();
		}
		final int valuesLength = input.readVarInt(true);
		@SuppressWarnings("unchecked")
		final T[] values = (T[]) Array.newInstance(ARRAY_TYPE.get(), valuesLength);
		for(int i = 0; i < valuesLength; i++) {
			//noinspection unchecked
			values[i] = (T) kryo.readClassAndObject(input);
		}
		final int childrenLength = input.readVarInt(true);
		@SuppressWarnings("unchecked")
		final TrieNode<T>[] children = new TrieNode[childrenLength];
		for(int i = 0; i < childrenLength; i++) {
			//noinspection unchecked
			children[i] = kryo.readObject(input, TrieNode.class);
		}
		return TrieNode._internalBuild(
			chars, children, edgeLabels, values
		);
	}

}
