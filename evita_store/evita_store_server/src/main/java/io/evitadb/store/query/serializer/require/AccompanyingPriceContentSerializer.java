/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.query.require.AccompanyingPriceContent;

import java.util.Optional;

/**
 * This {@link Serializer} implementation reads/writes {@link AccompanyingPriceContent} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class AccompanyingPriceContentSerializer extends Serializer<AccompanyingPriceContent> {

	@Override
	public void write(Kryo kryo, Output output, AccompanyingPriceContent object) {
		final Optional<String> accompanyingPriceName = object.getAccompanyingPriceName();
		output.writeBoolean(accompanyingPriceName.isPresent());
		accompanyingPriceName.ifPresent(output::writeString);
		final String[] priceLists = object.getPriceLists();
		output.writeVarInt(priceLists.length, true);
		for (String priceList : priceLists) {
			output.writeString(priceList);
		}
	}

	@Override
	public AccompanyingPriceContent read(Kryo kryo, Input input, Class<? extends AccompanyingPriceContent> type) {
		final String accompanyingPriceName = input.readBoolean() ?
			input.readString() : null;
		final int priceListCount = input.readVarInt(true);
		final String[] priceLists = new String[priceListCount];
		for (int i = 0; i < priceListCount; i++) {
			priceLists[i] = input.readString();
		}
		return accompanyingPriceName == null ?
			new AccompanyingPriceContent() : new AccompanyingPriceContent(accompanyingPriceName, priceLists);
	}

}