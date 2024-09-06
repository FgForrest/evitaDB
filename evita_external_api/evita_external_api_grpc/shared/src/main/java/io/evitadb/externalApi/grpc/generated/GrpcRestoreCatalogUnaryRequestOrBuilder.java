/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEvitaManagementAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcRestoreCatalogUnaryRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogUnaryRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of the catalog where the backup will be restored
   * The name must not clash with any of existing catalogs
   * </pre>
   *
   * <code>string catalogName = 1;</code>
   * @return The catalogName.
   */
  java.lang.String getCatalogName();
  /**
   * <pre>
   * Name of the catalog where the backup will be restored
   * The name must not clash with any of existing catalogs
   * </pre>
   *
   * <code>string catalogName = 1;</code>
   * @return The bytes for catalogName.
   */
  com.google.protobuf.ByteString
      getCatalogNameBytes();

  /**
   * <pre>
   * Binary contents of the backup file.
   * </pre>
   *
   * <code>bytes backupFile = 2;</code>
   * @return The backupFile.
   */
  com.google.protobuf.ByteString getBackupFile();

  /**
   * <pre>
   * Identification of the task (for continuation purpose)
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid fileId = 3;</code>
   * @return Whether the fileId field is set.
   */
  boolean hasFileId();
  /**
   * <pre>
   * Identification of the task (for continuation purpose)
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid fileId = 3;</code>
   * @return The fileId.
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuid getFileId();
  /**
   * <pre>
   * Identification of the task (for continuation purpose)
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid fileId = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getFileIdOrBuilder();

  /**
   * <pre>
   * Total size of uploaded file in Bytes, when the size is reached, restore automatically starts
   * </pre>
   *
   * <code>int64 totalSizeInBytes = 4;</code>
   * @return The totalSizeInBytes.
   */
  long getTotalSizeInBytes();
}
