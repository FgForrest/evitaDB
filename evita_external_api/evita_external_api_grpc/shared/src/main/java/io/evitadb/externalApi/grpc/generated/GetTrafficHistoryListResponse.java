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
// source: GrpcEvitaTrafficRecordingAPI.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Response to GetTrafficHistoryList request.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse}
 */
public final class GetTrafficHistoryListResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse)
    GetTrafficHistoryListResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GetTrafficHistoryListResponse.newBuilder() to construct.
  private GetTrafficHistoryListResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GetTrafficHistoryListResponse() {
    trafficRecord_ = java.util.Collections.emptyList();
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GetTrafficHistoryListResponse();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GetTrafficHistoryListResponse(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    int mutable_bitField0_ = 0;
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
            if (!((mutable_bitField0_ & 0x00000001) != 0)) {
              trafficRecord_ = new java.util.ArrayList<io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord>();
              mutable_bitField0_ |= 0x00000001;
            }
            trafficRecord_.add(
                input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.parser(), extensionRegistry));
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
      if (((mutable_bitField0_ & 0x00000001) != 0)) {
        trafficRecord_ = java.util.Collections.unmodifiableList(trafficRecord_);
      }
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.class, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.Builder.class);
  }

  public static final int TRAFFICRECORD_FIELD_NUMBER = 1;
  private java.util.List<io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord> trafficRecord_;
  /**
   * <pre>
   * The list of traffic records that match the criteria
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
   */
  @java.lang.Override
  public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord> getTrafficRecordList() {
    return trafficRecord_;
  }
  /**
   * <pre>
   * The list of traffic records that match the criteria
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
   */
  @java.lang.Override
  public java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordOrBuilder> 
      getTrafficRecordOrBuilderList() {
    return trafficRecord_;
  }
  /**
   * <pre>
   * The list of traffic records that match the criteria
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
   */
  @java.lang.Override
  public int getTrafficRecordCount() {
    return trafficRecord_.size();
  }
  /**
   * <pre>
   * The list of traffic records that match the criteria
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord getTrafficRecord(int index) {
    return trafficRecord_.get(index);
  }
  /**
   * <pre>
   * The list of traffic records that match the criteria
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordOrBuilder getTrafficRecordOrBuilder(
      int index) {
    return trafficRecord_.get(index);
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
    for (int i = 0; i < trafficRecord_.size(); i++) {
      output.writeMessage(1, trafficRecord_.get(i));
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    for (int i = 0; i < trafficRecord_.size(); i++) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, trafficRecord_.get(i));
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse other = (io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse) obj;

    if (!getTrafficRecordList()
        .equals(other.getTrafficRecordList())) return false;
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
    if (getTrafficRecordCount() > 0) {
      hash = (37 * hash) + TRAFFICRECORD_FIELD_NUMBER;
      hash = (53 * hash) + getTrafficRecordList().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse prototype) {
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
   * Response to GetTrafficHistoryList request.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse)
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.class, io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.newBuilder()
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
        getTrafficRecordFieldBuilder();
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      if (trafficRecordBuilder_ == null) {
        trafficRecord_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
      } else {
        trafficRecordBuilder_.clear();
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficHistoryListResponse_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse build() {
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse buildPartial() {
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse result = new io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse(this);
      int from_bitField0_ = bitField0_;
      if (trafficRecordBuilder_ == null) {
        if (((bitField0_ & 0x00000001) != 0)) {
          trafficRecord_ = java.util.Collections.unmodifiableList(trafficRecord_);
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.trafficRecord_ = trafficRecord_;
      } else {
        result.trafficRecord_ = trafficRecordBuilder_.build();
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse other) {
      if (other == io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse.getDefaultInstance()) return this;
      if (trafficRecordBuilder_ == null) {
        if (!other.trafficRecord_.isEmpty()) {
          if (trafficRecord_.isEmpty()) {
            trafficRecord_ = other.trafficRecord_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureTrafficRecordIsMutable();
            trafficRecord_.addAll(other.trafficRecord_);
          }
          onChanged();
        }
      } else {
        if (!other.trafficRecord_.isEmpty()) {
          if (trafficRecordBuilder_.isEmpty()) {
            trafficRecordBuilder_.dispose();
            trafficRecordBuilder_ = null;
            trafficRecord_ = other.trafficRecord_;
            bitField0_ = (bitField0_ & ~0x00000001);
            trafficRecordBuilder_ = 
              com.google.protobuf.GeneratedMessageV3.alwaysUseFieldBuilders ?
                 getTrafficRecordFieldBuilder() : null;
          } else {
            trafficRecordBuilder_.addAllMessages(other.trafficRecord_);
          }
        }
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
      io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    private int bitField0_;

    private java.util.List<io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord> trafficRecord_ =
      java.util.Collections.emptyList();
    private void ensureTrafficRecordIsMutable() {
      if (!((bitField0_ & 0x00000001) != 0)) {
        trafficRecord_ = new java.util.ArrayList<io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord>(trafficRecord_);
        bitField0_ |= 0x00000001;
       }
    }

    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordOrBuilder> trafficRecordBuilder_;

    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord> getTrafficRecordList() {
      if (trafficRecordBuilder_ == null) {
        return java.util.Collections.unmodifiableList(trafficRecord_);
      } else {
        return trafficRecordBuilder_.getMessageList();
      }
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public int getTrafficRecordCount() {
      if (trafficRecordBuilder_ == null) {
        return trafficRecord_.size();
      } else {
        return trafficRecordBuilder_.getCount();
      }
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord getTrafficRecord(int index) {
      if (trafficRecordBuilder_ == null) {
        return trafficRecord_.get(index);
      } else {
        return trafficRecordBuilder_.getMessage(index);
      }
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder setTrafficRecord(
        int index, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord value) {
      if (trafficRecordBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureTrafficRecordIsMutable();
        trafficRecord_.set(index, value);
        onChanged();
      } else {
        trafficRecordBuilder_.setMessage(index, value);
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder setTrafficRecord(
        int index, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder builderForValue) {
      if (trafficRecordBuilder_ == null) {
        ensureTrafficRecordIsMutable();
        trafficRecord_.set(index, builderForValue.build());
        onChanged();
      } else {
        trafficRecordBuilder_.setMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder addTrafficRecord(io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord value) {
      if (trafficRecordBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureTrafficRecordIsMutable();
        trafficRecord_.add(value);
        onChanged();
      } else {
        trafficRecordBuilder_.addMessage(value);
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder addTrafficRecord(
        int index, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord value) {
      if (trafficRecordBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureTrafficRecordIsMutable();
        trafficRecord_.add(index, value);
        onChanged();
      } else {
        trafficRecordBuilder_.addMessage(index, value);
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder addTrafficRecord(
        io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder builderForValue) {
      if (trafficRecordBuilder_ == null) {
        ensureTrafficRecordIsMutable();
        trafficRecord_.add(builderForValue.build());
        onChanged();
      } else {
        trafficRecordBuilder_.addMessage(builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder addTrafficRecord(
        int index, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder builderForValue) {
      if (trafficRecordBuilder_ == null) {
        ensureTrafficRecordIsMutable();
        trafficRecord_.add(index, builderForValue.build());
        onChanged();
      } else {
        trafficRecordBuilder_.addMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder addAllTrafficRecord(
        java.lang.Iterable<? extends io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord> values) {
      if (trafficRecordBuilder_ == null) {
        ensureTrafficRecordIsMutable();
        com.google.protobuf.AbstractMessageLite.Builder.addAll(
            values, trafficRecord_);
        onChanged();
      } else {
        trafficRecordBuilder_.addAllMessages(values);
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder clearTrafficRecord() {
      if (trafficRecordBuilder_ == null) {
        trafficRecord_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
      } else {
        trafficRecordBuilder_.clear();
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public Builder removeTrafficRecord(int index) {
      if (trafficRecordBuilder_ == null) {
        ensureTrafficRecordIsMutable();
        trafficRecord_.remove(index);
        onChanged();
      } else {
        trafficRecordBuilder_.remove(index);
      }
      return this;
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder getTrafficRecordBuilder(
        int index) {
      return getTrafficRecordFieldBuilder().getBuilder(index);
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordOrBuilder getTrafficRecordOrBuilder(
        int index) {
      if (trafficRecordBuilder_ == null) {
        return trafficRecord_.get(index);  } else {
        return trafficRecordBuilder_.getMessageOrBuilder(index);
      }
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordOrBuilder> 
         getTrafficRecordOrBuilderList() {
      if (trafficRecordBuilder_ != null) {
        return trafficRecordBuilder_.getMessageOrBuilderList();
      } else {
        return java.util.Collections.unmodifiableList(trafficRecord_);
      }
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder addTrafficRecordBuilder() {
      return getTrafficRecordFieldBuilder().addBuilder(
          io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.getDefaultInstance());
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder addTrafficRecordBuilder(
        int index) {
      return getTrafficRecordFieldBuilder().addBuilder(
          index, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.getDefaultInstance());
    }
    /**
     * <pre>
     * The list of traffic records that match the criteria
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord trafficRecord = 1;</code>
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder> 
         getTrafficRecordBuilderList() {
      return getTrafficRecordFieldBuilder().getBuilderList();
    }
    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordOrBuilder> 
        getTrafficRecordFieldBuilder() {
      if (trafficRecordBuilder_ == null) {
        trafficRecordBuilder_ = new com.google.protobuf.RepeatedFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.Builder, io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordOrBuilder>(
                trafficRecord_,
                ((bitField0_ & 0x00000001) != 0),
                getParentForChildren(),
                isClean());
        trafficRecord_ = null;
      }
      return trafficRecordBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse)
  private static final io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse();
  }

  public static io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GetTrafficHistoryListResponse>
      PARSER = new com.google.protobuf.AbstractParser<GetTrafficHistoryListResponse>() {
    @java.lang.Override
    public GetTrafficHistoryListResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GetTrafficHistoryListResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GetTrafficHistoryListResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GetTrafficHistoryListResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GetTrafficHistoryListResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

