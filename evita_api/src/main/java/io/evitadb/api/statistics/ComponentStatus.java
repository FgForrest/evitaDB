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

package io.evitadb.api.statistics;

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Outcome of one requested {@link CatalogStatisticsComponent}, telling the client whether the matching sub-message of
 * {@link CatalogStatistics} is present because it was computed, or absent because it could not be.
 *
 * Only requested components get a status. A component the client did not ask for is simply not in
 * {@link CatalogStatistics#componentStatus()}.
 *
 * @param component    the component this status describes
 * @param availability whether the component was delivered, and if not, the class of reason
 * @param reason       human-readable explanation, present whenever `availability` is not
 *                     {@link ComponentAvailability#DELIVERED}; never a substitute for the machine-readable
 *                     `availability`
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ComponentStatus(
	@Nonnull CatalogStatisticsComponent component,
	@Nonnull ComponentAvailability availability,
	@Nullable String reason
) {

	/**
	 * Enforces the binding between `availability` and `reason` that the record's contract promises, so that no route
	 * into this type can produce the one status the component model exists to rule out: an unavailability with no
	 * explanation. The factories below guard their own misuse, but they are not the only door - the gRPC decoder
	 * builds statuses from whatever a peer sent, and a peer is not bound by this class's rules.
	 */
	public ComponentStatus {
		if (availability == ComponentAvailability.DELIVERED) {
			if (reason != null) {
				throw new GenericEvitaInternalError(
					"A delivered component carries no reason - `" + component + "` was given one."
				);
			}
		} else if (reason == null) {
			throw new GenericEvitaInternalError(
				"An undelivered component must explain itself - `" + component + "` (" + availability +
					") carries no reason."
			);
		}
	}

	/**
	 * Creates a status for a component that was successfully computed.
	 *
	 * @param component the component that was delivered
	 * @return status with {@link ComponentAvailability#DELIVERED} and no reason
	 */
	@Nonnull
	public static ComponentStatus delivered(@Nonnull CatalogStatisticsComponent component) {
		return new ComponentStatus(component, ComponentAvailability.DELIVERED, null);
	}

	/**
	 * Creates a status for a component that was requested but could not be computed.
	 *
	 * @param component    the component that could not be delivered
	 * @param availability the class of reason; must not be {@link ComponentAvailability#DELIVERED}
	 * @param reason       human-readable explanation shown to the operator
	 * @return status carrying the failure class and its explanation
	 */
	@Nonnull
	public static ComponentStatus unavailable(
		@Nonnull CatalogStatisticsComponent component,
		@Nonnull ComponentAvailability availability,
		@Nonnull String reason
	) {
		if (availability == ComponentAvailability.DELIVERED) {
			throw new GenericEvitaInternalError(
				"DELIVERED is not an unavailability reason - use ComponentStatus.delivered(...) instead."
			);
		}
		return new ComponentStatus(component, availability, reason);
	}

	/**
	 * Returns the explanation of why the component could not be delivered.
	 *
	 * @return the reason, empty when the component was delivered
	 */
	@Nonnull
	public Optional<String> reasonIfAny() {
		return Optional.ofNullable(this.reason);
	}

	/**
	 * Shorthand for `availability() == DELIVERED`, so callers do not have to import the enum just to branch on the
	 * common case.
	 *
	 * @return true when the matching sub-message of {@link CatalogStatistics} is present
	 */
	public boolean isDelivered() {
		return this.availability == ComponentAvailability.DELIVERED;
	}

}
