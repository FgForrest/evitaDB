/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.requestResponse.trafficRecording;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Traffic recording capture request is used to specify the criteria for retrieving contents from the traffic recording
 * log.
 *
 * @param content                  determines whether only basic information about the traffic recording is returned or the actual content
 * @param since                    specifies the time from which the traffic recording should be returned
 * @param sinceSessionSequenceId   specifies the session sequence ID from which the traffic recording should be returned
 * @param sinceRecordSessionOffset specifies the record session offset from which the traffic recording should be returned
 *                                 (the offset is relative to the session sequence ID and starts from 0), offset allows
 *                                 to continue fetching the traffic recording from the last fetched record when session
 *                                 was not fully fetched
 * @param types                    specifies the types of traffic recording to be returned
 * @param sessionId                specifies the session ID from which the traffic recording should be returned
 * @param longerThan               specifies the minimum duration of the traffic recording to be returned
 * @param fetchingMoreBytesThan    specifies the minimum number of bytes that the traffic recording should contain
 * @param labels                   specifies the labels of traffic recording to be returned (both label name and value must match)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record TrafficRecordingCaptureRequest(
	@Nonnull TrafficRecordingContent content,
	@Nullable OffsetDateTime since,
	@Nullable Long sinceSessionSequenceId,
	@Nullable Integer sinceRecordSessionOffset,
	@Nullable TrafficRecordingType[] types,
	@Nullable UUID sessionId,
	@Nullable Duration longerThan,
	@Nullable Integer fetchingMoreBytesThan,
	@Nullable Label[] labels
) {

	/**
	 * Builder for the storage options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static TrafficRecordingCaptureRequest.Builder builder() {
		return new TrafficRecordingCaptureRequest.Builder();
	}

	/**
	 * Builder for the storage options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static TrafficRecordingCaptureRequest.Builder builder(@Nonnull TrafficRecordingCaptureRequest request) {
		return new TrafficRecordingCaptureRequest.Builder(request);
	}

	/**
	 * List of all possible traffic recording types.
	 */
	public enum TrafficRecordingType {
		/**
		 * evitaDB session opened.
		 */
		SESSION_START,
		/**
		 * evitaDB session closed.
		 */
		SESSION_CLOSE,
		/**
		 * Query received via. API from the client - container contains original string of the client query.
		 * API might call multiple queries related to the same source query.
		 */
		SOURCE_QUERY,
		/**
		 * Query received via. API from the client is finalized and sent to the client. Container contains the final
		 * statistics aggregated over all operations related to the source query.
		 */
		SOURCE_QUERY_STATISTICS,
		/**
		 * Internal evitaDB query (evitaQL) was executed.
		 */
		QUERY,
		/**
		 * Internal call to retrieve single evitaDB entity. Record is not created for entities fetched as a part of
		 * a query.
		 */
		FETCH,
		/**
		 * Internal call to enrich contents of the evitaDB entity.
		 */
		ENRICHMENT,
		/**
		 * Internal call to mutate the evitaDB entity or catalog schema.
		 */
		MUTATION
	}

	/**
	 * Builder for {@link TrafficRecordingCaptureRequest}. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static class Builder {
		@Nonnull
		private TrafficRecordingContent content = TrafficRecordingContent.HEADER;
		@Nullable
		private OffsetDateTime since;
		@Nullable
		private Long sinceSessionSequenceId;
		@Nullable
		private Integer sinceRecordSessionOffset;
		@Nullable
		private TrafficRecordingCaptureRequest.TrafficRecordingType[] types;
		@Nullable
		private Label[] labels;
		@Nullable
		private UUID sessionId;
		@Nullable
		private Duration longerThan;
		@Nullable
		private Integer fetchingMoreBytesThan;

		/**
		 * Default constructor for the builder.
		 */
		public Builder() {
		}

		/**
		 * Constructor for initializing the builder with an existing {@link TrafficRecordingCaptureRequest} instance.
		 *
		 * @param request the existing {@link TrafficRecordingCaptureRequest} instance.
		 */
		public Builder(@Nonnull TrafficRecordingCaptureRequest request) {
			this.content = request.content();
			this.since = request.since();
			this.sinceSessionSequenceId = request.sinceSessionSequenceId();
			this.sinceRecordSessionOffset = request.sinceRecordSessionOffset();
			this.types = request.types();
			this.sessionId = request.sessionId();
			this.longerThan = request.longerThan();
			this.fetchingMoreBytesThan = request.fetchingMoreBytesThan();
		}

		/**
		 * Sets the {@link TrafficRecordingContent} for the request.
		 *
		 * @param content the traffic recording content.
		 * @return this builder.
		 */
		@Nonnull
		public Builder content(@Nonnull TrafficRecordingContent content) {
			this.content = content;
			return this;
		}

		/**
		 * Sets the starting time from which the traffic recording should be returned.
		 *
		 * @param since the starting {@link OffsetDateTime}.
		 * @return this builder.
		 */
		@Nonnull
		public Builder since(@Nullable OffsetDateTime since) {
			this.since = since;
			return this;
		}

		/**
		 * Sets the session sequence ID from which the traffic recording should be returned.
		 *
		 * @param sinceSessionSequenceId the session sequence ID.
		 * @return this builder.
		 */
		@Nonnull
		public Builder sinceSessionSequenceId(@Nullable Long sinceSessionSequenceId) {
			this.sinceSessionSequenceId = sinceSessionSequenceId;
			return this;
		}

		/**
		 * Sets the record session offset relative to the session sequence ID.
		 *
		 * @param sinceRecordSessionOffset the record session offset.
		 * @return this builder.
		 */
		@Nonnull
		public Builder sinceRecordSessionOffset(@Nullable Integer sinceRecordSessionOffset) {
			this.sinceRecordSessionOffset = sinceRecordSessionOffset;
			return this;
		}

		/**
		 * Sets the types of traffic recording to be returned.
		 *
		 * @param type the array of {@link TrafficRecordingCaptureRequest.TrafficRecordingType}.
		 * @return this builder.
		 */
		@Nonnull
		public Builder type(@Nonnull TrafficRecordingCaptureRequest.TrafficRecordingType... type) {
			this.types = type;
			return this;
		}

		/**
		 * Sets the labels of traffic recording to be returned.
		 *
		 * @param labels the array of {@link Label}.
		 * @return this builder.
		 */
		@Nonnull
		public Builder labels(@Nonnull Label... labels) {
			this.labels = labels;
			return this;
		}

		/**
		 * Sets the session ID from which the traffic recording should be returned.
		 *
		 * @param sessionId the session ID.
		 * @return this builder.
		 */
		@Nonnull
		public Builder sessionId(@Nullable UUID sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		/**
		 * Sets the minimum duration of the traffic recording to be returned.
		 *
		 * @param longerThan the minimum duration.
		 * @return this builder.
		 */
		@Nonnull
		public Builder longerThan(@Nullable Duration longerThan) {
			this.longerThan = longerThan;
			return this;
		}

		/**
		 * Sets the minimum number of bytes that the traffic recording should contain.
		 *
		 * @param fetchingMoreBytesThan the minimum byte count.
		 * @return this builder.
		 */
		@Nonnull
		public Builder fetchingMoreBytesThan(@Nullable Integer fetchingMoreBytesThan) {
			this.fetchingMoreBytesThan = fetchingMoreBytesThan;
			return this;
		}

		/**
		 * Builds the {@link TrafficRecordingCaptureRequest} instance.
		 *
		 * @return a new {@link TrafficRecordingCaptureRequest} instance.
		 */
		@Nonnull
		public TrafficRecordingCaptureRequest build() {
			return new TrafficRecordingCaptureRequest(
				this.content,
				this.since,
				this.sinceSessionSequenceId,
				this.sinceRecordSessionOffset,
				this.types,
				this.sessionId,
				this.longerThan,
				this.fetchingMoreBytesThan,
				this.labels
			);
		}
	}

}