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


package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.fulltext.ReferenceFulltextValueIndex.ChildKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextReferenceValueStreamKey;

import javax.annotation.Nonnull;

import java.util.Locale;

/** Serializes a generation/child stream identity with its closed child kind written by name. */
public class FulltextReferenceValueStreamKeySerializer extends Serializer<FulltextReferenceValueStreamKey> {

	@Override
	public void write(@Nonnull Kryo kryo, @Nonnull Output output,
		@Nonnull FulltextReferenceValueStreamKey streamKey) {
		output.writeVarInt(streamKey.entityIndexPrimaryKey(), false);
		kryo.writeObject(output, streamKey.locale());
		output.writeVarInt(streamKey.fieldId(), true);
		output.writeString(streamKey.childKind().name());
	}

	@Nonnull
	@Override
	public FulltextReferenceValueStreamKey read(@Nonnull Kryo kryo, @Nonnull Input input,
		@Nonnull Class<? extends FulltextReferenceValueStreamKey> type) {
		final int entityIndexPrimaryKey = input.readVarInt(false);
		final Locale locale = kryo.readObject(input, Locale.class);
		final int fieldId = input.readVarInt(true);
		final ChildKind childKind = ChildKind.valueOf(input.readString());
		return new FulltextReferenceValueStreamKey(entityIndexPrimaryKey, locale, fieldId, childKind);
	}

}
