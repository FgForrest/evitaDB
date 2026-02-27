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

package io.evitadb.api.requestResponse;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.dataType.DataChunk;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evita response contains all results to single query. Results are
 * divided to two parts - main results returned by
 * {@link #getRecordPage()} and set of extra results retrieved by
 * {@link #getExtraResult(Class)}.
 *
 * Evita tries to take advantage of all possible intermediate results
 * to minimize computational costs so that there could be a variety of
 * extra results attached to the base response data.
 *
 * The idea behind it is as follows: client requires data A and B, for
 * computing result A we need to compute data C. This data C is
 * necessary even for computing the result B, so we can reuse this
 * intermediate result C if both A and B results are queried and
 * returned within the same query. If computation of A takes 73ms,
 * B takes 62ms and both require intermediate computation C that takes
 * 42ms, then we could achieve result computation in
 * (73-42 + 62-42) = 93ms instead of (73+62) = 135ms that would take
 * when there were two separates queries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public abstract sealed class EvitaResponse<T extends Serializable>
	permits EvitaBinaryEntityResponse, EvitaEntityReferenceResponse, EvitaEntityResponse {
	/**
	 * The input query that produced this response.
	 */
	protected final Query sourceQuery;
	/**
	 * Page of records according to pagination rules in input query.
	 * If no pagination was defined in input query `page(1, 20)` is
	 * assumed.
	 */
	protected final DataChunk<T> recordPage;
	/**
	 * Extra results attached to the base data response. The key is the
	 * class of the extra result and the value is the extra result itself.
	 * The map is initialized at construction time and is unmodifiable.
	 */
	protected final Map<Class<? extends EvitaResponseExtraResult>, EvitaResponseExtraResult> extraResults;
	/**
	 * Number of IO fetches performed to fetch all data in this response.
	 * This is a sum of all fetches performed by all entities in the
	 * response. The value is initialized on first access and is memoized
	 * for subsequent calls. The value is zero if entities in the response
	 * don't implement {@link EntityFetchAwareDecorator} interface
	 * (which is available only on the server side).
	 */
	private Integer ioFetchCount;
	/**
	 * Number of bytes fetched by IO fetches performed to fetch all data
	 * in this response. This is a sum of all fetches performed by all
	 * entities in the response. The value is initialized on first access
	 * and is memoized for subsequent calls. The value is zero if entities
	 * in the response don't implement
	 * {@link EntityFetchAwareDecorator} interface
	 * (which is available only on the server side).
	 */
	private Integer ioFetchedSizeBytes;

	/**
	 * Creates a new response with the given source query and record page.
	 *
	 * @param sourceQuery the input query that produced this response
	 * @param recordPage  the page of records matching the query
	 */
	protected EvitaResponse(
		@Nonnull Query sourceQuery,
		@Nonnull DataChunk<T> recordPage
	) {
		this.sourceQuery = sourceQuery;
		this.recordPage = recordPage;
		this.extraResults = Collections.emptyMap();
	}

	/**
	 * Creates a new response with the given source query, record page,
	 * and additional extra results attached to the response.
	 *
	 * @param sourceQuery  the input query that produced this response
	 * @param recordPage   the page of records matching the query
	 * @param extraResults varargs of extra results to attach
	 */
	protected EvitaResponse(
		@Nonnull Query sourceQuery,
		@Nonnull DataChunk<T> recordPage,
		@Nonnull EvitaResponseExtraResult... extraResults
	) {
		this.sourceQuery = sourceQuery;
		this.recordPage = recordPage;
		final Map<Class<? extends EvitaResponseExtraResult>, EvitaResponseExtraResult> map =
			CollectionUtils.createHashMap(extraResults.length);
		for (final EvitaResponseExtraResult extraResult : extraResults) {
			map.put(extraResult.getClass(), extraResult);
		}
		this.extraResults = Collections.unmodifiableMap(map);
	}

	/**
	 * Returns input query this response reacts to.
	 */
	@Nonnull
	public Query getSourceQuery() {
		return this.sourceQuery;
	}

	/**
	 * Returns array of primary keys of the entities in {@link #getRecordData()}.
	 * @return array of primary keys
	 */
	@Nonnull
	public abstract int[] getPrimaryKeys();

	/**
	 * Returns page of records according to pagination rules in input
	 * query. If no pagination was defined in input query
	 * `page(1, 20)` is assumed.
	 */
	@Nonnull
	public DataChunk<T> getRecordPage() {
		return this.recordPage;
	}

	/**
	 * Returns slice of data that belongs to the requested page.
	 */
	@Nonnull
	public List<T> getRecordData() {
		return this.recordPage.getData();
	}

	/**
	 * Returns total count of available main records in entire result set
	 * (i.e. ignoring current pagination settings).
	 */
	public int getTotalRecordCount() {
		return this.recordPage.getTotalRecordCount();
	}

	/**
	 * Returns set of extra result types provided along with the base
	 * result.
	 */
	@Nonnull
	public Set<Class<? extends EvitaResponseExtraResult>> getExtraResultTypes() {
		return this.extraResults.keySet();
	}

	/**
	 * Returns extra result attached to the base data response of
	 * specified type. See documentation for this class.
	 */
	@Nullable
	public <S extends EvitaResponseExtraResult> S getExtraResult(@Nonnull Class<S> resultType) {
		final Object extraResult = this.extraResults.get(resultType);
		if (extraResult != null && !resultType.isInstance(extraResult)) {
			throw new GenericEvitaInternalError("This should never happen!");
		}
		//noinspection unchecked
		return (S) extraResult;
	}

	/**
	 * Returns all attached extra results.
	 */
	@Nonnull
	public Map<Class<? extends EvitaResponseExtraResult>,
		EvitaResponseExtraResult> getExtraResults() {
		return this.extraResults;
	}

	/**
	 * Retrieves the number of input/output fetch operations counted
	 * during the processing of this response. If the count has not
	 * been calculated yet, it will trigger the computation of I/O
	 * fetch statistics.
	 *
	 * @return the total count of I/O fetch operations for the
	 *         current response.
	 */
	public int getIoFetchCount() {
		if (this.ioFetchCount == null || this.ioFetchedSizeBytes == null) {
			computeIoFetchStats();
		}
		return this.ioFetchCount;
	}

	/**
	 * Retrieves the total size in bytes of data fetched during
	 * input/output operations within the processing of this response.
	 * If the size has not been calculated yet, it will trigger the
	 * computation of I/O fetch statistics.
	 *
	 * @return the total size in bytes of I/O fetch operations for
	 *         the current response.
	 */
	public int getIoFetchedSizeBytes() {
		if (this.ioFetchCount == null || this.ioFetchedSizeBytes == null) {
			computeIoFetchStats();
		}
		return this.ioFetchedSizeBytes;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final EvitaResponse<?> that = (EvitaResponse<?>) o;
		if (!this.recordPage.equals(that.recordPage)) {
			return false;
		}
		if (this.extraResults.isEmpty() && that.extraResults.isEmpty()) {
			return true;
		}
		if (this.extraResults.isEmpty() || that.extraResults.isEmpty()) {
			// one is empty, the other is not -- check if
			// the non-empty side has only QT entries
			final Map<?, ?> nonEmpty = !this.extraResults.isEmpty()
				? this.extraResults : that.extraResults;
			for (final Object key : nonEmpty.keySet()) {
				if (!key.equals(QueryTelemetry.class)) {
					return false;
				}
			}
			return true;
		}
		// both non-empty: compare excluding QT on both sides
		int thisNonQtCount = 0;
		for (final Class<? extends EvitaResponseExtraResult> key : this.extraResults.keySet()) {
			if (!key.equals(QueryTelemetry.class)) {
				thisNonQtCount++;
			}
		}
		int thatNonQtCount = 0;
		for (final Class<? extends EvitaResponseExtraResult> key : that.extraResults.keySet()) {
			if (!key.equals(QueryTelemetry.class)) {
				thatNonQtCount++;
			}
		}
		if (thisNonQtCount != thatNonQtCount) {
			return false;
		}
		for (final Map.Entry<Class<? extends EvitaResponseExtraResult>,
			EvitaResponseExtraResult> entry : this.extraResults.entrySet()) {
			if (!entry.getKey().equals(QueryTelemetry.class)) {
				final EvitaResponseExtraResult thatValue =
					that.extraResults.get(entry.getKey());
				if (!entry.getValue().equals(thatValue)) {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		return this.recordPage.hashCode();
	}

	@Override
	public String toString() {
		return "EvitaResponse:" +
			"\nsourceQuery:\n" + this.sourceQuery.prettyPrint() +
			"\nresult:\n" + this.recordPage +
			(this.extraResults.isEmpty() ? "" : "\nextraResults\n: " + this.extraResults);
	}

	/**
	 * Computes the statistics for input/output (I/O) fetch operations
	 * by iterating over the record data. The method aggregates both the
	 * count of I/O fetch operations and the total size of bytes fetched.
	 * These statistics are collected from records that implement the
	 * {@link EntityFetchAwareDecorator} interface. The computed values
	 * are then stored in the instance variables {@code ioFetchCount}
	 * and {@code ioFetchedSizeBytes}.
	 */
	private void computeIoFetchStats() {
		int ioFetchCount = 0;
		int ioFetchedSizeBytes = 0;
		for (final T record : getRecordData()) {
			if (record instanceof EntityFetchAwareDecorator efad) {
				ioFetchCount += efad.getIoFetchCount();
				ioFetchedSizeBytes += efad.getIoFetchedBytes();
			}
		}
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedSizeBytes = ioFetchedSizeBytes;
	}
}
