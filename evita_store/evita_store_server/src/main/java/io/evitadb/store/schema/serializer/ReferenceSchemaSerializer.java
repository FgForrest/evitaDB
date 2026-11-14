/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.schema.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.NamingConvention;
import lombok.RequiredArgsConstructor;
import javax.annotation.Nonnull;
import java.util.EnumMap;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readBucketedHistogramMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readFacetedPartiallyMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readIndexedComponentsMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readNameVariants;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopeSet;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readScopedReferenceIndexTypeArray;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.readSortableAttributeCompounds;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeBucketedHistogramMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeFacetedPartiallyMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeIndexedComponentsMap;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeNameVariants;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeScopeSet;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeScopedReferenceIndexTypeArray;
import static io.evitadb.store.schema.serializer.EntitySchemaSerializer.writeSortableAttributeCompounds;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceSchema} from/to binary format.
 *
 * The layout ends with the text composition mode of each scope followed by the shared weight; the layout without
 * that tail is the one {@link ReferenceSchemaSerializer_2026_2_2} reads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ReferenceSchemaSerializer extends Serializer<ReferenceSchema> {
	private static final Function<String, EntitySchemaContract> IMPOSSIBLE_EXCEPTION_PRODUCER = s -> {
		throw new GenericEvitaInternalError("Sanity check!");
	};

	@Override
	public void write(Kryo kryo, Output output, ReferenceSchema referenceSchema) {
		output.writeString(referenceSchema.getName());
		writeNameVariants(output, referenceSchema.getNameVariants());
		output.writeString(referenceSchema.getReferencedEntityType());
		output.writeBoolean(referenceSchema.isReferencedEntityTypeManaged());

		final Map<NamingConvention, String> entityTypeNameVariants = referenceSchema.isReferencedEntityTypeManaged() ?
			Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(IMPOSSIBLE_EXCEPTION_PRODUCER);
		writeNameVariants(output, entityTypeNameVariants);
		kryo.writeObject(output, referenceSchema.getCardinality());
		output.writeString(referenceSchema.getReferencedGroupType());
		output.writeBoolean(referenceSchema.isReferencedGroupTypeManaged());

		final Map<NamingConvention, String> groupTypeNameVariants = referenceSchema.isReferencedGroupTypeManaged() ?
			Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(IMPOSSIBLE_EXCEPTION_PRODUCER);
		writeNameVariants(output, groupTypeNameVariants);

		writeScopedReferenceIndexTypeArray(kryo, output, referenceSchema.getReferenceIndexTypeInScopes());
		writeIndexedComponentsMap(kryo, output, referenceSchema.getIndexedComponentsInScopes());
		writeScopeSet(kryo, output, referenceSchema.getFacetedInScopes());

		writeFacetedPartiallyMap(kryo, output, referenceSchema.getFacetedPartiallyInScopes());

		writeBucketedHistogramMap(kryo, output, referenceSchema.getAllHistogramIndexDefinitions());
		// reuse faceted partially serializer — same Map<Scope, Expression> shape
		writeFacetedPartiallyMap(kryo, output, referenceSchema.getBucketedPartiallyInScopes());

		kryo.writeObject(output, referenceSchema.getAttributes());

		if (referenceSchema.getDescription() != null) {
			output.writeBoolean(true);
			output.writeString(referenceSchema.getDescription());
		} else {
			output.writeBoolean(false);
		}
		if (referenceSchema.getDeprecationNotice() != null) {
			output.writeBoolean(true);
			output.writeString(referenceSchema.getDeprecationNotice());
		} else {
			output.writeBoolean(false);
		}

		writeSortableAttributeCompounds(kryo, output, referenceSchema.getSortableAttributeCompounds().values());

		kryo.writeObject(output, referenceSchema.getConflictResolutionOverride());
		writeSearchModes(kryo, output, referenceSchema.getSearchableThroughInScopes());
		output.writeFloat(referenceSchema.getSearchableThroughWeight());
	}

	@Override
	public ReferenceSchema read(Kryo kryo, Input input, Class<? extends ReferenceSchema> aClass) {
		final String name = input.readString();
		final Map<NamingConvention, String> nameVariants = readNameVariants(input);
		final String entityType = input.readString();
		final boolean referencedEntityTypeManaged = input.readBoolean();
		final Map<NamingConvention, String> entityTypeNameVariants = readNameVariants(input);
		final Cardinality cardinality = kryo.readObject(input, Cardinality.class);
		final String groupType = input.readString();
		final boolean referencedGroupTypeManaged = input.readBoolean();
		final Map<NamingConvention, String> groupTypeNameVariants = readNameVariants(input);
		final Map<Scope, ReferenceIndexType> indexedInScopes = readScopedReferenceIndexTypeArray(kryo, input);
		final Map<Scope, Set<ReferenceIndexedComponents>> indexedComponentsInScopes = readIndexedComponentsMap(kryo, input);
		final EnumSet<Scope> facetedInScopes = readScopeSet(kryo, input);

		final Map<Scope, Expression> facetedPartiallyInScopes = readFacetedPartiallyMap(kryo, input);

		final Map<Scope, Map<String, HistogramIndexDefinition>> bucketedInScopes = readBucketedHistogramMap(kryo, input);
		// reuse faceted partially serializer — same Map<Scope, Expression> shape
		final Map<Scope, Expression> bucketedPartiallyInScopes = readFacetedPartiallyMap(kryo, input);

		@SuppressWarnings("unchecked") final Map<String, AttributeSchemaContract> attributes = kryo.readObject(input, Map.class);
		final String description = input.readBoolean() ? input.readString() : null;
		final String deprecationNotice = input.readBoolean() ? input.readString() : null;

		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = readSortableAttributeCompounds(kryo, input);

		final ConflictResolutionOverride conflictResolutionOverride = kryo.readObject(input, ConflictResolutionOverride.class);

		final Map<Scope, ReferenceSearchMode> searchModes = readSearchModes(kryo, input);
		final float searchWeight = input.readFloat();

		return ReferenceSchema._internalBuild(
			name, nameVariants, description, deprecationNotice,
			cardinality,
			entityType, entityTypeNameVariants, referencedEntityTypeManaged,
			groupType, groupTypeNameVariants, referencedGroupTypeManaged,
			indexedInScopes,
			indexedComponentsInScopes,
			facetedInScopes,
			facetedPartiallyInScopes,
			bucketedInScopes,
			bucketedPartiallyInScopes,
			attributes, sortableAttributeCompounds, conflictResolutionOverride, searchModes, searchWeight
		);
	}

	/**
	 * Writes the complete, non-null composition scope map.
	 *
	 * @param kryo Kryo providing name-based enum serialization
	 * @param output destination for the declaration
	 * @param modes composition mode in each declared scope
	 */
	static void writeSearchModes(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull Map<Scope, ReferenceSearchMode> modes
	) {
		output.writeVarInt(modes.size(), true);
		for (final Map.Entry<Scope, ReferenceSearchMode> mode : modes.entrySet()) {
			kryo.writeObject(output, mode.getKey());
			kryo.writeObject(output, mode.getValue());
		}
	}

	/**
	 * Reads the complete composition scope map.
	 *
	 * @param kryo Kryo providing name-based enum serialization
	 * @param input source of the declaration
	 * @return declared composition modes, empty when disabled everywhere
	 */
	@Nonnull
	static Map<Scope, ReferenceSearchMode> readSearchModes(@Nonnull Kryo kryo, @Nonnull Input input) {
		final int count = input.readVarInt(true);
		final Map<Scope, ReferenceSearchMode> modes = new EnumMap<>(Scope.class);
		for (int i = 0; i < count; i++) {
			modes.put(kryo.readObject(input, Scope.class), kryo.readObject(input, ReferenceSearchMode.class));
		}
		return modes;
	}

}
