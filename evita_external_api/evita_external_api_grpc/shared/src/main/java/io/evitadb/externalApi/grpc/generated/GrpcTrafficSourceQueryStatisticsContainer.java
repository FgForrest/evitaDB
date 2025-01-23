/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
// source: GrpcTrafficRecording.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * This container holds information about the source query statistics.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer}
 */
public final class GrpcTrafficSourceQueryStatisticsContainer extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer)
    GrpcTrafficSourceQueryStatisticsContainerOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcTrafficSourceQueryStatisticsContainer.newBuilder() to construct.
  private GrpcTrafficSourceQueryStatisticsContainer(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcTrafficSourceQueryStatisticsContainer() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcTrafficSourceQueryStatisticsContainer();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcTrafficSourceQueryStatisticsContainer(
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
          case 10: {
            io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder subBuilder = null;
            if (sourceQueryId_ != null) {
              subBuilder = sourceQueryId_.toBuilder();
            }
            sourceQueryId_ = input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcUuid.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(sourceQueryId_);
              sourceQueryId_ = subBuilder.buildPartial();
            }

            break;
          }
          case 16: {

            returnedRecordCount_ = input.readInt32();
            break;
          }
          case 24: {

            totalRecordCount_ = input.readInt32();
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
    return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryStatisticsContainer_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryStatisticsContainer_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer.class, io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer.Builder.class);
  }

  public static final int SOURCEQUERYID_FIELD_NUMBER = 1;
  private io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId_;
  /**
   * <pre>
   * The source query id
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   * @return Whether the sourceQueryId field is set.
   */
  @java.lang.Override
  public boolean hasSourceQueryId() {
    return sourceQueryId_ != null;
  }
  /**
   * <pre>
   * The source query id
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   * @return The sourceQueryId.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcUuid getSourceQueryId() {
    return sourceQueryId_ == null ? io.evitadb.externalApi.grpc.generated.GrpcUuid.getDefaultInstance() : sourceQueryId_;
  }
  /**
   * <pre>
   * The source query id
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getSourceQueryIdOrBuilder() {
    return getSourceQueryId();
  }

  public static final int RETURNEDRECORDCOUNT_FIELD_NUMBER = 2;
  private int returnedRecordCount_;
  /**
   * <pre>
   * The total number of records returned by the query ({&#64;link EvitaResponse#getRecordData()} size)
   * </pre>
   *
   * <code>int32 returnedRecordCount = 2;</code>
   * @return The returnedRecordCount.
   */
  @java.lang.Override
  public int getReturnedRecordCount() {
    return returnedRecordCount_;
  }

  public static final int TOTALRECORDCOUNT_FIELD_NUMBER = 3;
  private int totalRecordCount_;
  /**
   * <pre>
   * The total number of records calculated by the query ({&#64;link EvitaResponse#getTotalRecordCount()})
   * </pre>
   *
   * <code>int32 totalRecordCount = 3;</code>
   * @return The totalRecordCount.
   */
  @java.lang.Override
  public int getTotalRecordCount() {
    return totalRecordCount_;
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
    if (sourceQueryId_ != null) {
      output.writeMessage(1, getSourceQueryId());
    }
    if (returnedRecordCount_ != 0) {
      output.writeInt32(2, returnedRecordCount_);
    }
    if (totalRecordCount_ != 0) {
      output.writeInt32(3, totalRecordCount_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (sourceQueryId_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, getSourceQueryId());
    }
    if (returnedRecordCount_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(2, returnedRecordCount_);
    }
    if (totalRecordCount_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(3, totalRecordCount_);
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer other = (io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer) obj;

    if (hasSourceQueryId() != other.hasSourceQueryId()) return false;
    if (hasSourceQueryId()) {
      if (!getSourceQueryId()
          .equals(other.getSourceQueryId())) return false;
    }
    if (getReturnedRecordCount()
        != other.getReturnedRecordCount()) return false;
    if (getTotalRecordCount()
        != other.getTotalRecordCount()) return false;
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
    if (hasSourceQueryId()) {
      hash = (37 * hash) + SOURCEQUERYID_FIELD_NUMBER;
      hash = (53 * hash) + getSourceQueryId().hashCode();
    }
    hash = (37 * hash) + RETURNEDRECORDCOUNT_FIELD_NUMBER;
    hash = (53 * hash) + getReturnedRecordCount();
    hash = (37 * hash) + TOTALRECORDCOUNT_FIELD_NUMBER;
    hash = (53 * hash) + getTotalRecordCount();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer prototype) {
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
   * This container holds information about the source query statistics.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer)
      io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainerOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryStatisticsContainer_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryStatisticsContainer_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer.class, io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer.newBuilder()
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
      if (sourceQueryIdBuilder_ == null) {
        sourceQueryId_ = null;
      } else {
        sourceQueryId_ = null;
        sourceQueryIdBuilder_ = null;
      }
      returnedRecordCount_ = 0;

      totalRecordCount_ = 0;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcTrafficRecording.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTrafficSourceQueryStatisticsContainer_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer build() {
      io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer result = new io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer(this);
      if (sourceQueryIdBuilder_ == null) {
        result.sourceQueryId_ = sourceQueryId_;
      } else {
        result.sourceQueryId_ = sourceQueryIdBuilder_.build();
      }
      result.returnedRecordCount_ = returnedRecordCount_;
      result.totalRecordCount_ = totalRecordCount_;
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer.getDefaultInstance()) return this;
      if (other.hasSourceQueryId()) {
        mergeSourceQueryId(other.getSourceQueryId());
      }
      if (other.getReturnedRecordCount() != 0) {
        setReturnedRecordCount(other.getReturnedRecordCount());
      }
      if (other.getTotalRecordCount() != 0) {
        setTotalRecordCount(other.getTotalRecordCount());
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
      io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId_;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcUuid, io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder, io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder> sourceQueryIdBuilder_;
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     * @return Whether the sourceQueryId field is set.
     */
    public boolean hasSourceQueryId() {
      return sourceQueryIdBuilder_ != null || sourceQueryId_ != null;
    }
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     * @return The sourceQueryId.
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUuid getSourceQueryId() {
      if (sourceQueryIdBuilder_ == null) {
        return sourceQueryId_ == null ? io.evitadb.externalApi.grpc.generated.GrpcUuid.getDefaultInstance() : sourceQueryId_;
      } else {
        return sourceQueryIdBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public Builder setSourceQueryId(io.evitadb.externalApi.grpc.generated.GrpcUuid value) {
      if (sourceQueryIdBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        sourceQueryId_ = value;
        onChanged();
      } else {
        sourceQueryIdBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public Builder setSourceQueryId(
        io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder builderForValue) {
      if (sourceQueryIdBuilder_ == null) {
        sourceQueryId_ = builderForValue.build();
        onChanged();
      } else {
        sourceQueryIdBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public Builder mergeSourceQueryId(io.evitadb.externalApi.grpc.generated.GrpcUuid value) {
      if (sourceQueryIdBuilder_ == null) {
        if (sourceQueryId_ != null) {
          sourceQueryId_ =
            io.evitadb.externalApi.grpc.generated.GrpcUuid.newBuilder(sourceQueryId_).mergeFrom(value).buildPartial();
        } else {
          sourceQueryId_ = value;
        }
        onChanged();
      } else {
        sourceQueryIdBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public Builder clearSourceQueryId() {
      if (sourceQueryIdBuilder_ == null) {
        sourceQueryId_ = null;
        onChanged();
      } else {
        sourceQueryId_ = null;
        sourceQueryIdBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder getSourceQueryIdBuilder() {

      onChanged();
      return getSourceQueryIdFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder getSourceQueryIdOrBuilder() {
      if (sourceQueryIdBuilder_ != null) {
        return sourceQueryIdBuilder_.getMessageOrBuilder();
      } else {
        return sourceQueryId_ == null ?
            io.evitadb.externalApi.grpc.generated.GrpcUuid.getDefaultInstance() : sourceQueryId_;
      }
    }
    /**
     * <pre>
     * The source query id
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcUuid sourceQueryId = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcUuid, io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder, io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder>
        getSourceQueryIdFieldBuilder() {
      if (sourceQueryIdBuilder_ == null) {
        sourceQueryIdBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcUuid, io.evitadb.externalApi.grpc.generated.GrpcUuid.Builder, io.evitadb.externalApi.grpc.generated.GrpcUuidOrBuilder>(
                getSourceQueryId(),
                getParentForChildren(),
                isClean());
        sourceQueryId_ = null;
      }
      return sourceQueryIdBuilder_;
    }

    private int returnedRecordCount_ ;
    /**
     * <pre>
     * The total number of records returned by the query ({&#64;link EvitaResponse#getRecordData()} size)
     * </pre>
     *
     * <code>int32 returnedRecordCount = 2;</code>
     * @return The returnedRecordCount.
     */
    @java.lang.Override
    public int getReturnedRecordCount() {
      return returnedRecordCount_;
    }
    /**
     * <pre>
     * The total number of records returned by the query ({&#64;link EvitaResponse#getRecordData()} size)
     * </pre>
     *
     * <code>int32 returnedRecordCount = 2;</code>
     * @param value The returnedRecordCount to set.
     * @return This builder for chaining.
     */
    public Builder setReturnedRecordCount(int value) {

      returnedRecordCount_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The total number of records returned by the query ({&#64;link EvitaResponse#getRecordData()} size)
     * </pre>
     *
     * <code>int32 returnedRecordCount = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearReturnedRecordCount() {

      returnedRecordCount_ = 0;
      onChanged();
      return this;
    }

    private int totalRecordCount_ ;
    /**
     * <pre>
     * The total number of records calculated by the query ({&#64;link EvitaResponse#getTotalRecordCount()})
     * </pre>
     *
     * <code>int32 totalRecordCount = 3;</code>
     * @return The totalRecordCount.
     */
    @java.lang.Override
    public int getTotalRecordCount() {
      return totalRecordCount_;
    }
    /**
     * <pre>
     * The total number of records calculated by the query ({&#64;link EvitaResponse#getTotalRecordCount()})
     * </pre>
     *
     * <code>int32 totalRecordCount = 3;</code>
     * @param value The totalRecordCount to set.
     * @return This builder for chaining.
     */
    public Builder setTotalRecordCount(int value) {

      totalRecordCount_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The total number of records calculated by the query ({&#64;link EvitaResponse#getTotalRecordCount()})
     * </pre>
     *
     * <code>int32 totalRecordCount = 3;</code>
     * @return This builder for chaining.
     */
    public Builder clearTotalRecordCount() {

      totalRecordCount_ = 0;
      onChanged();
      return this;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer)
  private static final io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcTrafficSourceQueryStatisticsContainer>
      PARSER = new com.google.protobuf.AbstractParser<GrpcTrafficSourceQueryStatisticsContainer>() {
    @java.lang.Override
    public GrpcTrafficSourceQueryStatisticsContainer parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcTrafficSourceQueryStatisticsContainer(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcTrafficSourceQueryStatisticsContainer> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcTrafficSourceQueryStatisticsContainer> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcTrafficSourceQueryStatisticsContainer getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

