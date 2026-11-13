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

package io.evitadb.core.query;

import io.evitadb.api.proxy.WithEntityContract;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.extraResult.*;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.StripList;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;


/**
 * Compares materialized verification results, including bodies hidden by public identity equality. Entity value
 * comparison uses each projection's materialized keys and values; fetched bodies are traversed recursively.
 * Telemetry, IO accounting, schema metadata and internal value versions are not semantic result content.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class QueryResponseComparator {
	/** Utility class. */
	private QueryResponseComparator() {
	}

	/** Compares page identity, pagination and order, then materialized bodies and all non-telemetry extra results. */
	static boolean matches(@Nonnull EvitaResponse<?> first, @Nonnull EvitaResponse<?> second) {
		if (first.getClass() != second.getClass() || !first.getRecordPage().equals(second.getRecordPage()) ||
			!Arrays.equals(first.getPrimaryKeys(), second.getPrimaryKeys()) ||
			!sameContent(first.getRecordData(), second.getRecordData())) {
			return false;
		}
		for (final Class<? extends EvitaResponseExtraResult> type : second.getExtraResultTypes()) {
			if (type != QueryTelemetry.class && !first.getExtraResultTypes().contains(type)) return false;
		}
		for (final Map.Entry<Class<? extends EvitaResponseExtraResult>, EvitaResponseExtraResult> entry :
			first.getExtraResults().entrySet()) {
			if (entry.getKey() != QueryTelemetry.class &&
				!sameContent(entry.getValue(), second.getExtraResult(entry.getKey()))) return false;
		}
		return true;
	}

	/** Compares reference chunk metadata as well as ordered rows, including nested referenced and group bodies. */
	private static boolean sameReferences(@Nonnull EntityContract first, @Nonnull EntityContract second) {
		if (!first.getReferenceNames().equals(second.getReferenceNames())) return false;
		for (final String name : first.getReferenceNames()) {
			final DataChunk<ReferenceContract> left = first.getReferenceChunk(name);
			final DataChunk<ReferenceContract> right = second.getReferenceChunk(name);
			if (!sameChunkMetadata(left, right) || !sameContent(left.getData(), right.getData())) return false;
		}
		return true;
	}

	/** Compares named-only sets which the server deliberately omits from the ordinary reference view. */
	private static boolean sameNamedReferences(@Nonnull EntityContract first, @Nonnull EntityContract second) {
		final Map<?, ?> left = first instanceof ServerEntityDecorator entity ? entity.getNamedReferenceSets() : null;
		final Map<?, ?> right = second instanceof ServerEntityDecorator entity ? entity.getNamedReferenceSets() : null;
		return sameContent(left == null ? Map.of() : left, right == null ? Map.of() : right);
	}

	/** Matches materialized values by their own keys, without invoking getters for the opposite projection. */
	private static <K, V> boolean sameValues(
		@Nonnull Collection<V> first, @Nonnull Collection<V> second, @Nonnull Function<V, K> key
	) {
		if (first.size() != second.size()) return false;
		final Map<K, V> right = CollectionUtils.createHashMap(second.size());
		for (final V value : second) right.put(key.apply(value), value);
		for (final V value : first) {
			if (!sameContent(value, right.get(key.apply(value)))) return false;
		}
		return true;
	}

	/** Compares attribute availability and only the values actually present in each projection. */
	private static boolean sameAttributes(@Nonnull AttributesContract<?> first, @Nonnull AttributesContract<?> second) {
		return first.attributesAvailable() == second.attributesAvailable() && (!first.attributesAvailable() ||
			sameValues(first.getAttributeValues(), second.getAttributeValues(), AttributeValue::key));
	}

	/** Compares chunk metadata independently of rows; unpaged PlainChunk deliberately has identity equality. */
	private static boolean sameChunkMetadata(@Nonnull DataChunk<?> first, @Nonnull DataChunk<?> second) {
		if (first.getClass() != second.getClass() || first.getTotalRecordCount() != second.getTotalRecordCount()) {
			return false;
		}
		return switch (first) {
			case PaginatedList<?> page -> {
				final PaginatedList<?> other = (PaginatedList<?>) second;
				yield page.getPageNumber() == other.getPageNumber() && page.getPageSize() == other.getPageSize() &&
					page.getLastPageNumber() == other.getLastPageNumber();
			}
			case StripList<?> strip -> {
				final StripList<?> other = (StripList<?>) second;
				yield strip.getOffset() == other.getOffset() && strip.getLimit() == other.getLimit();
			}
			case PlainChunk<?> ignored -> true;
		};
	}

	/** Walks the API's materialized content containers; scalar values retain their API equality semantics. */
	private static boolean sameContent(@Nullable Object first, @Nullable Object second) {
		if (first == second) return true;
		if (first == null || second == null) return false;
		if (first instanceof WithEntityContract a && second instanceof WithEntityContract b) {
			return sameContent(a.entity(), b.entity());
		}
		if (first instanceof EntityContract a && second instanceof EntityContract b) {
			return Objects.equals(a.getPrimaryKey(), b.getPrimaryKey()) && a.getType().equals(b.getType()) &&
				a.version() == b.version() && a.dropped() == b.dropped() && a.getScope() == b.getScope() &&
				a.getLocales().equals(b.getLocales()) && a.getAllLocales().equals(b.getAllLocales()) &&
				a.parentAvailable() == b.parentAvailable() && sameAttributes(a, b) &&
				a.associatedDataAvailable() == b.associatedDataAvailable() &&
				(!a.associatedDataAvailable() || sameValues(a.getAssociatedDataValues(), b.getAssociatedDataValues(),
					AssociatedDataValue::key)) && a.pricesAvailable() == b.pricesAvailable() &&
				(!a.pricesAvailable() || (a.getPriceInnerRecordHandling() == b.getPriceInnerRecordHandling() &&
					sameValues(a.getPrices(), b.getPrices(), PriceContract::priceKey))) &&
				a.referencesAvailable() == b.referencesAvailable() &&
				(!a.parentAvailable() ||
					sameContent(a.getParentEntity().orElse(null), b.getParentEntity().orElse(null))) &&
				(!a.referencesAvailable() || sameReferences(a, b)) && sameNamedReferences(a, b);
		}
		if (first instanceof EntityClassifierWithParent a && second instanceof EntityClassifierWithParent b) {
			return a.equals(b) && sameContent(a.getParentEntity().orElse(null), b.getParentEntity().orElse(null));
		}
		if (first instanceof ReferenceContract a && second instanceof ReferenceContract b) {
			return a.getReferenceKey().equals(b.getReferenceKey()) && a.dropped() == b.dropped() &&
				!a.getGroup().map(group -> group.differsFrom(b.getGroup().orElse(null)))
					.orElseGet(() -> b.getGroup().isPresent()) && sameAttributes(a, b) &&
				sameContent(a.getReferencedEntity().orElse(null), b.getReferencedEntity().orElse(null)) &&
				sameContent(a.getGroupEntity().orElse(null), b.getGroupEntity().orElse(null));
		}
		if (first instanceof AttributeValue a && second instanceof AttributeValue b) {
			return a.key().equals(b.key()) && a.dropped() == b.dropped() &&
				!QueryUtils.valueDiffers(a.value(), b.value());
		}
		if (first instanceof AssociatedDataValue a && second instanceof AssociatedDataValue b) {
			return !a.differsFrom(b);
		}
		if (first instanceof PriceContract a && second instanceof PriceContract b) {
			return a.priceKey().equals(b.priceKey()) && !a.differsFrom(b);
		}
		if (first instanceof DataChunk<?> a && second instanceof DataChunk<?> b) {
			return sameChunkMetadata(a, b) && sameContent(a.getData(), b.getData());
		}

		if (first instanceof Hierarchy a && second instanceof Hierarchy b) {
			return sameContent(a.getSelfHierarchy(), b.getSelfHierarchy()) &&
				sameContent(a.getReferenceHierarchies(), b.getReferenceHierarchies());
		}
		if (first instanceof Hierarchy.LevelInfo a && second instanceof Hierarchy.LevelInfo b) {
			return a.requested() == b.requested() && Objects.equals(a.queriedEntityCount(), b.queriedEntityCount()) &&
				Objects.equals(a.childrenCount(), b.childrenCount()) && sameContent(a.entity(), b.entity()) &&
				sameContent(a.children(), b.children());
		}
		if (first instanceof ReferenceSummary a && second instanceof ReferenceSummary b) {
			if (!a.equals(b)) return false;
			for (final ReferenceGroupStatistics group : a.getReferenceStatistics()) {
				final ReferenceGroupStatistics other = group.getGroupEntity() == null ?
					b.getReferenceGroupStatistics(group.getReferenceName()) :
					b.getReferenceGroupStatistics(group.getReferenceName(),
						group.getGroupEntity().getPrimaryKeyOrThrowException());
				if (!sameContent(group, other)) return false;
			}
			return true;
		}
		if (first instanceof ReferenceGroupStatistics a && second instanceof ReferenceGroupStatistics b) {
			return a.equals(b) && sameContent(a.getGroupEntity(), b.getGroupEntity()) &&
				sameContent(a.getFacetStatistics(), b.getFacetStatistics()) &&
				sameContent(a.getHistogramStatistics(), b.getHistogramStatistics());
		}
		if (first instanceof FacetStatistics a && second instanceof FacetStatistics b) {
			return a.equals(b) && sameContent(a.getFacetEntity(), b.getFacetEntity());
		}
		if (first instanceof AttributeHistogram a && second instanceof AttributeHistogram b) {
			return sameContent(a.getHistograms(), b.getHistograms());
		}
		if (first instanceof HistogramContract a && second instanceof HistogramContract b) {
			return a.getMin().equals(b.getMin()) && a.getMax().equals(b.getMax()) &&
				a.getOverallCount() == b.getOverallCount() && Arrays.equals(a.getBuckets(), b.getBuckets()) &&
				sameContent(a.getMinReferencedEntity().orElse(null), b.getMinReferencedEntity().orElse(null)) &&
				sameContent(a.getMaxReferencedEntity().orElse(null), b.getMaxReferencedEntity().orElse(null));
		}
		if (first instanceof Map<?, ?> a && second instanceof Map<?, ?> b) {
			if (!a.keySet().equals(b.keySet())) return false;
			for (final Map.Entry<?, ?> entry : a.entrySet()) {
				if (!sameContent(entry.getValue(), b.get(entry.getKey()))) return false;
			}
			return true;
		}
		if (first instanceof Collection<?> a && second instanceof Collection<?> b) {
			if (a.size() != b.size()) return false;
			final Iterator<?> other = b.iterator();
			for (final Object item : a) {
				if (!sameContent(item, other.next())) return false;
			}
			return true;
		}
		return first.equals(second);
	}
}
