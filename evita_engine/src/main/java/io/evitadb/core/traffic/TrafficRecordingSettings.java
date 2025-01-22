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

package io.evitadb.core.traffic;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;

/**
 * TrafficRecordingSettings class encapsulates configuration settings for traffic recording.
 * It defines the parameters that control how traffic data is recorded, including sampling rate,
 * duration, size limits, and chunk file sizes.
 *
 * @param catalogName			   Specifies the name of the catalog for which traffic recording is done.
 * @param samplingRate              Defines the rate at which traffic samples are recorded. The value
 *                                  is provided as a percentage (e.g., 1 to 100), where 100 represents
 *                                  all traffic being recorded and lower values represent partial sampling.
 * @param exportFile				Specifies whether recorded traffic data should be exported to a file.
 * @param recordingDuration         Specifies the duration for which traffic recording should occur.
 *                                  Can be null, indicating that recording is not time-bound. When
 *                                  provided, it ensures that traffic recording will not exceed
 *                                  the defined duration.
 * @param recordingSizeLimitInBytes Specifies the maximum size of the traffic recording in bytes.
 *                                  This parameter is optional and can be null. When provided,
 *                                  it serves as an upper limit for traffic recording size, ensuring
 *                                  that recorded data does not exceed the specified size.
 * @param chunkFileSizeInBytes      Defines the size of each chunk file used to store recorded traffic
 *                                  data. Recorded data is divided into files of this size, aiding in
 *                                  data management and processing efficiency.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record TrafficRecordingSettings(
	@Nonnull String catalogName,
	int samplingRate,
	boolean exportFile,
	@Nullable Duration recordingDuration,
	@Nullable Long recordingSizeLimitInBytes,
	long chunkFileSizeInBytes
) {
}
