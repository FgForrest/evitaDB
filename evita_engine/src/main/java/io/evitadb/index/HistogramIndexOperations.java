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

package io.evitadb.index;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;

/**
 * Package-private helper consolidating the four {@link HistogramCapableEntityIndex} operations
 * shared by {@link ReferencedTypeEntityIndex} and {@link ReducedGroupEntityIndex}. Both classes
 * are siblings under {@code EntityIndex} (not parent-child), so this helper carries the shared
 * logic without forcing accessor methods that would leak `histogramIndexes` / `dirty` onto the
 * public interface. Each subclass keeps its own `histogramIndexes` field and `dirty` flag and
 * passes them in at the call site — including the reference-name expression, which differs
 * between the two subclasses ({@code getRepresentativeReferenceKey().referenceName()} on
 * `ReducedGroupEntityIndex` vs {@code getReferenceName()} on `ReferencedTypeEntityIndex`).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class HistogramIndexOperations {

	private HistogramIndexOperations() {
	}

	/**
	 * Inserts a histogram value for the given owner entity, lazily creating the histogram index on
	 * first use. The created index is locale-aware iff `locale` is non-`null`. Sets the supplied
	 * `dirty` flag to signal that the owning entity index has pending changes.
	 *
	 * @param histograms    the subclass-owned histogram index map
	 * @param dirty         the subclass-owned dirty flag to mark as `true` after the insert
	 * @param referenceName the reference name to attach to a newly-created histogram index
	 * @param histogramName the name of the histogram definition
	 * @param locale        the locale for localized histograms, or `null` for non-localized
	 * @param value         the histogram value in its original type (a `Number` or `Range`)
	 * @param ownerPK       the primary key of the owner entity
	 * @param valueType     the plain type of the value (used for lazy index creation)
	 */
	static void insertHistogramValue(
		@Nonnull TransactionalMap<String, HistogramIndex> histograms,
		@Nonnull TransactionalBoolean dirty,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK,
		@Nonnull Class<? extends Serializable> valueType
	) {
		final HistogramIndex histogramIndex = histograms.computeIfAbsent(
			histogramName,
			k -> locale != null
				? new LocalizedHistogramIndex(histogramName, referenceName, valueType)
				: new SimpleHistogramIndex(histogramName, referenceName, valueType)
		);
		histogramIndex.insertValue(locale, value, ownerPK);
		dirty.setToTrue();
	}

	/**
	 * Removes a histogram value for the given owner entity. If the histogram index becomes empty
	 * after the removal, drops it from the map and detaches its transactional layer when a
	 * maintainer is present.
	 *
	 * @param histograms                the subclass-owned histogram index map
	 * @param dirty                     the subclass-owned dirty flag to mark as `true` after the
	 *                                  remove
	 * @param transactionalLayer        the current transactional layer maintainer, or `null` when
	 *                                  no transaction is active
	 * @param histogramName             the name of the histogram definition
	 * @param locale                    the locale for localized histograms, or `null` for
	 *                                  non-localized
	 * @param value                     the histogram value to remove
	 * @param ownerPK                   the primary key of the owner entity
	 */
	static void removeHistogramValue(
		@Nonnull TransactionalMap<String, HistogramIndex> histograms,
		@Nonnull TransactionalBoolean dirty,
		@Nullable TransactionalLayerMaintainer transactionalLayer,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final HistogramIndex histogramIndex = histograms.get(histogramName);
		Assert.isPremiseValid(
			histogramIndex != null,
			() -> "Histogram index for histogram " + histogramName + " not found."
		);
		histogramIndex.removeValue(locale, value, ownerPK);
		dirty.setToTrue();
		// if the histogram index is now empty, remove it from the map and clean up transactional layers
		if (histogramIndex.isEmpty()) {
			final HistogramIndex removed = histograms.remove(histogramName);
			if (removed != null && transactionalLayer != null) {
				removed.removeLayer(transactionalLayer);
			}
		}
	}

}
