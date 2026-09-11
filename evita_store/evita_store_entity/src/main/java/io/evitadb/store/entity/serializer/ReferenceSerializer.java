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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.spi.store.catalog.persistence.ReferenceNameFilterContext;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * This {@link Serializer} implementation reads/writes {@link Reference} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ReferenceSerializer extends Serializer<Reference> {

	@Override
	public void write(Kryo kryo, Output output, Reference reference) {
		output.writeVarInt(reference.version(), true);
		final ReferenceKey referenceKey = reference.getReferenceKey();
		Assert.isPremiseValid(referenceKey.isKnownInternalPrimaryKey(), "Reference internal id must be positive!");
		output.writeVarInt(referenceKey.internalPrimaryKey(), true);
		output.writeString(referenceKey.referenceName());
		output.writeInt(referenceKey.primaryKey());
		output.writeBoolean(reference.dropped());
		final Optional<GroupEntityReference> group = reference.getGroup();
		output.writeBoolean(group.isPresent());
		group.ifPresent(it -> {
			output.writeVarInt(it.version(), true);
			output.writeInt(it.getPrimaryKey());
			output.writeBoolean(it.dropped());
		});
		final Collection<AttributeValue> attributes = reference.getAttributeValues();
		output.writeVarInt(attributes.size(), true);
		// the attributes locales are always sorted to ensure the same order of attributes in the serialized form
		attributes.stream().sorted(Comparator.comparing(AttributeValue::key))
			.forEach(attribute -> kryo.writeObject(output, attribute));
	}

	@Override
	@Nullable
	public Reference read(Kryo kryo, Input input, Class<? extends Reference> type) {
		final int version = input.readVarInt(true);
		final int internalPrimaryKey = input.readVarInt(true);
		final String referenceName = input.readString();
		final Set<String> referenceNameFilter = ReferenceNameFilterContext.getReferenceNameFilter();
		if (referenceNameFilter != null && !referenceNameFilter.contains(referenceName)) {
			// the caller cannot see this reference, so only advance the stream past it - not materializing it is
			// the whole point of the filter, and it is what makes a projection over an entity carrying tens of
			// thousands of back-references cost the handful of references it actually asked for
			skipReferenceBody(kryo, input);
			return null;
		}
		// deliberately resolved *after* the skip decision: the accessor builds three Optionals per call and a
		// skipped reference has no use for the schema, so hoisting it above the filter made the narrowing pay a
		// schema lookup for every reference it was created to avoid touching
		final EntitySchema schema = io.evitadb.spi.store.catalog.persistence.EntitySchemaContext.getEntitySchema();
		// resolved once - the schema lookup used to run twice per reference (group type plus construction),
		// and this method decodes every reference of every entity the query touches
		final ReferenceSchema referenceSchema = schema.getReferenceOrThrowException(referenceName);
		final int entityPrimaryKey = input.readInt();
		final boolean dropped = input.readBoolean();
		final boolean groupExists = input.readBoolean();
		final GroupEntityReference group;
		if (groupExists) {
			final int groupVersion = input.readVarInt(true);
			final int groupPrimaryKey = input.readInt();
			final boolean groupDropped = input.readBoolean();
			final String groupType = Objects.requireNonNull(referenceSchema.getReferencedGroupType());
			group = new GroupEntityReference(groupType, groupPrimaryKey, groupVersion, groupDropped);
		} else {
			group = null;
		}
		final int attributeCount = input.readVarInt(true);
		final LinkedHashMap<AttributeKey, AttributeValue> attributes = CollectionUtils.createLinkedHashMap(attributeCount);
		for (int i = 0; i < attributeCount; i++) {
			final AttributeValue attributeValue = kryo.readObject(input, AttributeValue.class);
			attributes.put(attributeValue.key(), attributeValue);
		}

		return new Reference(
			schema,
			referenceSchema,
			version,
			// the schema's own name instance, not the one just decoded: `input.readString()` hands back a fresh
			// String for every reference, so keeping it would retain one per reference and force every later
			// name comparison through String.equals instead of settling on identity
			new ReferenceKey(referenceSchema.getName(), entityPrimaryKey, internalPrimaryKey),
			group, attributes, dropped
		);
	}

	/**
	 * Advances the input past the body of a reference whose header (version, internal primary key and name) has
	 * already been consumed, without materializing anything from it.
	 *
	 * The binary layout is written by {@link #write(Kryo, Output, Reference)} and must be mirrored field for field -
	 * the stream is not self-delimiting, so an incorrect skip desynchronizes the rest of the storage part rather
	 * than failing at the reference itself. Attribute values are the one part that still has to be decoded: they are
	 * variable length records with no length prefix, so the only way past them is through their own serializer.
	 *
	 * @param kryo  the Kryo instance used to decode the skipped attribute values
	 * @param input the input positioned right after the reference name
	 */
	private static void skipReferenceBody(@Nonnull Kryo kryo, @Nonnull Input input) {
		input.readInt();
		input.readBoolean();
		if (input.readBoolean()) {
			input.readVarInt(true);
			input.readInt();
			input.readBoolean();
		}
		final int attributeCount = input.readVarInt(true);
		for (int i = 0; i < attributeCount; i++) {
			kryo.readObject(input, AttributeValue.class);
		}
	}

}
