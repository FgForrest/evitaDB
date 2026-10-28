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

package io.evitadb.core.cdc;


import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Checks the resume position of a {@link ChangeCatalogCaptureRequest} against the catalog incarnation it is about
 * to be served from, before anything is read.
 *
 * A position that does not belong to the incarnation used to be accepted and served as an empty stream: a checkpoint
 * recorded before `replaceCatalog` pointed into a version sequence the new incarnation reaches only days later, and
 * the subscription stayed open, healthy and silent until then. Both checks here exist to turn that into an immediate
 * {@link ChangeCaptureResumePositionInvalidException}:
 *
 * - **identity** - a position stating the catalog identity it was recorded on is refused when the incarnation is a
 *   different one, whatever its versions are; this is the check that cannot be fooled by a new incarnation that
 *   happens to have reached a similar version
 * - **ahead of the catalog** - a subscription position more than one version past the live catalog cannot have been
 *   recorded on this incarnation, with or without an identity; the history reads do not apply it, because they
 *   document a future version as yielding an empty stream
 *
 * The caller passes the identity of the incarnation the position is judged against - the catalog instance a session
 * is bound to for the history reads, the incarnation a transaction manager serves for a subscription - so the check is
 * atomic with respect to a concurrent replacement: neither ever changes the incarnation it belongs to.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ResumePositionValidator {

	private ResumePositionValidator() {
		// static helper
	}

	/**
	 * Verifies that the position of a request for the history of the catalog was recorded on the incarnation the
	 * history is read from. A request without an expected identity passes.
	 *
	 * @param request                the request whose position is checked
	 * @param catalogId              identity of the catalog incarnation the history is read from
	 * @param catalogVersion         the version of that incarnation the history is read at
	 * @param firstReplayableVersion looks up the first version the incarnation's WAL can still replay, `-1` when it
	 *                               never lost a file - consulted only to describe a refusal
	 * @throws ChangeCaptureResumePositionInvalidException with {@link Reason#DIFFERENT_INCARNATION} when the request
	 *                                                     expects another incarnation
	 */
	public static void assertSameIncarnation(
		@Nonnull ChangeCatalogCaptureRequest request,
		@Nonnull UUID catalogId,
		long catalogVersion,
		@Nonnull LongSupplier firstReplayableVersion
	) {
		final UUID expectedCatalogId = request.catalogId();
		if (expectedCatalogId != null && !expectedCatalogId.equals(catalogId)) {
			throw refuse(Reason.DIFFERENT_INCARNATION, request, catalogId, catalogVersion, firstReplayableVersion);
		}
	}

	/**
	 * Verifies that the position a new subscription asks to start at can be served by the incarnation it is being
	 * registered with: it must have been recorded on this incarnation if the request says which one it was recorded
	 * on, and it must not lie more than one version ahead of the live catalog in any case. Exactly one version ahead
	 * is the version the next change will carry - the default start of a subscription - and is accepted.
	 *
	 * The live version must be the last version the incarnation has **finalized**, not the version of any catalog
	 * instance a reader holds. Finalization precedes every way a version becomes visible: a session bound to an older
	 * snapshot, and the change observer that is told about a new version only after it has been published to new
	 * sessions, may both still describe the version before - while a consumer has already learnt the newer one from a
	 * fresh session and legitimately asks for the version after it. Judged by those lagging views, such a position
	 * would be refused as ahead of the catalog.
	 *
	 * @param request                 the request whose position is checked
	 * @param catalogId               identity of the catalog incarnation the subscription is registered with
	 * @param finalizedCatalogVersion the last version that incarnation has finalized
	 * @param firstReplayableVersion  looks up the first version the incarnation's WAL can still replay, `-1` when it
	 *                                never lost a file - consulted only to describe a refusal
	 * @throws ChangeCaptureResumePositionInvalidException with {@link Reason#DIFFERENT_INCARNATION} when the request
	 *                                                     expects another incarnation, or with
	 *                                                     {@link Reason#AHEAD_OF_CATALOG} when its version lies more
	 *                                                     than one version past the live catalog
	 */
	public static void assertSubscriptionPosition(
		@Nonnull ChangeCatalogCaptureRequest request,
		@Nonnull UUID catalogId,
		long finalizedCatalogVersion,
		@Nonnull LongSupplier firstReplayableVersion
	) {
		assertSameIncarnation(request, catalogId, finalizedCatalogVersion, firstReplayableVersion);
		final Long sinceVersion = request.sinceVersion();
		if (sinceVersion != null && sinceVersion > finalizedCatalogVersion + 1) {
			throw refuse(Reason.AHEAD_OF_CATALOG, request, catalogId, finalizedCatalogVersion, firstReplayableVersion);
		}
	}

	/**
	 * Creates the refusal of the request's position. The oldest version still available is looked up only now, on
	 * the refusal path, because it lists the WAL folder. A failure of that lookup must not replace the refusal - the
	 * refusal is what the consumer has to act on - so it is attached as suppressed and the oldest version is left
	 * unknown.
	 *
	 * @param reason                 why the position is refused
	 * @param request                the request whose position is refused
	 * @param catalogId              identity of the catalog incarnation the position was checked against
	 * @param catalogVersion         the live version of that incarnation
	 * @param firstReplayableVersion looks up the first version the incarnation's WAL can still replay
	 * @return the exception to throw
	 */
	@Nonnull
	private static ChangeCaptureResumePositionInvalidException refuse(
		@Nonnull Reason reason,
		@Nonnull ChangeCatalogCaptureRequest request,
		@Nonnull UUID catalogId,
		long catalogVersion,
		@Nonnull LongSupplier firstReplayableVersion
	) {
		Long oldestAvailableVersion;
		RuntimeException lookupFailure = null;
		try {
			oldestAvailableVersion = toOldestAvailableVersion(firstReplayableVersion.getAsLong());
		} catch (RuntimeException ex) {
			oldestAvailableVersion = null;
			lookupFailure = ex;
		}
		final ChangeCaptureResumePositionInvalidException refusal = new ChangeCaptureResumePositionInvalidException(
			reason, catalogId, catalogVersion, oldestAvailableVersion,
			request.catalogId(), request.sinceVersion(), request.sinceIndex()
		);
		if (lookupFailure != null) {
			refusal.addSuppressed(lookupFailure);
		}
		return refusal;
	}

	/**
	 * Translates the first replayable version of a WAL into the oldest version a stream can still resume at. A WAL
	 * that has never lost a file answers `-1`: the versions below its first transaction belong to the warm-up phase
	 * and were never transactions, so a stream can resume anywhere from version zero without missing anything.
	 *
	 * @param firstReplayableVersion the first version the WAL can still replay, `-1` when it never lost a file
	 * @return the oldest version a stream can resume at
	 */
	private static long toOldestAvailableVersion(long firstReplayableVersion) {
		return Math.max(firstReplayableVersion, 0L);
	}

}
