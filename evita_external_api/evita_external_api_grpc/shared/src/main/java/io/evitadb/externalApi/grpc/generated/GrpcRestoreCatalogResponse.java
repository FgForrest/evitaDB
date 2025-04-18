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

/**
 * <pre>
 * Response to a catalog restore request.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse}
 */
public final class GrpcRestoreCatalogResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse)
    GrpcRestoreCatalogResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcRestoreCatalogResponse.newBuilder() to construct.
  private GrpcRestoreCatalogResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcRestoreCatalogResponse() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcRestoreCatalogResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcRestoreCatalogResponse(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 8: {

            read_ = input.readInt64();
            break;
          }
          case 26: {
            io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.Builder subBuilder = null;
            if (task_ != null) {
              subBuilder = task_.toBuilder();
            }
            task_ = input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(task_);
              task_ = subBuilder.buildPartial();
            }

            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaManagementAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreCatalogResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaManagementAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreCatalogResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.class, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.Builder.class);
  }

  public static final int READ_FIELD_NUMBER = 1;
  private long read_;
  /**
   * <pre>
   * returns the number of bytes read from the backup file
   * </pre>
   *
   * <code>int64 read = 1;</code>
   * @return The read.
   */
  @java.lang.Override
  public long getRead() {
    return read_;
  }

  public static final int TASK_FIELD_NUMBER = 3;
  private io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task_;
  /**
   * <pre>
   * the task that is used to restore the catalog and getting its progress
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
   * @return Whether the task field is set.
   */
  @java.lang.Override
  public boolean hasTask() {
    return task_ != null;
  }
  /**
   * <pre>
   * the task that is used to restore the catalog and getting its progress
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
   * @return The task.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcTaskStatus getTask() {
    return task_ == null ? io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.getDefaultInstance() : task_;
  }
  /**
   * <pre>
   * the task that is used to restore the catalog and getting its progress
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcTaskStatusOrBuilder getTaskOrBuilder() {
    return getTask();
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (read_ != 0L) {
      output.writeInt64(1, read_);
    }
    if (task_ != null) {
      output.writeMessage(3, getTask());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (read_ != 0L) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt64Size(1, read_);
    }
    if (task_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(3, getTask());
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse other = (io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse) obj;

    if (getRead()
        != other.getRead()) return false;
    if (hasTask() != other.hasTask()) return false;
    if (hasTask()) {
      if (!getTask()
          .equals(other.getTask())) return false;
    }
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + READ_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
        getRead());
    if (hasTask()) {
      hash = (37 * hash) + TASK_FIELD_NUMBER;
      hash = (53 * hash) + getTask().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * Response to a catalog restore request.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse)
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaManagementAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreCatalogResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaManagementAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreCatalogResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.class, io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      read_ = 0L;

      if (taskBuilder_ == null) {
        task_ = null;
      } else {
        task_ = null;
        taskBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaManagementAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRestoreCatalogResponse_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse build() {
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse result = new io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse(this);
      result.read_ = read_;
      if (taskBuilder_ == null) {
        result.task_ = task_;
      } else {
        result.task_ = taskBuilder_.build();
      }
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse.getDefaultInstance()) return this;
      if (other.getRead() != 0L) {
        setRead(other.getRead());
      }
      if (other.hasTask()) {
        mergeTask(other.getTask());
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private long read_ ;
    /**
     * <pre>
     * returns the number of bytes read from the backup file
     * </pre>
     *
     * <code>int64 read = 1;</code>
     * @return The read.
     */
    @java.lang.Override
    public long getRead() {
      return read_;
    }
    /**
     * <pre>
     * returns the number of bytes read from the backup file
     * </pre>
     *
     * <code>int64 read = 1;</code>
     * @param value The read to set.
     * @return This builder for chaining.
     */
    public Builder setRead(long value) {
      
      read_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * returns the number of bytes read from the backup file
     * </pre>
     *
     * <code>int64 read = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearRead() {
      
      read_ = 0L;
      onChanged();
      return this;
    }

    private io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcTaskStatus, io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.Builder, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusOrBuilder> taskBuilder_;
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     * @return Whether the task field is set.
     */
    public boolean hasTask() {
      return taskBuilder_ != null || task_ != null;
    }
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     * @return The task.
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTaskStatus getTask() {
      if (taskBuilder_ == null) {
        return task_ == null ? io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.getDefaultInstance() : task_;
      } else {
        return taskBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     */
    public Builder setTask(io.evitadb.externalApi.grpc.generated.GrpcTaskStatus value) {
      if (taskBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        task_ = value;
        onChanged();
      } else {
        taskBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     */
    public Builder setTask(
        io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.Builder builderForValue) {
      if (taskBuilder_ == null) {
        task_ = builderForValue.build();
        onChanged();
      } else {
        taskBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     */
    public Builder mergeTask(io.evitadb.externalApi.grpc.generated.GrpcTaskStatus value) {
      if (taskBuilder_ == null) {
        if (task_ != null) {
          task_ =
            io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.newBuilder(task_).mergeFrom(value).buildPartial();
        } else {
          task_ = value;
        }
        onChanged();
      } else {
        taskBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     */
    public Builder clearTask() {
      if (taskBuilder_ == null) {
        task_ = null;
        onChanged();
      } else {
        task_ = null;
        taskBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.Builder getTaskBuilder() {
      
      onChanged();
      return getTaskFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTaskStatusOrBuilder getTaskOrBuilder() {
      if (taskBuilder_ != null) {
        return taskBuilder_.getMessageOrBuilder();
      } else {
        return task_ == null ?
            io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.getDefaultInstance() : task_;
      }
    }
    /**
     * <pre>
     * the task that is used to restore the catalog and getting its progress
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcTaskStatus task = 3;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcTaskStatus, io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.Builder, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusOrBuilder> 
        getTaskFieldBuilder() {
      if (taskBuilder_ == null) {
        taskBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcTaskStatus, io.evitadb.externalApi.grpc.generated.GrpcTaskStatus.Builder, io.evitadb.externalApi.grpc.generated.GrpcTaskStatusOrBuilder>(
                getTask(),
                getParentForChildren(),
                isClean());
        task_ = null;
      }
      return taskBuilder_;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse)
  private static final io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcRestoreCatalogResponse>
      PARSER = new com.google.protobuf.AbstractParser<GrpcRestoreCatalogResponse>() {
    @java.lang.Override
    public GrpcRestoreCatalogResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcRestoreCatalogResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcRestoreCatalogResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcRestoreCatalogResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

