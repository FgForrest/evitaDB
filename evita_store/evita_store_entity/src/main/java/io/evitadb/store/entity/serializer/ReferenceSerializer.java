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
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage.DecodedKeys;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.spi.store.catalog.persistence.ReferenceDecodeCoverageContext;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
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
	/**
	 * One-entry memo of the last decoded reference name and what was resolved from it. The references of one entity
	 * arrive grouped by name, so the previous answer serves the next reference almost every time - which replaces
	 * a hash plus a set probe plus a schema map lookup per reference with one `String.equals`. An entity may carry
	 * tens of thousands of references, and this method decodes every one of them.
	 *
	 * A plain field is safe: this serializer is instantiated once per {@link Kryo} instance (see
	 * `EntityStoragePartConfigurer`), and a Kryo instance is never used by two threads at the same time.
	 */
	private String memoizedReferenceName;
	/**
	 * The coverage the {@link #memoizedNameAdmission} decision was taken under, compared by identity - a different
	 * coverage instance invalidates the decision even when the name repeats.
	 */
	private ReferenceDecodeCoverage memoizedCoverage;
	/**
	 * What the {@link #memoizedCoverage} lets through for {@link #memoizedReferenceName}. Resolved once per run of
	 * same-named references rather than per reference, which is what makes the narrowing cheap on a record holding
	 * tens of thousands of them.
	 */
	private NameAdmission memoizedNameAdmission;
	/**
	 * The referenced entity primary keys {@link #memoizedReferenceName} is narrowed to, valid only while
	 * {@link #memoizedNameAdmission} is {@link NameAdmission#BY_KEY}.
	 *
	 * Held as the coverage's own immutable value rather than a bare array so that memoizing it across a run of
	 * references cannot alias mutable state the coverage is hashed on.
	 */
	private DecodedKeys memoizedAdmittedKeys;
	/**
	 * The entity schema {@link #memoizedReferenceSchema} was resolved from, compared by identity - a schema change
	 * invalidates the resolution.
	 */
	private EntitySchema memoizedEntitySchema;
	/**
	 * The reference schema of {@link #memoizedReferenceName}, valid while {@link #memoizedEntitySchema} still matches.
	 */
	private ReferenceSchema memoizedReferenceSchema;

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
		final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverageContext.getDecodeCoverage();
		if (!referenceName.equals(this.memoizedReferenceName) || coverage != this.memoizedCoverage) {
			if (coverage == null || coverage.isNameDecodedWhole(referenceName)) {
				// no narrowing at all, or this name narrowed by nothing but its own presence
				this.memoizedNameAdmission = NameAdmission.WHOLE;
				this.memoizedAdmittedKeys = null;
			} else {
				this.memoizedAdmittedKeys = coverage.getAdmittedKeys(referenceName);
				this.memoizedNameAdmission = this.memoizedAdmittedKeys == null ?
					NameAdmission.NONE : NameAdmission.BY_KEY;
			}
			this.memoizedCoverage = coverage;
			this.memoizedReferenceName = referenceName;
			// the name decides the reference schema, so a new name invalidates that resolution too
			this.memoizedEntitySchema = null;
			this.memoizedReferenceSchema = null;
		}
		if (this.memoizedNameAdmission == NameAdmission.NONE) {
			// the caller cannot see any reference of this name, so only advance the stream past it - not
			// materializing it is the whole point of the coverage, and it is what makes a projection over an entity
			// carrying tens of thousands of back-references cost the handful of references it actually asked for
			skipReferenceBody(kryo, input);
			return null;
		}
		if (this.memoizedNameAdmission == NameAdmission.BY_KEY) {
			// the referenced primary key is the very next field, so narrowing by key costs four bytes and a binary
			// search on top of the name decision - and saves the body, the objects it would build, and the garbage
			final int referencedPrimaryKey = input.readInt();
			if (!this.memoizedAdmittedKeys.contains(referencedPrimaryKey)) {
				skipReferenceBodyAfterPrimaryKey(kryo, input);
				return null;
			}
			return readAdmittedReference(kryo, input, referenceName, version, internalPrimaryKey, referencedPrimaryKey);
		}
		// the name is admitted in full - the referenced primary key is read here rather than inside the shared
		// decoder so that both admissions consume the stream in exactly the same order
		final int referencedPrimaryKey = input.readInt();
		return readAdmittedReference(kryo, input, referenceName, version, internalPrimaryKey, referencedPrimaryKey);
	}

	/**
	 * Decodes the rest of a reference whose header has been consumed and which the coverage admits.
	 *
	 * Shared by both admissions so the two cannot drift: a reference let through by name and one let through by key
	 * are the same bytes decoded the same way, and the only difference is where the referenced primary key was read.
	 *
	 * @param kryo                 the Kryo instance used to decode attribute values
	 * @param input                the input positioned right after the referenced entity primary key
	 * @param referenceName        name decoded from the header, used to resolve the reference schema
	 * @param version              version decoded from the header
	 * @param internalPrimaryKey   internal primary key decoded from the header
	 * @param referencedPrimaryKey primary key of the referenced entity, already consumed from the stream
	 * @return the decoded reference
	 */
	@Nonnull
	private Reference readAdmittedReference(
		@Nonnull Kryo kryo,
		@Nonnull Input input,
		@Nonnull String referenceName,
		int version,
		int internalPrimaryKey,
		int referencedPrimaryKey
	) {
		// deliberately resolved *after* the skip decision: the accessor builds three Optionals per call and a
		// skipped reference has no use for the schema, so hoisting it above the filter made the narrowing pay a
		// schema lookup for every reference it was created to avoid touching
		final EntitySchema schema = io.evitadb.spi.store.catalog.persistence.EntitySchemaContext.getEntitySchema();
		// resolved once - the schema lookup used to run twice per reference (group type plus construction),
		// and this method decodes every reference of every entity the query touches
		final ReferenceSchema referenceSchema;
		if (schema == this.memoizedEntitySchema) {
			referenceSchema = this.memoizedReferenceSchema;
		} else {
			referenceSchema = schema.getReferenceOrThrowException(referenceName);
			this.memoizedEntitySchema = schema;
			this.memoizedReferenceSchema = referenceSchema;
		}
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
		final Map<AttributeKey, AttributeValue> attributes;
		if (attributeCount == 0) {
			// the overwhelmingly common shape - a back-reference carrying no attributes of its own - and an entity
			// may hold tens of thousands of those. The empty map costs no object, and `Attributes` then collects its
			// locales over a view whose iterator is a shared singleton instead of over a fresh LinkedHashMap's
			attributes = Collections.emptyMap();
		} else {
			final LinkedHashMap<AttributeKey, AttributeValue> decodedAttributes =
				CollectionUtils.createLinkedHashMap(attributeCount);
			for (int i = 0; i < attributeCount; i++) {
				final AttributeValue attributeValue = kryo.readObject(input, AttributeValue.class);
				decodedAttributes.put(attributeValue.key(), attributeValue);
			}
			attributes = decodedAttributes;
		}

		return new Reference(
			schema,
			referenceSchema,
			version,
			// the schema's own name instance, not the one just decoded: `input.readString()` hands back a fresh
			// String for every reference, so keeping it would retain one per reference and force every later
			// name comparison through String.equals instead of settling on identity
			new ReferenceKey(referenceSchema.getName(), referencedPrimaryKey, internalPrimaryKey),
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
		skipReferenceBodyAfterPrimaryKey(kryo, input);
	}

	/**
	 * Advances the input past the remainder of a reference whose header **and referenced entity primary key** have
	 * already been consumed, without materializing anything from it.
	 *
	 * This is the tail {@link #skipReferenceBody(Kryo, Input)} runs after reading the primary key, split out because
	 * a coverage narrowed by key has to read that key to take its decision and must not read it twice.
	 *
	 * @param kryo  the Kryo instance used to decode the skipped attribute values
	 * @param input the input positioned right after the referenced entity primary key
	 */
	private static void skipReferenceBodyAfterPrimaryKey(@Nonnull Kryo kryo, @Nonnull Input input) {
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

	/**
	 * What a {@link ReferenceDecodeCoverage} lets through for one reference name, resolved once per run of
	 * same-named references.
	 */
	private enum NameAdmission {

		/**
		 * Every reference of the name may be materialized.
		 */
		WHOLE,
		/**
		 * Only references whose referenced entity primary key is among the admitted keys may be materialized.
		 */
		BY_KEY,
		/**
		 * No reference of the name may be materialized.
		 */
		NONE

	}

}
