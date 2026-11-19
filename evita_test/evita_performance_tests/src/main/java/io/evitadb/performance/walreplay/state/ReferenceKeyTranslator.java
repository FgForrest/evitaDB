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

package io.evitadb.performance.walreplay.state;

import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites the internal reference ids a source WAL addresses into the ids of a catalog *rebuilt* from the source
 * snapshot, so the WAL can be replayed over the rebuild.
 *
 * A rebuild re-upserts every entity into a fresh catalog, and the engine assigns new internal ids to inserted
 * references - in sorted order, from a per-entity counter starting at zero - so a reference the source knows as
 * `relatedProducts: 42/39` may be `42/78` in the rebuild. References that allow duplicates can only be
 * addressed by that id, and the others are addressed by it whenever the client knows it, so an untranslated replay
 * fails on most transactions that touch existing references.
 *
 * The translation needs two facts per entity the replayed slice touches, both taken from the T0 state of the source
 * and of the rebuild and written to a tab-separated file by {@link ReferenceKeyMapBuilder}:
 *
 * - `R <type> <pk> <referenceName> <referencedPk> <sourceId> <targetId>` - the pairing of the references present at
 *   T0, matched per reference name and referenced entity in ascending id order, which the rebuild preserves;
 * - `E <type> <pk> <sourceLastUsedId> <targetLastUsedId>` - the last id each side's counter handed out at T0. A
 *   reference created during the slice gets the next counter value on either side, and the entity's own reference
 *   mutations advance both counters alike, so its target id is its source id shifted by the difference of the two
 *   counters.
 *
 * `X <type> <referenceName>` rows name reflected references, which are left untouched. Temporary (negative) and
 * unknown (zero) ids are left untouched as well - they mean the same in both catalogs. An entity the file does not
 * name did not exist at T0 and needs no translation, because both counters start from zero; the same holds after the
 * slice removes an entity, so its entry is dropped.
 *
 * **Known limits.** The two rules hold while ids change only through the entity's own reference mutations, which is
 * what production slices have shown so far (401 transactions replayed over a rebuild without a failure). They break
 * in four cases this class does not track:
 *
 * - an entity whose last reference is removed loses its reference container, and both counters restart from zero;
 *   the T0 entries still apply afterwards and may address a different duplicate of the same reference - silently;
 * - a reflected reference takes its id from the owning reference, which the rebuild renumbered, so a mutation
 *   addressing a reflected reference by a known id is passed through and fails;
 * - an inherited attribute changed from the owning side updates the reflected side and may raise that entity's
 *   counters, which shifts its later ids away from the offset rule;
 * - an insert carrying a positive id that was removed before T0 is passed through and may collide with a rebuilt
 *   duplicate.
 *
 * All but the first fail the transaction, which {@link WalReplayState} counts and refuses above its
 * `evita.replay.maxFailedShare`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceKeyTranslator {

	/**
	 * Translation data of the entities touched by the slice, keyed by entity type and primary key.
	 */
	private final Map<String, Map<Integer, EntityKeys>> entities;
	/**
	 * Reflected reference names per entity type - left untouched.
	 */
	private final Map<String, Set<String>> reflectedReferences;
	/**
	 * Number of entities the map file describes.
	 */
	@Getter private final int entityCount;
	/**
	 * Number of T0 reference pairs the map file describes.
	 */
	@Getter private final int referencePairCount;
	/**
	 * Reference keys rewritten through the T0 pairing.
	 */
	@Getter private long translatedByPairing;
	/**
	 * Reference keys rewritten by the counter offset - references created during the slice.
	 */
	@Getter private long translatedByOffset;

	/**
	 * Loads the translation map from the file written by {@link ReferenceKeyMapBuilder} - see the class documentation
	 * for its format.
	 *
	 * @param mapFile the tab-separated map file
	 * @return the translator
	 * @throws IOException when the file cannot be read
	 */
	@Nonnull
	public static ReferenceKeyTranslator load(@Nonnull Path mapFile) throws IOException {
		final Map<String, Map<Integer, EntityKeys>> entities = CollectionUtils.createHashMap(32);
		final Map<String, Set<String>> reflected = CollectionUtils.createHashMap(8);
		int entityCount = 0;
		int pairCount = 0;
		try (BufferedReader reader = Files.newBufferedReader(mapFile, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				final String[] columns = line.split("\t");
				switch (columns[0]) {
					case "X" -> reflected.computeIfAbsent(columns[1], type -> CollectionUtils.createHashSet(4))
						.add(columns[2]);
					case "E" -> {
						entityKeys(entities, columns[1], Integer.parseInt(columns[2]))
							.setCounters(Integer.parseInt(columns[3]), Integer.parseInt(columns[4]));
						entityCount++;
					}
					case "R" -> {
						entityKeys(entities, columns[1], Integer.parseInt(columns[2]))
							.sourceToTarget.put(Integer.parseInt(columns[5]), Integer.parseInt(columns[6]));
						pairCount++;
					}
					default -> throw new GenericEvitaInternalError(
						"Unknown row `" + columns[0] + "` in the reference key map `" + mapFile + "`.",
						"Unknown row in the reference key map."
					);
				}
			}
		}
		return new ReferenceKeyTranslator(entities, reflected, entityCount, pairCount);
	}

	/**
	 * Returns the translation data of an entity, creating an empty one on first access.
	 *
	 * @param entities   all translation data
	 * @param entityType the entity type
	 * @param primaryKey the entity primary key
	 * @return the entity's translation data
	 */
	@Nonnull
	private static EntityKeys entityKeys(
		@Nonnull Map<String, Map<Integer, EntityKeys>> entities,
		@Nonnull String entityType,
		int primaryKey
	) {
		return entities
			.computeIfAbsent(entityType, type -> CollectionUtils.createHashMap(1024))
			.computeIfAbsent(primaryKey, pk -> new EntityKeys());
	}

	private ReferenceKeyTranslator(
		@Nonnull Map<String, Map<Integer, EntityKeys>> entities,
		@Nonnull Map<String, Set<String>> reflectedReferences,
		int entityCount,
		int referencePairCount
	) {
		this.entities = entities;
		this.reflectedReferences = reflectedReferences;
		this.entityCount = entityCount;
		this.referencePairCount = referencePairCount;
	}

	/**
	 * Returns the mutation with every positive internal reference id rewritten into the rebuilt catalog's id, or
	 * the very same instance when nothing needed rewriting.
	 *
	 * @param mutation the entity mutation read from the source WAL
	 * @return the mutation to apply to the rebuilt catalog
	 */
	@Nonnull
	public EntityMutation translate(@Nonnull EntityMutation mutation) {
		final Integer primaryKey = mutation.getEntityPrimaryKey();
		if (primaryKey == null) {
			return mutation;
		}
		final Map<Integer, EntityKeys> ofType = this.entities.get(mutation.getEntityType());
		if (mutation instanceof EntityRemoveMutation) {
			// a re-created entity starts both counters from zero again - it needs no translation from now on
			if (ofType != null) {
				ofType.remove(primaryKey);
			}
			return mutation;
		}
		final EntityKeys keys = ofType == null ? null : ofType.get(primaryKey);
		if (keys == null || !(mutation instanceof EntityUpsertMutation upsert)) {
			return mutation;
		}
		final Set<String> reflected = this.reflectedReferences.get(mutation.getEntityType());
		final List<? extends LocalMutation<?, ?>> localMutations = upsert.getLocalMutations();
		List<LocalMutation<?, ?>> translated = null;
		for (int i = 0; i < localMutations.size(); i++) {
			final LocalMutation<?, ?> localMutation = localMutations.get(i);
			LocalMutation<?, ?> result = localMutation;
			if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
				final ReferenceKey key = referenceMutation.getReferenceKey();
				final boolean isReflected = reflected != null && reflected.contains(key.referenceName());
				if (key.isKnownInternalPrimaryKey() && !isReflected) {
					final int targetId = targetId(keys, key.internalPrimaryKey());
					if (targetId != key.internalPrimaryKey()) {
						result = rekey(
							referenceMutation, new ReferenceKey(key.referenceName(), key.primaryKey(), targetId)
						);
					}
				}
			}
			if (result != localMutation && translated == null) {
				// first rewrite - copy everything seen so far
				translated = new ArrayList<>(localMutations.size());
				for (int j = 0; j < i; j++) {
					translated.add(localMutations.get(j));
				}
			}
			if (translated != null) {
				translated.add(result);
			}
		}
		return translated == null ?
			mutation :
			new EntityUpsertMutation(upsert.getEntityType(), primaryKey, upsert.expects(), translated);
	}

	/**
	 * Maps a source internal reference id to the rebuilt catalog's id.
	 *
	 * @param keys     the entity's translation data
	 * @param sourceId the positive source id
	 * @return the target id (the source id when the reference is unknown to both rules)
	 */
	private int targetId(@Nonnull EntityKeys keys, int sourceId) {
		final Integer paired = keys.sourceToTarget.get(sourceId);
		if (paired != null) {
			if (paired != sourceId) {
				this.translatedByPairing++;
			}
			return paired;
		} else if (sourceId > keys.sourceLastUsedId) {
			// created during the slice - both counters advanced by the same count since T0
			final int shifted = sourceId + keys.targetLastUsedId - keys.sourceLastUsedId;
			if (shifted != sourceId) {
				this.translatedByOffset++;
			}
			return shifted;
		} else {
			// removed before T0 - passed through, which reproduces the source outcome unless the rebuild assigned this
			// id to another duplicate (see the class documentation)
			return sourceId;
		}
	}

	/**
	 * Re-creates a reference mutation with another reference key, keeping everything else including its decisive
	 * timestamp.
	 *
	 * @param mutation the original mutation
	 * @param key      the translated key
	 * @return the re-keyed mutation
	 */
	@Nonnull
	private static LocalMutation<?, ?> rekey(@Nonnull ReferenceMutation<?> mutation, @Nonnull ReferenceKey key) {
		final LocalMutation<?, ?> rekeyed = switch (mutation) {
			case InsertReferenceMutation insert -> new InsertReferenceMutation(
				key, insert.getReferenceCardinality(), insert.getReferencedEntityType()
			);
			case RemoveReferenceMutation ignored -> new RemoveReferenceMutation(key);
			case SetReferenceGroupMutation setGroup -> new SetReferenceGroupMutation(
				key, setGroup.getGroupType(), setGroup.getGroupPrimaryKey()
			);
			case RemoveReferenceGroupMutation ignored -> new RemoveReferenceGroupMutation(key);
			case ReferenceAttributeMutation attribute -> new ReferenceAttributeMutation(
				key, attribute.getAttributeMutation()
			);
		};
		return rekeyed.withDecisiveTimestamp(mutation.getDecisiveTimestamp());
	}

	/**
	 * Translation data of one entity.
	 */
	private static final class EntityKeys {
		/**
		 * T0 pairing of source internal reference ids to the rebuilt catalog's ids.
		 */
		private final Map<Integer, Integer> sourceToTarget = CollectionUtils.createHashMap(16);
		/**
		 * Last id the source counter handed out at T0.
		 */
		private int sourceLastUsedId;
		/**
		 * Last id the rebuilt catalog's counter handed out at T0.
		 */
		private int targetLastUsedId;

		/**
		 * Records both counters.
		 *
		 * @param sourceLastUsedId the source counter at T0
		 * @param targetLastUsedId the rebuilt catalog's counter at T0
		 */
		private void setCounters(int sourceLastUsedId, int targetLastUsedId) {
			this.sourceLastUsedId = sourceLastUsedId;
			this.targetLastUsedId = targetLastUsedId;
		}
	}

}
