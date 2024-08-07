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
// source: GrpcEvitaSessionAPI.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Request for updating the catalog schema.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest}
 */
public final class GrpcUpdateCatalogSchemaRequest extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest)
    GrpcUpdateCatalogSchemaRequestOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcUpdateCatalogSchemaRequest.newBuilder() to construct.
  private GrpcUpdateCatalogSchemaRequest(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcUpdateCatalogSchemaRequest() {
    schemaMutations_ = java.util.Collections.emptyList();
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcUpdateCatalogSchemaRequest();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcUpdateCatalogSchemaRequest(
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
              schemaMutations_ = new java.util.ArrayList<io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation>();
              mutable_bitField0_ |= 0x00000001;
            }
            schemaMutations_.add(
                input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.parser(), extensionRegistry));
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
        schemaMutations_ = java.util.Collections.unmodifiableList(schemaMutations_);
      }
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcUpdateCatalogSchemaRequest_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcUpdateCatalogSchemaRequest_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.class, io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.Builder.class);
  }

  public static final int SCHEMAMUTATIONS_FIELD_NUMBER = 1;
  private java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation> schemaMutations_;
  /**
   * <pre>
   * Collection of local catalog schema mutations to be applied.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
   */
  @java.lang.Override
  public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation> getSchemaMutationsList() {
    return schemaMutations_;
  }
  /**
   * <pre>
   * Collection of local catalog schema mutations to be applied.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
   */
  @java.lang.Override
  public java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutationOrBuilder>
      getSchemaMutationsOrBuilderList() {
    return schemaMutations_;
  }
  /**
   * <pre>
   * Collection of local catalog schema mutations to be applied.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
   */
  @java.lang.Override
  public int getSchemaMutationsCount() {
    return schemaMutations_.size();
  }
  /**
   * <pre>
   * Collection of local catalog schema mutations to be applied.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation getSchemaMutations(int index) {
    return schemaMutations_.get(index);
  }
  /**
   * <pre>
   * Collection of local catalog schema mutations to be applied.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutationOrBuilder getSchemaMutationsOrBuilder(
      int index) {
    return schemaMutations_.get(index);
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
    for (int i = 0; i < schemaMutations_.size(); i++) {
      output.writeMessage(1, schemaMutations_.get(i));
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    for (int i = 0; i < schemaMutations_.size(); i++) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, schemaMutations_.get(i));
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest other = (io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest) obj;

    if (!getSchemaMutationsList()
        .equals(other.getSchemaMutationsList())) return false;
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
    if (getSchemaMutationsCount() > 0) {
      hash = (37 * hash) + SCHEMAMUTATIONS_FIELD_NUMBER;
      hash = (53 * hash) + getSchemaMutationsList().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest prototype) {
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
   * Request for updating the catalog schema.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest)
      io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequestOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcUpdateCatalogSchemaRequest_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcUpdateCatalogSchemaRequest_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.class, io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.newBuilder()
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
        getSchemaMutationsFieldBuilder();
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      if (schemaMutationsBuilder_ == null) {
        schemaMutations_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
      } else {
        schemaMutationsBuilder_.clear();
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcUpdateCatalogSchemaRequest_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest build() {
      io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest result = new io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest(this);
      int from_bitField0_ = bitField0_;
      if (schemaMutationsBuilder_ == null) {
        if (((bitField0_ & 0x00000001) != 0)) {
          schemaMutations_ = java.util.Collections.unmodifiableList(schemaMutations_);
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.schemaMutations_ = schemaMutations_;
      } else {
        result.schemaMutations_ = schemaMutationsBuilder_.build();
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest.getDefaultInstance()) return this;
      if (schemaMutationsBuilder_ == null) {
        if (!other.schemaMutations_.isEmpty()) {
          if (schemaMutations_.isEmpty()) {
            schemaMutations_ = other.schemaMutations_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureSchemaMutationsIsMutable();
            schemaMutations_.addAll(other.schemaMutations_);
          }
          onChanged();
        }
      } else {
        if (!other.schemaMutations_.isEmpty()) {
          if (schemaMutationsBuilder_.isEmpty()) {
            schemaMutationsBuilder_.dispose();
            schemaMutationsBuilder_ = null;
            schemaMutations_ = other.schemaMutations_;
            bitField0_ = (bitField0_ & ~0x00000001);
            schemaMutationsBuilder_ =
              com.google.protobuf.GeneratedMessageV3.alwaysUseFieldBuilders ?
                 getSchemaMutationsFieldBuilder() : null;
          } else {
            schemaMutationsBuilder_.addAllMessages(other.schemaMutations_);
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
      io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    private int bitField0_;

    private java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation> schemaMutations_ =
      java.util.Collections.emptyList();
    private void ensureSchemaMutationsIsMutable() {
      if (!((bitField0_ & 0x00000001) != 0)) {
        schemaMutations_ = new java.util.ArrayList<io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation>(schemaMutations_);
        bitField0_ |= 0x00000001;
       }
    }

    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutationOrBuilder> schemaMutationsBuilder_;

    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation> getSchemaMutationsList() {
      if (schemaMutationsBuilder_ == null) {
        return java.util.Collections.unmodifiableList(schemaMutations_);
      } else {
        return schemaMutationsBuilder_.getMessageList();
      }
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public int getSchemaMutationsCount() {
      if (schemaMutationsBuilder_ == null) {
        return schemaMutations_.size();
      } else {
        return schemaMutationsBuilder_.getCount();
      }
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation getSchemaMutations(int index) {
      if (schemaMutationsBuilder_ == null) {
        return schemaMutations_.get(index);
      } else {
        return schemaMutationsBuilder_.getMessage(index);
      }
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder setSchemaMutations(
        int index, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation value) {
      if (schemaMutationsBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureSchemaMutationsIsMutable();
        schemaMutations_.set(index, value);
        onChanged();
      } else {
        schemaMutationsBuilder_.setMessage(index, value);
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder setSchemaMutations(
        int index, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder builderForValue) {
      if (schemaMutationsBuilder_ == null) {
        ensureSchemaMutationsIsMutable();
        schemaMutations_.set(index, builderForValue.build());
        onChanged();
      } else {
        schemaMutationsBuilder_.setMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder addSchemaMutations(io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation value) {
      if (schemaMutationsBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureSchemaMutationsIsMutable();
        schemaMutations_.add(value);
        onChanged();
      } else {
        schemaMutationsBuilder_.addMessage(value);
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder addSchemaMutations(
        int index, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation value) {
      if (schemaMutationsBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureSchemaMutationsIsMutable();
        schemaMutations_.add(index, value);
        onChanged();
      } else {
        schemaMutationsBuilder_.addMessage(index, value);
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder addSchemaMutations(
        io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder builderForValue) {
      if (schemaMutationsBuilder_ == null) {
        ensureSchemaMutationsIsMutable();
        schemaMutations_.add(builderForValue.build());
        onChanged();
      } else {
        schemaMutationsBuilder_.addMessage(builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder addSchemaMutations(
        int index, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder builderForValue) {
      if (schemaMutationsBuilder_ == null) {
        ensureSchemaMutationsIsMutable();
        schemaMutations_.add(index, builderForValue.build());
        onChanged();
      } else {
        schemaMutationsBuilder_.addMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder addAllSchemaMutations(
        java.lang.Iterable<? extends io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation> values) {
      if (schemaMutationsBuilder_ == null) {
        ensureSchemaMutationsIsMutable();
        com.google.protobuf.AbstractMessageLite.Builder.addAll(
            values, schemaMutations_);
        onChanged();
      } else {
        schemaMutationsBuilder_.addAllMessages(values);
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder clearSchemaMutations() {
      if (schemaMutationsBuilder_ == null) {
        schemaMutations_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
      } else {
        schemaMutationsBuilder_.clear();
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public Builder removeSchemaMutations(int index) {
      if (schemaMutationsBuilder_ == null) {
        ensureSchemaMutationsIsMutable();
        schemaMutations_.remove(index);
        onChanged();
      } else {
        schemaMutationsBuilder_.remove(index);
      }
      return this;
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder getSchemaMutationsBuilder(
        int index) {
      return getSchemaMutationsFieldBuilder().getBuilder(index);
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutationOrBuilder getSchemaMutationsOrBuilder(
        int index) {
      if (schemaMutationsBuilder_ == null) {
        return schemaMutations_.get(index);  } else {
        return schemaMutationsBuilder_.getMessageOrBuilder(index);
      }
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutationOrBuilder>
         getSchemaMutationsOrBuilderList() {
      if (schemaMutationsBuilder_ != null) {
        return schemaMutationsBuilder_.getMessageOrBuilderList();
      } else {
        return java.util.Collections.unmodifiableList(schemaMutations_);
      }
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder addSchemaMutationsBuilder() {
      return getSchemaMutationsFieldBuilder().addBuilder(
          io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.getDefaultInstance());
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder addSchemaMutationsBuilder(
        int index) {
      return getSchemaMutationsFieldBuilder().addBuilder(
          index, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.getDefaultInstance());
    }
    /**
     * <pre>
     * Collection of local catalog schema mutations to be applied.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation schemaMutations = 1;</code>
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder>
         getSchemaMutationsBuilderList() {
      return getSchemaMutationsFieldBuilder().getBuilderList();
    }
    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutationOrBuilder>
        getSchemaMutationsFieldBuilder() {
      if (schemaMutationsBuilder_ == null) {
        schemaMutationsBuilder_ = new com.google.protobuf.RepeatedFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation.Builder, io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutationOrBuilder>(
                schemaMutations_,
                ((bitField0_ & 0x00000001) != 0),
                getParentForChildren(),
                isClean());
        schemaMutations_ = null;
      }
      return schemaMutationsBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest)
  private static final io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcUpdateCatalogSchemaRequest>
      PARSER = new com.google.protobuf.AbstractParser<GrpcUpdateCatalogSchemaRequest>() {
    @java.lang.Override
    public GrpcUpdateCatalogSchemaRequest parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcUpdateCatalogSchemaRequest(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcUpdateCatalogSchemaRequest> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcUpdateCatalogSchemaRequest> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

