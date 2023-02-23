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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.memTable.model;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.memTable.MemTable;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.StoragePartRegistry;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * This enum contains all supported classes that can be stored in {@link MemTable}. This
 * enum is used for translating full Class to a small number to minimize memory overhead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class MemTableRecordTypeRegistry {
	private final Map<Class<? extends StoragePart>, Byte> typeToIdIndex = new HashMap<>(64);
	private final Map<Byte, Class<? extends StoragePart>> idToTypeIndex = new HashMap<>(64);

	public MemTableRecordTypeRegistry() {
		ServiceLoader.load(StoragePartRegistry.class)
			.stream()
			.map(Provider::get)
			.flatMap(it -> it.listStorageParts().stream())
			.forEach(it -> registerMemTableType(it.id(), it.partType()));
	}

	/**
	 * Registers new type that could be stored into the {@link MemTable} along with its unique id.
	 */
	public void registerMemTableType(byte id, Class<? extends StoragePart> type) {
		Assert.isPremiseValid(!this.idToTypeIndex.containsKey(id), () -> "The id is already set to `" + this.idToTypeIndex.get(id) + "` class!");
		Assert.isPremiseValid(!this.typeToIdIndex.containsKey(type), () -> "The class has already set id `" + this.typeToIdIndex.get(type) + "`!");
		this.idToTypeIndex.put(id, type);
		this.typeToIdIndex.put(type, id);
	}

	/**
	 * Returns real type for the passes record type id.
	 */
	@Nonnull
	public Class<? extends StoragePart> typeFor(byte id) {
		return Optional.ofNullable(idToTypeIndex.get(id))
			.orElseThrow(() -> new EvitaInternalError("Type id " + id + " cannot be handled by MemTable!"));
	}

	/**
	 * Returns record type id for passed class.
	 */
	public byte idFor(@Nonnull Class<? extends StoragePart> type) {
		return Optional.ofNullable(typeToIdIndex.get(type))
			.orElseThrow(() -> new EvitaInternalError("Type " + type + " cannot be handled by MemTable!"));
	}

}
