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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.UUID;

/**
 * Exception thrown when a catalog change capture cannot continue from the position the consumer asked it to resume
 * from. A resume position - {@link ChangeCatalogCaptureRequest#catalogId()},
 * {@link ChangeCatalogCaptureRequest#sinceVersion()} and {@link ChangeCatalogCaptureRequest#sinceIndex()} - points
 * into the version sequence of one **incarnation** of a catalog. The {@link Reason} says why the position no longer
 * points anywhere the catalog can serve.
 *
 * Whatever the reason, the consumer cannot continue where it left off: the changes between its position and the
 * present are either gone or belong to a different dataset that merely carries the same name. A consumer holding
 * state derived from the stream (a cache, a projection) must drop it together with the stored position, rebuild it
 * from the current data and subscribe again from the head of the stream - without `sinceVersion` - expecting
 * {@link #getCatalogId()}. Silently rewinding the position instead would hide the gap the derived state now has.
 * The captures of a stream carry the identity of the incarnation they come from in
 * {@link ChangeCatalogCapture#catalogId()} - the value to store alongside the position and to pass back in
 * {@link ChangeCatalogCaptureRequest#catalogId()} on resumption.
 *
 * The exception extends {@link TemporalDataNotAvailableException}, so a handler written for a position outside
 * the write-ahead log retention catches every reason, and {@link #getCatalogVersion()} keeps its meaning there:
 * the oldest catalog version still available. The version the catalog is at right now is
 * {@link #getCurrentCatalogVersion()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ChangeCaptureResumePositionInvalidException extends TemporalDataNotAvailableException {
	@Serial private static final long serialVersionUID = 5747795486490307826L;

	/**
	 * Why the resume position cannot be served.
	 */
	@Getter @Nonnull private final Reason reason;
	/**
	 * Identity of the catalog incarnation the position was checked against - the incarnation a renewed
	 * subscription will be served from.
	 */
	@Getter @Nonnull private final UUID catalogId;
	/**
	 * The catalog version the incarnation was at when the position was checked.
	 */
	@Getter private final long currentCatalogVersion;
	/**
	 * The catalog identity the consumer expected, or `null` when it did not state one.
	 */
	@Getter @Nullable private final UUID requestedCatalogId;
	/**
	 * The catalog version the consumer asked to resume from, or `null` when it did not state one.
	 */
	@Getter @Nullable private final Long requestedSinceVersion;
	/**
	 * The index within {@link #requestedSinceVersion} the consumer asked to resume from, or `null` when it did not
	 * state one.
	 */
	@Getter @Nullable private final Integer requestedSinceIndex;

	/**
	 * Creates a new exception for a resume position that was refused before anything was read.
	 *
	 * @param reason                        why the position cannot be served
	 * @param catalogId                     identity of the catalog incarnation the position was checked against
	 * @param currentCatalogVersion         the catalog version that incarnation is at
	 * @param oldestAvailableCatalogVersion the oldest catalog version still available, or `null` when it could not
	 *                                      be determined - reported as {@link #getCatalogVersion()}
	 * @param requestedCatalogId            the catalog identity the consumer expected, if any
	 * @param requestedSinceVersion         the catalog version the consumer asked to resume from, if any
	 * @param requestedSinceIndex           the index within that version the consumer asked to resume from, if any
	 */
	public ChangeCaptureResumePositionInvalidException(
		@Nonnull Reason reason,
		@Nonnull UUID catalogId,
		long currentCatalogVersion,
		@Nullable Long oldestAvailableCatalogVersion,
		@Nullable UUID requestedCatalogId,
		@Nullable Long requestedSinceVersion,
		@Nullable Integer requestedSinceIndex
	) {
		super(
			composeMessage(
				reason, catalogId, currentCatalogVersion, oldestAvailableCatalogVersion,
				requestedCatalogId, requestedSinceVersion, requestedSinceIndex
			),
			oldestAvailableCatalogVersion
		);
		this.reason = reason;
		this.catalogId = catalogId;
		this.currentCatalogVersion = currentCatalogVersion;
		this.requestedCatalogId = requestedCatalogId;
		this.requestedSinceVersion = requestedSinceVersion;
		this.requestedSinceIndex = requestedSinceIndex;
	}

	/**
	 * Creates a new exception for a resume position recognized as unserviceable only after a read of the change
	 * stream had already failed - the failure is kept as the cause, with whatever it carries as suppressed.
	 *
	 * @param reason                        why the position cannot be served
	 * @param catalogId                     identity of the catalog incarnation the position was checked against
	 * @param currentCatalogVersion         the catalog version that incarnation is at
	 * @param oldestAvailableCatalogVersion the oldest catalog version still available, or `null` when it could not
	 *                                      be determined - reported as {@link #getCatalogVersion()}
	 * @param requestedCatalogId            the catalog identity the consumer expected, if any
	 * @param requestedSinceVersion         the catalog version the consumer asked to resume from, if any
	 * @param requestedSinceIndex           the index within that version the consumer asked to resume from, if any
	 * @param cause                         the failure of the read that ran into the unserviceable position
	 */
	public ChangeCaptureResumePositionInvalidException(
		@Nonnull Reason reason,
		@Nonnull UUID catalogId,
		long currentCatalogVersion,
		@Nullable Long oldestAvailableCatalogVersion,
		@Nullable UUID requestedCatalogId,
		@Nullable Long requestedSinceVersion,
		@Nullable Integer requestedSinceIndex,
		@Nonnull Throwable cause
	) {
		super(
			composeMessage(
				reason, catalogId, currentCatalogVersion, oldestAvailableCatalogVersion,
				requestedCatalogId, requestedSinceVersion, requestedSinceIndex
			),
			oldestAvailableCatalogVersion,
			cause
		);
		this.reason = reason;
		this.catalogId = catalogId;
		this.currentCatalogVersion = currentCatalogVersion;
		this.requestedCatalogId = requestedCatalogId;
		this.requestedSinceVersion = requestedSinceVersion;
		this.requestedSinceIndex = requestedSinceIndex;
	}

	/**
	 * Composes the message from the fields alone, so that an exception rebuilt from them on the client side reads
	 * the same as the one raised by the server.
	 *
	 * @param reason                        why the position cannot be served
	 * @param catalogId                     identity of the catalog incarnation the position was checked against
	 * @param currentCatalogVersion         the catalog version that incarnation is at
	 * @param oldestAvailableCatalogVersion the oldest catalog version still available, or `null` when unknown
	 * @param requestedCatalogId            the catalog identity the consumer expected, if any
	 * @param requestedSinceVersion         the catalog version the consumer asked to resume from, if any
	 * @param requestedSinceIndex           the index within that version the consumer asked to resume from, if any
	 * @return the message
	 */
	@Nonnull
	private static String composeMessage(
		@Nonnull Reason reason,
		@Nonnull UUID catalogId,
		long currentCatalogVersion,
		@Nullable Long oldestAvailableCatalogVersion,
		@Nullable UUID requestedCatalogId,
		@Nullable Long requestedSinceVersion,
		@Nullable Integer requestedSinceIndex
	) {
		final StringBuilder message = new StringBuilder(640);
		message.append("The change capture resume position (");
		if (requestedCatalogId != null) {
			message.append("catalogId=").append(requestedCatalogId).append(", ");
		}
		message.append("sinceVersion=").append(requestedSinceVersion == null ? "not set" : requestedSinceVersion);
		if (requestedSinceIndex != null) {
			message.append(", sinceIndex=").append(requestedSinceIndex);
		}
		message.append(") ");
		// a switch expression, so that a reason added later cannot slip through without an explanation
		final String explanation = switch (reason) {
			case DIFFERENT_INCARNATION -> "was recorded on a different incarnation of the catalog. The catalog has " +
				"been replaced, restored or duplicated since, and is now `" + catalogId + "` at version " +
				currentCatalogVersion + ". Versions of one incarnation mean nothing in another.";
			case AHEAD_OF_CATALOG -> "lies ahead of catalog `" + catalogId + "`, which is at version " +
				currentCatalogVersion + " - a subscription can start at version " + (currentCatalogVersion + 1) +
				" at the latest. Such a position cannot have been recorded on this incarnation of the catalog; it " +
				"most likely belongs to one that has been replaced since.";
			case OUTSIDE_RETENTION -> "is no longer retained by the write-ahead log of catalog `" + catalogId +
				"`, which is at version " + currentCatalogVersion +
				(oldestAvailableCatalogVersion == null ?
					"" : " - the oldest catalog version still available is " + oldestAvailableCatalogVersion) +
				". The changes in between are gone.";
		};
		message.append(explanation);
		message
			.append(" Drop every state derived from the change stream together with the stored resume position, ")
			.append("rebuild it from the current data and subscribe again from the head of the stream (without ")
			.append("`sinceVersion`), expecting catalog id `").append(catalogId).append("`.");
		return message.toString();
	}

	/**
	 * The reasons a resume position can be refused for.
	 */
	public enum Reason {

		/**
		 * The position carries a catalog identity different from the catalog the session is bound to - the catalog
		 * of that name has been replaced, restored or duplicated since the position was recorded. Its versions
		 * belong to another lineage, regardless of whether they happen to be lower or higher than the current ones.
		 */
		DIFFERENT_INCARNATION,
		/**
		 * The position lies more than one version ahead of the catalog. A position recorded on this incarnation can
		 * be at most the version right after the current one (the version the next change will carry), so this one
		 * was recorded elsewhere - typically on an incarnation that has been replaced since, when the consumer did
		 * not state the catalog identity it expected.
		 */
		AHEAD_OF_CATALOG,
		/**
		 * The write-ahead log retention has removed the changes the position points at. The position belongs to
		 * this incarnation, but the consumer fell too far behind and the changes it missed cannot be replayed.
		 */
		OUTSIDE_RETENTION

	}

}
