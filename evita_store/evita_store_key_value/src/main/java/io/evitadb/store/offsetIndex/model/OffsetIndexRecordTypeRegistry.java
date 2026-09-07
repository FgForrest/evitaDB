/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.offsetIndex.model;

import io.evitadb.api.statistics.StoragePartGroup;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.shared.service.StoragePartRegistry;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * This enum contains all supported classes that can be stored in {@link OffsetIndex}. This
 * enum is used for translating full Class to a small number to minimize memory overhead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class OffsetIndexRecordTypeRegistry {
	/**
	 * Maps the class types of StorageParts to corresponding byte IDs.
	 */
	private final Map<Class<? extends StoragePart>, Byte> typeToIdIndex = new HashMap<>(64);
	/**
	 * Maps the byte IDs to corresponding class types of StorageParts.
	 */
	private final Map<Byte, Class<? extends StoragePart>> idToTypeIndex = new HashMap<>(64);
	/**
	 * Maps the simple class name of each registered type to the group it belongs to. Keyed by simple name because
	 * that is the identity {@link OffsetIndex#getHistogram()} reports a record type under, and the registration
	 * assertions below are what make that key unambiguous.
	 */
	private final Map<String, StoragePartGroup> nameToGroupIndex = new HashMap<>(64);

	public OffsetIndexRecordTypeRegistry() {
		ServiceLoader.load(StoragePartRegistry.class)
			.stream()
			.map(Provider::get)
			.flatMap(it -> it.listStorageParts().stream())
			.forEach(it -> registerFileOffsetIndexType(it.id(), it.partType(), it.group()));
	}

	/**
	 * Registers new type that could be stored into the {@link OffsetIndex} along with its unique id and the group it
	 * belongs to.
	 *
	 * @param id    unique id among all other storage parts
	 * @param type  the class of storage part
	 * @param group which group of stored data the type belongs to, reported by the storage composition statistics
	 */
	public void registerFileOffsetIndexType(byte id, @Nonnull Class<? extends StoragePart> type, @Nonnull StoragePartGroup group) {
		// checked first, so a rejected registration records nothing at all. `@Nonnull` is documentation rather than
		// enforcement, and an unclassified type that got past here would be stored under its name and then be
		// indistinguishable, to `groupFor`, from a name nobody ever registered - reporting a type that IS registered
		// as one the OffsetIndex cannot handle, and sending its reader after a registration that is not missing
		Assert.isPremiseValid(
			group != null,
			() -> "The storage part type `" + type.getSimpleName() + "` was registered without a group!"
		);
		Assert.isPremiseValid(!this.idToTypeIndex.containsKey(id), () -> "The id is already set to `" + this.idToTypeIndex.get(id) + "` class!");
		Assert.isPremiseValid(!this.typeToIdIndex.containsKey(type), () -> "The class has already set id `" + this.typeToIdIndex.get(type) + "`!");
		// the histogram identifies a record type by its simple name, so two registered types sharing one would merge
		// into a single row that reports the sum of both and names only one of them. Caught here rather than left to
		// be noticed in a composition table
		Assert.isPremiseValid(
			!this.nameToGroupIndex.containsKey(type.getSimpleName()),
			() -> "The simple class name `" + type.getSimpleName() + "` is already registered by another storage part type!"
		);
		this.idToTypeIndex.put(id, type);
		this.typeToIdIndex.put(type, id);
		this.nameToGroupIndex.put(type.getSimpleName(), group);
	}

	/**
	 * Returns real type for the passes record type id.
	 */
	@Nonnull
	public Class<? extends StoragePart> typeFor(byte id) {
		return Optional.ofNullable(this.idToTypeIndex.get(id))
			.orElseThrow(() -> new GenericEvitaInternalError("Type id " + id + " cannot be handled by OffsetIndex!"));
	}

	/**
	 * Returns record type id for passed class.
	 */
	public byte idFor(@Nonnull Class<? extends StoragePart> type) {
		return Optional.ofNullable(this.typeToIdIndex.get(type))
			.orElseThrow(() -> new GenericEvitaInternalError("Type " + type + " cannot be handled by OffsetIndex!"));
	}

	/**
	 * Returns the group the record type of the passed simple class name belongs to - the classification a storage
	 * composition breakdown groups by.
	 *
	 * The lookup is by simple name because that is how {@link OffsetIndex#getHistogram()} identifies a record type;
	 * registration refuses two types that would share one, so the name resolves to exactly one group.
	 *
	 * @param recordTypeSimpleName simple class name of a registered storage part type
	 * @return the group the type belongs to
	 */
	@Nonnull
	public StoragePartGroup groupFor(@Nonnull String recordTypeSimpleName) {
		return Optional.ofNullable(this.nameToGroupIndex.get(recordTypeSimpleName))
			.orElseThrow(() -> new GenericEvitaInternalError("Type " + recordTypeSimpleName + " cannot be handled by OffsetIndex!"));
	}

}
