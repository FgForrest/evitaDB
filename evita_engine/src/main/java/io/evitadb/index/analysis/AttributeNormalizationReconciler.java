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


package io.evitadb.index.analysis;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.index.analysis.AttributeNormalizationPolicy.ElementBinding;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Computes built-with normalization metadata when a collection adopts a schema declaration.
 * Only a changed String policy needs a population probe. Populated identities retain their previous steps;
 * empty identities adopt the declaration. Probes inspect transaction-visible in-memory indexes without storage IO.
 */
@Slf4j
public final class AttributeNormalizationReconciler {
	/** Utility class. */
	private AttributeNormalizationReconciler() {
	}

	/**
	 * Collects the currently declared compound identities, also used to inspect legacy compounds without metadata.
	 *
	 * @param schema entity and reference compound declarations
	 * @return ordered element bindings keyed by scope/locale-free compound identity
	 */
	@Nonnull
	public static Map<AttributeIndexKey, List<ElementBinding>> describeCompounds(@Nonnull EntitySchemaContract schema) {
		final Map<AttributeIndexKey, List<ElementBinding>> result = CollectionUtils.createHashMap(8);
		addCompounds(result, null, schema.getSortableAttributeCompounds(),
			name -> schema.getAttribute(name).orElse(null));
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			addCompounds(result, reference.getName(),
				reference instanceof ReflectedReferenceSchema reflected &&
					!reflected.isReflectedReferenceAvailable() ? reflected.getDeclaredSortableAttributeCompounds() :
					reference.getSortableAttributeCompounds(),
				name -> attributesOf(reference).get(name));
		}
		return result;
	}

	/** Reads declarations safely before a reflected reference has been bound to its source. */
	@Nonnull
	static Map<String, AttributeSchemaContract> attributesOf(@Nonnull ReferenceSchemaContract reference) {
		return reference instanceof ReflectedReferenceSchema reflected &&
			!reflected.isReflectedReferenceAvailable() ? reflected.getDeclaredAttributes() : reference.getAttributes();
	}

	/** Resolves ordered names and localization without retaining schema objects. */
	private static void addCompounds(
		@Nonnull Map<AttributeIndexKey, List<ElementBinding>> result, @Nullable String referenceName,
		@Nonnull Map<String, ? extends SortableAttributeCompoundSchemaContract> compounds,
		@Nonnull Function<String, AttributeSchemaContract> attributes
	) {
		compounds.forEach((name, compound) -> {
			final List<ElementBinding> elements = new ArrayList<>(compound.getAttributeElements().size());
			for (final SortableAttributeCompoundSchemaContract.AttributeElement element :
				compound.getAttributeElements()) {
				final AttributeSchemaContract attribute = attributes.apply(element.attributeName());
				if (attribute == null) {
					return;
				}
				elements.add(new ElementBinding(element.attributeName(), attribute.getPlainType(),
					attribute.isLocalized(),
					attribute instanceof GlobalAttributeSchemaContract));
			}
			result.put(new AttributeIndexKey(referenceName, name, null), List.copyOf(elements));
		});
	}

	/**
	 * Reconciles every declaration and retained orphan. A type change keeps the former String row while its old keys
	 * survive. A rename creates an independent identity; the old name keeps its row until its old keys disappear.
	 * Ownership moves in either direction only when the identity is empty: a populated local identity keeps its local
	 * row even if its new declaration uses a global attribute, and a populated adopted global identity keeps the catalog
	 * row even if its declaration becomes local.
	 *
	 * @param owner collection name for diagnostics
	 * @param previous previous built-with policy, including retained legacy bindings
	 * @param schema updated declarations
	 * @param populated transaction-visible String-key probe, prepared once for this reconciliation
	 * @param compoundPopulated transaction-visible compound footprint probe
	 * @param globalRows catalog built-with rows, compared with a retained local row only for the diagnostic
	 * @return reconciled immutable descriptions
	 */
	@Nonnull
	public static AttributeNormalizationPolicy reconcile(
		@Nonnull String owner, @Nonnull AttributeNormalizationPolicy previous,
		@Nonnull EntitySchemaContract schema, @Nonnull Predicate<AttributeIndexKey> populated,
		@Nonnull Predicate<AttributeIndexKey> compoundPopulated,
		@Nonnull Map<String, List<AnalysisStep>> globalRows
	) {
		final Map<AttributeIndexKey, AttributeSchemaContract> declarations = CollectionUtils.createHashMap(16);
		schema.getAttributes().forEach((name, attribute) ->
			declarations.put(new AttributeIndexKey(null, name, null), attribute));
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			attributesOf(reference).forEach((name, attribute) ->
				declarations.put(new AttributeIndexKey(reference.getName(), name, null), attribute));
		}
		final Map<AttributeIndexKey, List<AnalysisStep>> rows = new HashMap<>(previous.rows());
		final Set<String> globals = new HashSet<>(previous.globalAttributes());
		final Set<AttributeIndexKey> identities = new HashSet<>(rows.keySet());
		identities.addAll(declarations.keySet());
		for (final String name : globals) {
			identities.add(new AttributeIndexKey(null, name, null));
		}
		for (final AttributeIndexKey key : identities) {
			final AttributeSchemaContract attribute = declarations.get(key);
			if (attribute instanceof GlobalAttributeSchemaContract) {
				if (!globals.contains(key.attributeName())) {
					if (populated.test(key)) {
						final List<AnalysisStep> effective = rows.getOrDefault(key, List.of());
						final List<AnalysisStep> global = globalRows.getOrDefault(key.attributeName(), List.of());
						if (!effective.equals(global)) {
							warn(owner, key, attribute.getNormalization(), effective);
						}
					} else {
						rows.remove(key);
						globals.add(key.attributeName());
					}
				}
				continue;
			}
			boolean knownEmpty = false;
			if (key.referenceName() == null && globals.contains(key.attributeName())) {
				if (populated.test(key)) {
					continue;
				}
				globals.remove(key.attributeName());
				knownEmpty = true;
			}
			final List<AnalysisStep> effective = rows.getOrDefault(key, List.of());
			if (attribute == null || attribute.getPlainType() != String.class) {
				if (effective.isEmpty()) {
					continue;
				} else if (knownEmpty || !populated.test(key)) {
					rows.remove(key);
				} else {
					warn(owner, key, List.of(), effective);
				}
			} else if (!effective.equals(attribute.getNormalization())) {
				if (!knownEmpty && populated.test(key)) {
					warn(owner, key, attribute.getNormalization(), effective);
				} else if (attribute.getNormalization().isEmpty()) {
					rows.remove(key);
				} else {
					rows.put(key, List.copyOf(attribute.getNormalization()));
				}
			}
		}
		final Map<AttributeIndexKey, List<ElementBinding>> compounds = new HashMap<>(previous.compounds());
		final Map<AttributeIndexKey, List<ElementBinding>> declaredCompounds = describeCompounds(schema);
		final Set<AttributeIndexKey> compoundKeys = new HashSet<>(compounds.keySet());
		compoundKeys.addAll(declaredCompounds.keySet());
		for (final AttributeIndexKey key : compoundKeys) {
			final List<ElementBinding> elements = declaredCompounds.get(key);
			if (Objects.equals(compounds.get(key), elements)) {
				continue;
			}
			if (!compounds.containsKey(key) || !compoundPopulated.test(key)) {
				if (elements == null) {
					compounds.remove(key);
				} else {
					compounds.put(key, elements);
				}
			}
		}
		return new AttributeNormalizationPolicy(rows, compounds, globals);
	}

	/**
	 * Emits one diagnostic per affected logical attribute at the schema-adoption boundary.
	 *
	 * @param owner collection or catalog name
	 * @param key scope/locale-free attribute identity
	 * @param declared new declaration
	 * @param effective retained built-with steps
	 */
	public static void warn(
		@Nonnull String owner, @Nonnull AttributeIndexKey key,
		@Nonnull List<AnalysisStep> declared, @Nonnull List<AnalysisStep> effective
	) {
		// TOBEDONE JNO #409 - rebuild the keys of a populated attribute whose normalization changed
		log.warn("Attribute `{}` in `{}` declares normalization {} but populated indexes retain built-with {}.",
			key, owner, declared, effective);
	}

	/**
	 * Tests only the named scalar or a compound containing it at a String position. Scalar stores are inspected by
	 * their frozen comparator type and emptiness; only relevant compound tuples need a value cursor.
	 *
	 * @param indexKey physical sort identity, including locale
	 * @param index sort tree to inspect
	 * @param attribute scope/locale-free attribute identity
	 * @param policy retained compound bindings, supplemented with legacy declarations
	 * @return true if a non-null indexed String key survives
	 */
	public static boolean containsStringKey(
		@Nonnull AttributeIndexKey indexKey, @Nonnull SortIndex index,
		@Nonnull AttributeIndexKey attribute, @Nonnull AttributeNormalizationPolicy policy
	) {
		if (!Objects.equals(indexKey.referenceName(), attribute.referenceName())) {
			return false;
		}
		final List<ElementBinding> elements = policy.compounds().get(
			new AttributeIndexKey(indexKey.referenceName(), indexKey.attributeName(), null));
		if (elements == null) {
			if (!indexKey.attributeName().equals(attribute.attributeName())) {
				return false;
			}
			final SortIndex.ComparatorSource[] sources = index.getComparatorBase();
			return sources.length == 1 && sources[0].type() == String.class && !index.isEmpty();
		}
		for (int i = 0; i < elements.size(); i++) {
			final ElementBinding element = elements.get(i);
			if (element.type() == String.class && element.attributeName().equals(attribute.attributeName())) {
				final Iterator<Serializable> values = index.getDistinctValueIterator(false);
				while (values.hasNext()) {
					if (((SortIndex.ComparableArray) values.next()).array()[i] != null) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Tests whether a physical key belongs to a scope/locale-free identity, whatever its locale.
	 *
	 * @param key physical index key, possibly localized
	 * @param identity scope/locale-free attribute identity
	 * @return true when both name the same reference and attribute
	 */
	public static boolean isSameIdentity(@Nonnull AttributeIndexKey key, @Nonnull AttributeIndexKey identity) {
		return Objects.equals(key.referenceName(), identity.referenceName()) &&
			key.attributeName().equals(identity.attributeName());
	}

	/**
	 * Tests the cardinality counters an index keeps without a filter or sort tree for any String key of the identity.
	 *
	 * @param counters cardinality counters keyed by physical index key
	 * @param identity scope/locale-free attribute identity
	 * @return true if a non-empty String counter of the identity survives
	 */
	public static boolean hasStringCardinalityKeys(
		@Nonnull Map<AttributeIndexKey, AttributeCardinalityIndex> counters, @Nonnull AttributeIndexKey identity
	) {
		final boolean[] found = {false};
		counters.forEach((key, counter) -> {
			if (isSameIdentity(key, identity) && !counter.isEmpty() && counter.getValueType() == String.class) {
				found[0] = true;
			}
		});
		return found[0];
	}
}
