/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.cdc;


import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.Flow;

/**
 * This interface represents a subscription to a change capture stream in the evitaDB engine. It extends the standard
 * Flow.Subscription interface to provide additional functionality specific to change capture.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ChangeCaptureSubscription extends Flow.Subscription {

	/**
	 * Returns unique identifier of the subscription. This identifier is used to identify the subscription to track
	 * its state and manage its lifecycle.
	 *
	 * @return the unique identifier of the subscription
	 */
	@Nonnull
	UUID getSubscriptionId();

}
