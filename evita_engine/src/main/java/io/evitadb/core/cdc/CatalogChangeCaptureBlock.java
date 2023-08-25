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

import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class CatalogChangeCaptureBlock {
	private final CatalogChangeObserver changeObserver;
	private final Map<UUID, List<ChangeCatalogCapture>> notifications = new HashMap<>(32);

	public void notify(@Nonnull UUID uuid, @Nonnull ChangeCatalogCapture captureHeader) {
		notifications.compute(
			uuid,
			(uuid1, changeCatalogCaptures) -> {
				if (changeCatalogCaptures == null) {
					changeCatalogCaptures = new LinkedList<>();
				}
				changeCatalogCaptures.add(captureHeader);
				return changeCatalogCaptures;
			}
		);
	}

	public void finish() {
		notifications.forEach(
			(uuid, changeCatalogCaptures) -> {
				// todo jno: reimplement
//				final ChangeDataCaptureObserver observer = changeObserver.getObserver(uuid);
//				if (observer != null) {
//					// TODO JNO - implement transaction commit
//					observer.onTransactionCommit(0L, changeDataCaptures);
//				}
			}
		);
	}
}
