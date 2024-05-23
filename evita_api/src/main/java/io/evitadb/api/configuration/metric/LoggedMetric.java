/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.configuration.metric;

import javax.annotation.Nonnull;

/**
 * This DTO wraps required information about to be created Prometheus metric.
 * @param name metric name
 * @param helpMessage description about the metric
 * @param type one of supported Prometheus metric types
 * @param labels labels that will be attached to the metric
 */
public record LoggedMetric(
	@Nonnull String name,
	@Nonnull String helpMessage,
	@Nonnull MetricType type,
	@Nonnull String... labels
) {

}
