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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prices owner-held immutable maps and description content once. Indexes borrow resolvers and functions; collection
 * policy excludes the catalog rows it borrows. Estimates follow JDK immutable collection shapes, conservatively
 * charging description text even when a schema also references it.
 */
public final class AttributeNormalizationPolicyHeapSize {
	/** Utility class. */
	private AttributeNormalizationPolicyHeapSize() {
	}

	/**
	 * Prices the collection-owned row map, compound identities and retained global-name set.
	 *
	 * @param policy collection-owned immutable metadata
	 * @return estimated retained heap bytes
	 */
	public static long estimate(@Nonnull AttributeNormalizationPolicy policy) {
		if (policy == AttributeNormalizationPolicy.EMPTY) {
			return 0L;
		}
		final VMLayout layout = VMLayout.current();
		final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		long size = layout.sizeOfObject(3L * layout.referenceSize()) + mapShell(policy.rows().size());
		for (final Map.Entry<AttributeIndexKey, List<AnalysisStep>> row : policy.rows().entrySet()) {
			size += layout.sizeOfObject(3L * layout.referenceSize()) + steps(row.getValue(), seen) +
				text(row.getKey().attributeName(), seen) + text(row.getKey().referenceName(), seen);
		}
		size += mapShell(policy.compounds().size());
		for (final Map.Entry<AttributeIndexKey, List<AttributeNormalizationPolicy.ElementBinding>> compound :
			policy.compounds().entrySet()) {
			final List<AttributeNormalizationPolicy.ElementBinding> elements = compound.getValue();
			size += text(compound.getKey().attributeName(), seen) + text(compound.getKey().referenceName(), seen);
			size += layout.sizeOfObject(3L * layout.referenceSize()) + listShell(elements.size());
			for (final AttributeNormalizationPolicy.ElementBinding element : elements) {
				size += layout.sizeOfObject(2L * layout.referenceSize() + 2L * Byte.BYTES) +
					text(element.attributeName(), seen);
			}
		}
		final int globalCount = policy.globalAttributes().size();
		if (globalCount > 0) {
			size += globalCount <= 2 ? layout.sizeOfObject(2L * layout.referenceSize()) :
				layout.sizeOfObject(layout.referenceSize() + Integer.BYTES) +
					layout.sizeOfArray(globalCount * 2, layout.referenceSize());
			for (final String name : policy.globalAttributes()) {
				size += text(name, seen);
			}
		}
		return size;
	}

	/**
	 * Prices the catalog-owned rows, including parameter arrays and text, once for the entire catalog.
	 *
	 * @param rows authoritative built-with global descriptions
	 * @return estimated retained heap bytes
	 */
	public static long estimateGlobalRows(@Nonnull Map<String, List<AnalysisStep>> rows) {
		final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		long size = mapShell(rows.size());
		for (final Map.Entry<String, List<AnalysisStep>> row : rows.entrySet()) {
			size += text(row.getKey(), seen) + steps(row.getValue(), seen);
		}
		return size;
	}

	/**
	 * Prices distinct shared immutable step lists and their complete parameter payloads.
	 *
	 * @param steps the step list to price
	 * @param seen  identity set of objects already priced in this budget; priced objects are added to it
	 * @return estimated retained bytes, `0` when the same list instance was already priced
	 */
	private static long steps(@Nonnull List<AnalysisStep> steps, @Nonnull Set<Object> seen) {
		if (!seen.add(steps)) {
			return 0L;
		}
		final VMLayout layout = VMLayout.current();
		long size = listShell(steps.size());
		for (final AnalysisStep step : steps) {
			if (seen.add(step)) {
				size += layout.sizeOfObject(2L * layout.referenceSize()) + text(step.name(), seen);
				final Map<String, Serializable> parameters = step.parameters();
				// unmodifiable wrapper, LinkedHashMap, bucket table and linked nodes
				size += layout.sizeOfObject(2L * layout.referenceSize()) +
					layout.sizeOfObject(6L * layout.referenceSize() + 4L * Integer.BYTES + Byte.BYTES);
				if (!parameters.isEmpty()) {
					final int capacity = Math.max(2, Integer.highestOneBit((int) (parameters.size() / 0.75f)) << 1);
					size += layout.sizeOfArray(capacity, layout.referenceSize());
				}
				for (final Map.Entry<String, Serializable> parameter : parameters.entrySet()) {
					size += layout.sizeOfObject(Integer.BYTES + 5L * layout.referenceSize()) +
						text(parameter.getKey(), seen) + EvitaDataTypes.estimateSize(parameter.getValue());
				}
			}
		}
		return size;
	}

	/**
	 * Prices a shared description/name String at most once in this owner's budget.
	 *
	 * @param text the string to price, may be `null`
	 * @param seen identity set of objects already priced in this budget; the string is added to it
	 * @return estimated retained bytes, `0` for `null` or a string instance already priced
	 */
	private static long text(@Nullable String text, @Nonnull Set<Object> seen) {
		return text != null && seen.add(text) ? EvitaDataTypes.estimateSize(text) : 0L;
	}

	/**
	 * Immutable Map1 has two slots; MapN uses an alternating-key/value array at half occupancy.
	 *
	 * @param count number of entries in the map
	 * @return estimated retained bytes of the map shell without its entries, `0` for an empty map
	 */
	private static long mapShell(int count) {
		if (count == 0) {
			return 0L;
		}
		final VMLayout layout = VMLayout.current();
		return count == 1 ? layout.sizeOfObject(4L * layout.referenceSize()) :
			layout.sizeOfObject(3L * layout.referenceSize() + Integer.BYTES) +
				layout.sizeOfArray(count * 4, layout.referenceSize());
	}

	/**
	 * Immutable List12 has two slots; ListN owns its element array.
	 *
	 * @param count number of elements in the list
	 * @return estimated retained bytes of the list shell without its elements, `0` for an empty list
	 */
	private static long listShell(int count) {
		if (count == 0) {
			return 0L;
		}
		final VMLayout layout = VMLayout.current();
		return count == 1 || count == 2 ? layout.sizeOfObject(2L * layout.referenceSize()) :
			layout.sizeOfObject(layout.referenceSize() + Byte.BYTES) +
				layout.sizeOfArray(count, layout.referenceSize());
	}
}
