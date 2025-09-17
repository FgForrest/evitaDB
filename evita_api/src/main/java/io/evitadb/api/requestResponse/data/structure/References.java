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

package io.evitadb.api.requestResponse.data.structure;


import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException.Operation;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.chunk.NoTransformer;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferencesContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.EqualsAndHashCode;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import one.edee.oss.proxycian.util.ReflectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static java.util.Optional.ofNullable;

/**
 * References refer to other entities (of same or different entity type).
 * Allows entity filtering (but not sorting) of the entities by using {@link FacetHaving} query
 * and statistics computation if when {@link FacetStatistics} requirement is used. Reference
 * is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and {@link Serializable} entity type and can be
 * part of multiple reference groups, that are also represented by int and {@link Serializable} entity type.
 *
 * Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
 * of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
 * to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
 * group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
 * Evita.
 *
 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@EqualsAndHashCode(of = "referenceCollection")
@Immutable
@ThreadSafe
public class References implements ReferencesContract {
	@Serial private static final long serialVersionUID = -148670616615520545L;
	/**
	 * Default implementation of the chunk transformer that simply wraps the input list into a one page facade.
	 */
	public static final ChunkTransformerAccessor DEFAULT_CHUNK_TRANSFORMER =
		referenceName -> NoTransformer.INSTANCE;
	/**
	 * Internal reference constant used for recognition of duplicate references.
	 */
	static final ReferenceContract DUPLICATE_REFERENCE = createThrowingStub();
	/**
	 * Definition of the entity schema.
	 */
	final EntitySchemaContract entitySchema;
	/**
	 * Contains all references of the entity.
	 */
	private final Collection<ReferenceContract> referenceCollection;
	/**
	 * Contains an index of entity references by their unique keys. Contains only references that cannot have duplicates
	 * according to a {@link ReferenceSchemaContract#getCardinality()}.
	 * @see ReferenceContract
	 */
	private final Map<ReferenceKey, ReferenceContract> references;
	/**
	 * Contains an index of entity references by their unique keys. Contains only references that may have duplicates
	 * according to a {@link ReferenceSchemaContract#getCardinality()}.
	 * @see ReferenceContract
	 */
	private final Map<ReferenceKey, List<ReferenceContract>> duplicateReferences;
	/**
	 * Contains set of all {@link ReferenceSchemaContract#getName()} defined in entity {@link EntitySchemaContract}.
	 */
	private final Set<String> referencesDefined;
	/**
	 * Contains map of all references by their name. This map is used for fast lookup of the references by their name
	 * and is initialized lazily on first request.
	 */
	private Map<String, DataChunk<ReferenceContract>> referencesByName;
	/**
	 * Contains transformer function that takes FULL list of references by their name and returns page that conforms
	 * to the evita request. The function is called lazily on first request for chunked data. The function should not
	 * trap entire evita request in its closure.
	 */
	private final ChunkTransformerAccessor referenceChunkTransformer;

	/**
	 * Creates mock instance of reference contract that
	 */
	@Nonnull
	private static ReferenceContract createThrowingStub() {
		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(
				"DUPLICATE_REFERENCE_MARKER",
				// special toString implementation
				new PredicateMethodClassification<>(
					"Object methods",
					(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, Object.class, "toString"),
					(method, state) -> null,
					(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState
				),
				// objects method must pass through
				new PredicateMethodClassification<>(
					"Object methods",
					(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, Object.class),
					(method, state) -> null,
					(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
						try {
							return invokeSuper.call();
						} catch (Exception e) {
							throw new InvocationTargetException(e);
						}
					}
				),
				// for all other methods we will throw the exception that the reference is not indexed
				new PredicateMethodClassification<>(
					"All other methods",
					(method, proxyState) -> true,
					(method, state) -> null,
					(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
						throw new UnsupportedOperationException("Not supposed to be called");
					}
				)
			),
			new Class<?>[]{
				ReferenceContract.class
			},
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	public References(@Nonnull EntitySchemaContract schema) {
		this.entitySchema = schema;
		this.references = Map.of();
		this.referenceCollection = List.of();
		this.duplicateReferences = Map.of();
		this.referencesDefined = Collections.emptySet();
		this.referenceChunkTransformer = DEFAULT_CHUNK_TRANSFORMER;
	}

	public References(
		@Nonnull EntitySchemaContract schema,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull Set<String> referencesDefined,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) {
		this.entitySchema = schema;
		this.referenceCollection = Collections.unmodifiableCollection(references);
		// this quite ugly part is necessary so that we can propely handle references that allow duplicates
		// i.e. references with same ReferenceKey, that are distinguished only by their set of attributes
		Map<ReferenceKey, ReferenceContract> indexedReferences = references.isEmpty() ? null : CollectionUtils.createHashMap(references.size());
		Map<ReferenceKey, List<ReferenceContract>> duplicatedIndexedReferences = null;

		// cache last resolved schema to avoid Optional/map overhead on each iteration
		ReferenceKey lastReferenceKey = null;

		for (ReferenceContract reference : references) {
			final ReferenceKey referenceKey = reference.getReferenceKey();
			final boolean duplicate = lastReferenceKey != null && lastReferenceKey.referenceName().equals(referenceKey.referenceName()) &&
				lastReferenceKey.primaryKey() == referenceKey.primaryKey();

			if (duplicate) {
				final boolean duplicatesAllowed = schema.getEvolutionMode().contains(EvolutionMode.UPDATING_REFERENCE_CARDINALITY) ||
					schema.getReference(referenceKey.referenceName())
					      .map(ReferenceSchemaContract::getCardinality)
					      .map(Cardinality::allowsDuplicates)
					      .orElse(false);
				if (duplicatesAllowed) {
					if (duplicatedIndexedReferences == null) {
						duplicatedIndexedReferences = CollectionUtils.createHashMap(references.size());
					}
					final ReferenceKey genericKey = new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey());
					final ReferenceContract previous = indexedReferences.remove(lastReferenceKey);
					final List<ReferenceContract> duplicatedList;
					if (previous != null) {
						duplicatedList = new ArrayList<>(2);
						duplicatedIndexedReferences.put(genericKey, duplicatedList);
						duplicatedList.add(previous);
					} else {
						duplicatedList = Objects.requireNonNull(duplicatedIndexedReferences.get(genericKey));
					}
					duplicatedList.add(reference);
					indexedReferences.put(genericKey, DUPLICATE_REFERENCE);
				} else {
					throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), schema, Operation.CREATE);
				}
			} else {
				indexedReferences.putIfAbsent(referenceKey, reference);
			}

			lastReferenceKey = referenceKey;
		}

		this.references = indexedReferences == null ? Map.of() : Collections.unmodifiableMap(indexedReferences);
		if (duplicatedIndexedReferences == null) {
			this.duplicateReferences = Map.of();
		} else {
			// wrap lists first, then map
			for (Entry<ReferenceKey, List<ReferenceContract>> entry : duplicatedIndexedReferences.entrySet()) {
				entry.setValue(Collections.unmodifiableList(entry.getValue()));
			}
			this.duplicateReferences = Collections.unmodifiableMap(duplicatedIndexedReferences);
		}

		this.referencesDefined = referencesDefined;
		this.referenceChunkTransformer = referenceChunkTransformer;
	}

	@Override
	public boolean referencesAvailable() {
		return true;
	}

	@Override
	public boolean referencesAvailable(@Nonnull String referenceName) {
		return true;
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		return this.referenceCollection;
	}

	@Nonnull
	@Override
	public Set<String> getReferenceNames() {
		return this.referencesDefined;
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		return this.getReferenceChunk(referenceName).getData();
	}

	@Nonnull
	@Override
	public DataChunk<ReferenceContract> getReferenceChunk(@Nonnull String referenceName) throws ContextMissingException {
		checkReferenceNameAndCardinality(referenceName, true, true);
		if (this.referencesByName == null) {
			// here we never limit the references by input requirements
			// this is managed on the entity decorator level
			final HashMap<String, DataChunk<ReferenceContract>> chunksByName =
				CollectionUtils.createHashMap(this.referencesDefined.size());
			for (ReferenceContract reference : this.referenceCollection) {
				chunksByName.computeIfAbsent(
					            reference.getReferenceKey().referenceName(),
					            it -> new PlainChunk<>(new ArrayList<>(2))
				            )
				            .getData()
				            .add(reference);
			}
			this.referencesByName = chunksByName;
		}
		return this.referencesByName.computeIfAbsent(
			referenceName,
			refName -> this.referenceChunkTransformer.apply(refName).createChunk(Collections.emptyList())
		);
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		checkReferenceNameAndCardinality(referenceName, false, true);
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedEntityId);
		final ReferenceContract reference = this.references.get(referenceKey);
		if (reference == References.DUPLICATE_REFERENCE) {
			throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), this.entitySchema, Operation.READ);
		}
		return ofNullable(reference);
	}

	/**
	 * Returns reference contract without checking the existence in the schema.
	 * Part of the private API.
	 */
	@Nonnull
	public List<ReferenceContract> getReferencesWithoutSchemaCheck(
		@Nonnull ReferenceKey referenceKey
	) throws ContextMissingException, ReferenceNotFoundException {
		final ReferenceContract reference = this.references.get(referenceKey);
		return reference == References.DUPLICATE_REFERENCE ?
			this.duplicateReferences.get(referenceKey) :
			(reference == null ? Collections.emptyList() : Collections.singletonList(reference));
	}

	/**
	 * Returns reference contract without checking the existence in the schema.
	 * Part of the private API.
	 */
	@Nonnull
	public Optional<ReferenceContract> getReferenceWithoutSchemaCheck(@Nonnull ReferenceKey referenceKey) {
		final ReferenceContract reference = this.references.get(referenceKey);
		if (reference == References.DUPLICATE_REFERENCE) {
			if (referenceKey.isUnknownReference()) {
				throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), this.entitySchema, Operation.READ);
			} else {
				return Objects
					// when this.references contains DUPLICATE_REFERENCE marker,
					// this.duplicateReferences must contain the actual references
					.requireNonNull(this.duplicateReferences.get(referenceKey))
					.stream()
					.filter(it -> it.getReferenceKey().internalPrimaryKey() == referenceKey.internalPrimaryKey())
					.findFirst();
			}
		}
		return ofNullable(reference);
	}

	@Override
	@Nonnull
	public Optional<ReferenceContract> getReference(@Nonnull ReferenceKey referenceKey) {
		checkReferenceNameAndCardinality(referenceKey.referenceName(), false, referenceKey.isUnknownReference());
		return getReferenceWithoutSchemaCheck(referenceKey);
	}

	@Nonnull
	@Override
	public List<ReferenceContract> getReferences(
		@Nonnull ReferenceKey referenceKey
	) throws ContextMissingException, ReferenceNotFoundException {
		checkReferenceNameAndCardinality(referenceKey.referenceName(), true, referenceKey.isUnknownReference());
		final ReferenceContract reference = this.references.get(referenceKey);
		return reference == References.DUPLICATE_REFERENCE ?
			this.duplicateReferences.get(referenceKey) :
			(reference == null ? Collections.emptyList() : Collections.singletonList(reference));
	}

	/**
	 * Checks whether the reference is defined in the schema or is otherwise known.
	 */
	public void checkReferenceNameAndCardinality(@Nonnull String referenceName, boolean methodAllowsDuplicates, boolean internalPrimaryKeyUnknown) {
		final Optional<ReferenceSchemaContract> schemaDefined = this.entitySchema.getReference(referenceName);
		Assert.isTrue(
			// schema is either defined or can be added automatically
			schemaDefined.isPresent() ||
				(this.entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_REFERENCES) &&
					this.referencesDefined.contains(referenceName)),
			() -> new ReferenceNotFoundException(referenceName, this.entitySchema)
		);
		if (!methodAllowsDuplicates && internalPrimaryKeyUnknown) {
			Assert.isTrue(
				// schema must not allow duplicates
				!schemaDefined.map(it -> it.getCardinality().allowsDuplicates()).orElse(false),
				() -> new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.READ)
			);
		}
	}

	/**
	 * This method is part of the internal API and is not meant to be used by the client code.
	 */
	@Nonnull
	public ChunkTransformerAccessor getReferenceChunkTransformer() {
		return this.referenceChunkTransformer;
	}

	/**
	 * This interface provides access to chunk transformers for particular reference name. The implementations are
	 * not ought to check the existence of reference in the schema and simply fall back to {@link NoTransformer}
	 * implementation if necessary, but they must never return NULL.
	 */
	public interface ChunkTransformerAccessor {

		/**
		 * Returns chunk transformer for the given reference name.
		 *
		 * @param referenceName name of the reference
		 * @return chunk transformer for the given reference name, never NULL
		 */
		@Nonnull
		ChunkTransformer apply(@Nonnull String referenceName);

	}

}
