/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.dataType.DataChunk;
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Evita response contains all results to single query. Results are divided to two parts - main results returned by
 * {@link #getRecordPage()} and set of extra results retrieved by {@link #getExtraResult(Class)}.
 *
 * Evita tries to take advantage of all possible intermediate results to minimize computational costs so that there
 * could be a variety of extra results attached to the base response data.
 *
 * The idea behind it is as follows: client requires data A and B, for computing result A we need to compute data C.
 * This data C is necessary even for computing the result B, so we can reuse this intermediate result C if both A and B
 * results are queried and returned within the same query. If computation of A takes 73ms, B takes 62ms and both require
 * intermediate computation C that takes 42ms, then we could achieve result computation in (73-42 + 62-42) = 93ms
 * instead of (73+62) = 135ms that would take when there were two separates queries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract sealed class EvitaResponse<T extends EntityClassifier> permits EvitaEntityResponse, EvitaBinaryEntityResponse, EvitaEntityReferenceResponse {
	private final Query sourceQuery;
	private final DataChunk<T> recordPage;
	private final Map<Class<? extends EvitaResponseExtraResult>, EvitaResponseExtraResult> extraResults = new HashMap<>();

	protected EvitaResponse(@Nonnull Query sourceQuery, @Nonnull DataChunk<T> recordPage) {
		this.sourceQuery = sourceQuery;
		this.recordPage = recordPage;
	}

	protected EvitaResponse(@Nonnull Query sourceQuery,
	                        @Nonnull DataChunk<T> recordPage,
	                        @Nonnull EvitaResponseExtraResult... extraResults) {
		this.sourceQuery = sourceQuery;
		this.recordPage = recordPage;
		for (EvitaResponseExtraResult extraResult : extraResults) {
			addExtraResult(extraResult);
		}
	}

	/**
	 * Returns input query this response reacts to.
	 */
	@Nonnull
	public Query getSourceQuery() {
		return sourceQuery;
	}

	/**
	 * Returns page of records according to pagination rules in input query. If no pagination was defined in input
	 * query `page(1, 20)` is assumed.
	 */
	@Nonnull
	public DataChunk<T> getRecordPage() {
		return recordPage;
	}

	/**
	 * Returns slice of data that belongs to the requested page.
	 */
	@Nonnull
	public List<T> getRecordData() {
		return recordPage.getData();
	}

	/**
	 * Returns total count of available main records in entire result set (i.e. ignoring current pagination settings).
	 */
	public int getTotalRecordCount() {
		return recordPage.getTotalRecordCount();
	}

	/**
	 * Returns set of extra result types provided along with the base result.
	 */
	@Nonnull
	public Set<Class<? extends EvitaResponseExtraResult>> getExtraResultTypes() {
		return this.extraResults.keySet();
	}

	/**
	 * Adds extra result to be accompanied with standard record page.
	 */
	public void addExtraResult(@Nonnull EvitaResponseExtraResult extraResult) {
		this.extraResults.put(extraResult.getClass(), extraResult);
	}

	/**
	 * Returns extra result attached to the base data response of specified type. See documentation for this class.
	 */
	@Nullable
	public <S extends EvitaResponseExtraResult> S getExtraResult(Class<S> resultType) {
		final Object extraResult = this.extraResults.get(resultType);
		if (extraResult != null && !resultType.isInstance(extraResult)) {
			throw new EvitaInternalError("This should never happen!");
		}
		//noinspection unchecked
		return (S) extraResult;
	}

	/**
	 * Returns all attached extra results.
	 */
	@Nonnull
	public Map<Class<? extends EvitaResponseExtraResult>, EvitaResponseExtraResult> getExtraResults() {
		return Collections.unmodifiableMap(this.extraResults);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		EvitaResponse<?> that = (EvitaResponse<?>) o;
		return recordPage.equals(that.recordPage) &&
			extraResults.size() == ((EvitaResponse<?>) o).extraResults.size() &&
			extraResults.entrySet()
				.stream()
				.filter(it -> !(it instanceof QueryTelemetry))
				.allMatch(it -> it.getValue().equals(((EvitaResponse<?>) o).extraResults.get(it.getKey())));
	}

	@Override
	public int hashCode() {
		return Objects.hash(recordPage);
	}

	@Override
	public String toString() {
		return "EvitaResponse:" +
			"\nsourceQuery:\n" + sourceQuery.prettyPrint() +
			"\nresult:\n" + recordPage +
			(extraResults.isEmpty() ? "" : "\nextraResults\n: " + extraResults);
	}
}
