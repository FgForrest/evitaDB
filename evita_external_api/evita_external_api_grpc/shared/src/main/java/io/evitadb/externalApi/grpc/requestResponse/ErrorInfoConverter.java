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

package io.evitadb.externalApi.grpc.requestResponse;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.grpc.Status.Code;
import io.grpc.protobuf.StatusProto;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Carries the fields of selected evitaDB exceptions across the gRPC boundary as the metadata of the
 * {@link ErrorInfo} detail the server attaches to every error status, so that the client can rebuild the very
 * exception the server raised instead of a generic one.
 *
 * A generic {@link EvitaInvalidUsageException} carries only an error code and a message, which is enough for a
 * human but not for a consumer that has to *react* - a change capture consumer whose resume position was refused
 * needs the reason and the identity of the catalog to subscribe again, and it can only branch on them if they
 * arrive as fields of the exception it catches rather than as words of a message.
 *
 * The set of exceptions is deliberately closed: only types whose fields are encoded here can be rebuilt, and the
 * rebuild happens only when the metadata names one of them in {@link #EXCEPTION_CLASS} and every required field
 * is present and well-formed. Everything else - another exception type, a status from a server that predates the
 * encoding, a status raised by the transport itself, metadata that does not parse - yields `null`, and the caller
 * keeps the handling it had before. The rebuilt exception composes its message from the fields alone, exactly
 * like the original did on the server, so the two read the same.
 *
 * The metadata keys are part of the wire contract between server and client versions and must not be renamed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ErrorInfoConverter {
	/**
	 * Fully qualified name of the exception class the metadata describes. Its presence is what tells the client
	 * the server encoded the fields of that exception - an older server sends the {@link ErrorInfo} without it.
	 */
	public static final String EXCEPTION_CLASS = "exceptionClass";
	/**
	 * {@link ChangeCaptureResumePositionInvalidException#getReason()} - the name of the reason constant.
	 */
	public static final String REASON = "reason";
	/**
	 * {@link ChangeCaptureResumePositionInvalidException#getCatalogId()} - the catalog identity as a UUID string.
	 */
	public static final String CATALOG_ID = "catalogId";
	/**
	 * {@link ChangeCaptureResumePositionInvalidException#getCurrentCatalogVersion()}.
	 */
	public static final String CURRENT_CATALOG_VERSION = "currentCatalogVersion";
	/**
	 * {@link ChangeCaptureResumePositionInvalidException#getCatalogVersion()} - the oldest catalog version still
	 * available, absent when it could not be determined.
	 */
	public static final String OLDEST_AVAILABLE_CATALOG_VERSION = "oldestAvailableCatalogVersion";
	/**
	 * {@link ChangeCaptureResumePositionInvalidException#getRequestedCatalogId()}, absent when not requested.
	 */
	public static final String REQUESTED_CATALOG_ID = "requestedCatalogId";
	/**
	 * {@link ChangeCaptureResumePositionInvalidException#getRequestedSinceVersion()}, absent when not requested.
	 */
	public static final String REQUESTED_SINCE_VERSION = "requestedSinceVersion";
	/**
	 * {@link ChangeCaptureResumePositionInvalidException#getRequestedSinceIndex()}, absent when not requested.
	 */
	public static final String REQUESTED_SINCE_INDEX = "requestedSinceIndex";
	/**
	 * {@link TemporalDataNotAvailableException#getCatalogVersion()} - the oldest available catalog version, absent
	 * when the exception reports a moment or nothing at all.
	 */
	public static final String CATALOG_VERSION = "catalogVersion";
	/**
	 * {@link TemporalDataNotAvailableException#getOffsetDateTime()} - the oldest available moment in ISO-8601 form,
	 * absent when the exception reports a version or nothing at all.
	 */
	public static final String OFFSET_DATE_TIME = "offsetDateTime";

	private ErrorInfoConverter() {
		// static helper
	}

	/**
	 * Returns the metadata describing the fields of the passed exception, or an empty map when the exception is not
	 * one of the types the client can rebuild. The server adds the result to the {@link ErrorInfo} of the status it
	 * sends.
	 *
	 * @param exception the exception to describe
	 * @return the metadata, never `null`
	 */
	@Nonnull
	public static Map<String, String> toErrorInfoMetadata(@Nonnull EvitaInvalidUsageException exception) {
		if (exception instanceof ChangeCaptureResumePositionInvalidException resumePositionInvalid) {
			final Map<String, String> metadata = new HashMap<>(16);
			metadata.put(EXCEPTION_CLASS, ChangeCaptureResumePositionInvalidException.class.getName());
			metadata.put(REASON, resumePositionInvalid.getReason().name());
			metadata.put(CATALOG_ID, resumePositionInvalid.getCatalogId().toString());
			metadata.put(CURRENT_CATALOG_VERSION, String.valueOf(resumePositionInvalid.getCurrentCatalogVersion()));
			putIfPresent(metadata, OLDEST_AVAILABLE_CATALOG_VERSION, resumePositionInvalid.getCatalogVersion());
			putIfPresent(metadata, REQUESTED_CATALOG_ID, resumePositionInvalid.getRequestedCatalogId());
			putIfPresent(metadata, REQUESTED_SINCE_VERSION, resumePositionInvalid.getRequestedSinceVersion());
			putIfPresent(metadata, REQUESTED_SINCE_INDEX, resumePositionInvalid.getRequestedSinceIndex());
			return metadata;
		} else if (exception.getClass() == TemporalDataNotAvailableException.class) {
			// the exact class only - a subtype unknown to this converter would be rebuilt as its parent and lose
			// whatever it adds, so it travels as a generic exception instead
			final TemporalDataNotAvailableException temporalDataNotAvailable = (TemporalDataNotAvailableException) exception;
			final Map<String, String> metadata = new HashMap<>(4);
			metadata.put(EXCEPTION_CLASS, TemporalDataNotAvailableException.class.getName());
			putIfPresent(metadata, CATALOG_VERSION, temporalDataNotAvailable.getCatalogVersion());
			putIfPresent(metadata, OFFSET_DATE_TIME, temporalDataNotAvailable.getOffsetDateTime());
			return metadata;
		} else {
			return Collections.emptyMap();
		}
	}

	/**
	 * Rebuilds the evitaDB exception the server described in the {@link ErrorInfo} detail of the status carried
	 * by the passed throwable (or by any throwable in its cause chain).
	 *
	 * @param throwable the failure of a gRPC call, typically a `StatusRuntimeException`
	 * @return the rebuilt exception, or `null` when the status is absent, is not `INVALID_ARGUMENT`, carries no
	 *         {@link ErrorInfo}, or its metadata does not describe a recognised exception with well-formed fields
	 */
	@Nullable
	public static EvitaInvalidUsageException toTypedException(@Nonnull Throwable throwable) {
		final com.google.rpc.Status status = StatusProto.fromThrowable(throwable);
		// every recognised type is a client error - any other code was produced by something else, most likely
		// the transport, and must keep the handling the caller has for it
		if (status == null || status.getCode() != Code.INVALID_ARGUMENT.value()) {
			return null;
		}
		for (Any detail : status.getDetailsList()) {
			if (detail.is(ErrorInfo.class)) {
				try {
					return toTypedException(detail.unpack(ErrorInfo.class).getMetadataMap());
				} catch (InvalidProtocolBufferException ex) {
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Rebuilds the exception described by the passed {@link ErrorInfo} metadata.
	 *
	 * @param metadata the metadata produced by {@link #toErrorInfoMetadata(EvitaInvalidUsageException)}
	 * @return the rebuilt exception, or `null` when the metadata does not describe a recognised exception with
	 *         well-formed fields
	 */
	@Nullable
	static EvitaInvalidUsageException toTypedException(@Nonnull Map<String, String> metadata) {
		final String exceptionClass = metadata.get(EXCEPTION_CLASS);
		try {
			if (ChangeCaptureResumePositionInvalidException.class.getName().equals(exceptionClass)) {
				return toResumePositionInvalidException(metadata);
			} else if (TemporalDataNotAvailableException.class.getName().equals(exceptionClass)) {
				return toTemporalDataNotAvailableException(metadata);
			} else {
				return null;
			}
		} catch (IllegalArgumentException | DateTimeException ex) {
			// a value that does not parse - `NumberFormatException`, a malformed UUID or an unknown reason constant
			// are all `IllegalArgumentException`s; a half-rebuilt exception would misinform more than none
			return null;
		}
	}

	/**
	 * Rebuilds {@link ChangeCaptureResumePositionInvalidException} from the metadata.
	 *
	 * @param metadata the metadata naming the exception
	 * @return the rebuilt exception, or `null` when a required field is missing
	 * @throws IllegalArgumentException when a field does not parse
	 */
	@Nullable
	private static ChangeCaptureResumePositionInvalidException toResumePositionInvalidException(
		@Nonnull Map<String, String> metadata
	) {
		final String reason = metadata.get(REASON);
		final String catalogId = metadata.get(CATALOG_ID);
		final String currentCatalogVersion = metadata.get(CURRENT_CATALOG_VERSION);
		if (reason == null || catalogId == null || currentCatalogVersion == null) {
			return null;
		}
		final String oldestAvailableCatalogVersion = metadata.get(OLDEST_AVAILABLE_CATALOG_VERSION);
		final String requestedCatalogId = metadata.get(REQUESTED_CATALOG_ID);
		final String requestedSinceVersion = metadata.get(REQUESTED_SINCE_VERSION);
		final String requestedSinceIndex = metadata.get(REQUESTED_SINCE_INDEX);
		return new ChangeCaptureResumePositionInvalidException(
			Reason.valueOf(reason),
			UUID.fromString(catalogId),
			Long.parseLong(currentCatalogVersion),
			oldestAvailableCatalogVersion == null ? null : Long.valueOf(oldestAvailableCatalogVersion),
			requestedCatalogId == null ? null : UUID.fromString(requestedCatalogId),
			requestedSinceVersion == null ? null : Long.valueOf(requestedSinceVersion),
			requestedSinceIndex == null ? null : Integer.valueOf(requestedSinceIndex)
		);
	}

	/**
	 * Rebuilds {@link TemporalDataNotAvailableException} from the metadata - the variant reporting the oldest
	 * catalog version, the oldest moment, or neither, depending on which field is present.
	 *
	 * @param metadata the metadata naming the exception
	 * @return the rebuilt exception, or `null` when both alternative fields are present
	 * @throws IllegalArgumentException when a field does not parse
	 * @throws DateTimeException        when the moment does not parse
	 */
	@Nullable
	private static TemporalDataNotAvailableException toTemporalDataNotAvailableException(
		@Nonnull Map<String, String> metadata
	) {
		final String catalogVersion = metadata.get(CATALOG_VERSION);
		final String offsetDateTime = metadata.get(OFFSET_DATE_TIME);
		if (catalogVersion != null && offsetDateTime != null) {
			// no constructor accepts both - the server cannot have produced this
			return null;
		} else if (catalogVersion != null) {
			return new TemporalDataNotAvailableException(Long.parseLong(catalogVersion));
		} else if (offsetDateTime != null) {
			return new TemporalDataNotAvailableException(OffsetDateTime.parse(offsetDateTime));
		} else {
			return new TemporalDataNotAvailableException();
		}
	}

	/**
	 * Puts the string form of the value into the metadata unless the value is `null` - an absent key is how
	 * the metadata says "not set".
	 *
	 * @param metadata the metadata to fill
	 * @param key      the key to put the value under
	 * @param value    the value, may be `null`
	 */
	private static void putIfPresent(@Nonnull Map<String, String> metadata, @Nonnull String key, @Nullable Object value) {
		if (value != null) {
			metadata.put(key, value.toString());
		}
	}

}
