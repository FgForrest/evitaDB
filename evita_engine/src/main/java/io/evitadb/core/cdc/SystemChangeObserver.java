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

package io.evitadb.core.cdc;

import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureObserver;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.utils.UUIDUtil;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SystemChangeObserver {
	private final Map<UUID, RequestWithObserver> systemObservers = new ConcurrentHashMap<>();

	@Nonnull
	public UUID registerObserver(
		@Nonnull ChangeSystemCaptureRequest request,
		@Nonnull ChangeSystemCaptureObserver callback
	) {
		final UUID randomId = UUIDUtil.randomUUID();
		systemObservers.put(
			randomId,
			new RequestWithObserver(request, callback)
		);
		return randomId;
	}

	public boolean unregisterObserver(@Nonnull UUID uuid) {
		return systemObservers.remove(uuid) != null;
	}

	/**
	 * TODO JNO - implement me
	 * @param catalog
	 * @param operation
	 * @param eventSupplier
	 */
	public void notifyObservers(@Nonnull String catalog, @Nonnull Operation operation, @Nonnull Supplier<Mutation> eventSupplier) {
		ChangeSystemCapture captureHeader = null;
		ChangeSystemCapture captureBody = null;
		for (RequestWithObserver requestWithObserver : systemObservers.values()) {
			final ChangeSystemCaptureObserver observer = requestWithObserver.observer();
			final ChangeSystemCaptureRequest request = requestWithObserver.request();
			if (request.content() == CaptureContent.BODY) {
				captureBody = captureBody == null ? new ChangeSystemCapture(catalog, operation, eventSupplier.get()) : captureBody;
				observer.onChange(captureBody);
			} else {
				captureHeader = captureHeader == null ? new ChangeSystemCapture(catalog, operation, null) : captureHeader;
				observer.onChange(captureHeader);
			}
		}
	}

	private record RequestWithObserver(
		@Nonnull ChangeSystemCaptureRequest request,
		@Nonnull ChangeSystemCaptureObserver observer
	) {

	}

}
