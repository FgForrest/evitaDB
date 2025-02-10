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
 * Response to GetTrafficRecordingLabelsNamesOrderedByCardinality request.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest}
 */
public final class GetTrafficRecordingLabelNamesRequest extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest)
    GetTrafficRecordingLabelNamesRequestOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GetTrafficRecordingLabelNamesRequest.newBuilder() to construct.
  private GetTrafficRecordingLabelNamesRequest(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GetTrafficRecordingLabelNamesRequest() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GetTrafficRecordingLabelNamesRequest();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GetTrafficRecordingLabelNamesRequest(
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

            limit_ = input.readInt32();
            break;
          }
          case 18: {
            com.google.protobuf.StringValue.Builder subBuilder = null;
            if (nameStartsWith_ != null) {
              subBuilder = nameStartsWith_.toBuilder();
            }
            nameStartsWith_ = input.readMessage(com.google.protobuf.StringValue.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(nameStartsWith_);
              nameStartsWith_ = subBuilder.buildPartial();
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
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.class, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.Builder.class);
  }

  public static final int LIMIT_FIELD_NUMBER = 1;
  private int limit_;
  /**
   * <pre>
   * The limit of records to return
   * </pre>
   *
   * <code>int32 limit = 1;</code>
   * @return The limit.
   */
  @java.lang.Override
  public int getLimit() {
    return limit_;
  }

  public static final int NAMESTARTSWITH_FIELD_NUMBER = 2;
  private com.google.protobuf.StringValue nameStartsWith_;
  /**
   * <pre>
   * Allows to filter the returned labels by the name prefix
   * </pre>
   *
   * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
   * @return Whether the nameStartsWith field is set.
   */
  @java.lang.Override
  public boolean hasNameStartsWith() {
    return nameStartsWith_ != null;
  }
  /**
   * <pre>
   * Allows to filter the returned labels by the name prefix
   * </pre>
   *
   * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
   * @return The nameStartsWith.
   */
  @java.lang.Override
  public com.google.protobuf.StringValue getNameStartsWith() {
    return nameStartsWith_ == null ? com.google.protobuf.StringValue.getDefaultInstance() : nameStartsWith_;
  }
  /**
   * <pre>
   * Allows to filter the returned labels by the name prefix
   * </pre>
   *
   * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
   */
  @java.lang.Override
  public com.google.protobuf.StringValueOrBuilder getNameStartsWithOrBuilder() {
    return getNameStartsWith();
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
    if (limit_ != 0) {
      output.writeInt32(1, limit_);
    }
    if (nameStartsWith_ != null) {
      output.writeMessage(2, getNameStartsWith());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (limit_ != 0) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt32Size(1, limit_);
    }
    if (nameStartsWith_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(2, getNameStartsWith());
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest other = (io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest) obj;

    if (getLimit()
        != other.getLimit()) return false;
    if (hasNameStartsWith() != other.hasNameStartsWith()) return false;
    if (hasNameStartsWith()) {
      if (!getNameStartsWith()
          .equals(other.getNameStartsWith())) return false;
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
    hash = (37 * hash) + LIMIT_FIELD_NUMBER;
    hash = (53 * hash) + getLimit();
    if (hasNameStartsWith()) {
      hash = (37 * hash) + NAMESTARTSWITH_FIELD_NUMBER;
      hash = (53 * hash) + getNameStartsWith().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest prototype) {
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
   * Response to GetTrafficRecordingLabelsNamesOrderedByCardinality request.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest)
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequestOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.class, io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.newBuilder()
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
      limit_ = 0;

      if (nameStartsWithBuilder_ == null) {
        nameStartsWith_ = null;
      } else {
        nameStartsWith_ = null;
        nameStartsWithBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaTrafficRecordingAPI.internal_static_io_evitadb_externalApi_grpc_generated_GetTrafficRecordingLabelNamesRequest_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest build() {
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest buildPartial() {
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest result = new io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest(this);
      result.limit_ = limit_;
      if (nameStartsWithBuilder_ == null) {
        result.nameStartsWith_ = nameStartsWith_;
      } else {
        result.nameStartsWith_ = nameStartsWithBuilder_.build();
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest other) {
      if (other == io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest.getDefaultInstance()) return this;
      if (other.getLimit() != 0) {
        setLimit(other.getLimit());
      }
      if (other.hasNameStartsWith()) {
        mergeNameStartsWith(other.getNameStartsWith());
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
      io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private int limit_ ;
    /**
     * <pre>
     * The limit of records to return
     * </pre>
     *
     * <code>int32 limit = 1;</code>
     * @return The limit.
     */
    @java.lang.Override
    public int getLimit() {
      return limit_;
    }
    /**
     * <pre>
     * The limit of records to return
     * </pre>
     *
     * <code>int32 limit = 1;</code>
     * @param value The limit to set.
     * @return This builder for chaining.
     */
    public Builder setLimit(int value) {
      
      limit_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The limit of records to return
     * </pre>
     *
     * <code>int32 limit = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearLimit() {
      
      limit_ = 0;
      onChanged();
      return this;
    }

    private com.google.protobuf.StringValue nameStartsWith_;
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.StringValue, com.google.protobuf.StringValue.Builder, com.google.protobuf.StringValueOrBuilder> nameStartsWithBuilder_;
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     * @return Whether the nameStartsWith field is set.
     */
    public boolean hasNameStartsWith() {
      return nameStartsWithBuilder_ != null || nameStartsWith_ != null;
    }
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     * @return The nameStartsWith.
     */
    public com.google.protobuf.StringValue getNameStartsWith() {
      if (nameStartsWithBuilder_ == null) {
        return nameStartsWith_ == null ? com.google.protobuf.StringValue.getDefaultInstance() : nameStartsWith_;
      } else {
        return nameStartsWithBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     */
    public Builder setNameStartsWith(com.google.protobuf.StringValue value) {
      if (nameStartsWithBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        nameStartsWith_ = value;
        onChanged();
      } else {
        nameStartsWithBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     */
    public Builder setNameStartsWith(
        com.google.protobuf.StringValue.Builder builderForValue) {
      if (nameStartsWithBuilder_ == null) {
        nameStartsWith_ = builderForValue.build();
        onChanged();
      } else {
        nameStartsWithBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     */
    public Builder mergeNameStartsWith(com.google.protobuf.StringValue value) {
      if (nameStartsWithBuilder_ == null) {
        if (nameStartsWith_ != null) {
          nameStartsWith_ =
            com.google.protobuf.StringValue.newBuilder(nameStartsWith_).mergeFrom(value).buildPartial();
        } else {
          nameStartsWith_ = value;
        }
        onChanged();
      } else {
        nameStartsWithBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     */
    public Builder clearNameStartsWith() {
      if (nameStartsWithBuilder_ == null) {
        nameStartsWith_ = null;
        onChanged();
      } else {
        nameStartsWith_ = null;
        nameStartsWithBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     */
    public com.google.protobuf.StringValue.Builder getNameStartsWithBuilder() {
      
      onChanged();
      return getNameStartsWithFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     */
    public com.google.protobuf.StringValueOrBuilder getNameStartsWithOrBuilder() {
      if (nameStartsWithBuilder_ != null) {
        return nameStartsWithBuilder_.getMessageOrBuilder();
      } else {
        return nameStartsWith_ == null ?
            com.google.protobuf.StringValue.getDefaultInstance() : nameStartsWith_;
      }
    }
    /**
     * <pre>
     * Allows to filter the returned labels by the name prefix
     * </pre>
     *
     * <code>.google.protobuf.StringValue nameStartsWith = 2;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.StringValue, com.google.protobuf.StringValue.Builder, com.google.protobuf.StringValueOrBuilder> 
        getNameStartsWithFieldBuilder() {
      if (nameStartsWithBuilder_ == null) {
        nameStartsWithBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            com.google.protobuf.StringValue, com.google.protobuf.StringValue.Builder, com.google.protobuf.StringValueOrBuilder>(
                getNameStartsWith(),
                getParentForChildren(),
                isClean());
        nameStartsWith_ = null;
      }
      return nameStartsWithBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest)
  private static final io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest();
  }

  public static io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GetTrafficRecordingLabelNamesRequest>
      PARSER = new com.google.protobuf.AbstractParser<GetTrafficRecordingLabelNamesRequest>() {
    @java.lang.Override
    public GetTrafficRecordingLabelNamesRequest parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GetTrafficRecordingLabelNamesRequest(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GetTrafficRecordingLabelNamesRequest> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GetTrafficRecordingLabelNamesRequest> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GetTrafficRecordingLabelNamesRequest getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

