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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEntity.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcTypeReferences}
 */
public final class GrpcTypeReferences extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcTypeReferences)
    GrpcTypeReferencesOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcTypeReferences.newBuilder() to construct.
  private GrpcTypeReferences(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcTypeReferences() {
    references_ = java.util.Collections.emptyList();
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcTypeReferences();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcTypeReferences(
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
              references_ = new java.util.ArrayList<io.evitadb.externalApi.grpc.generated.GrpcReference>();
              mutable_bitField0_ |= 0x00000001;
            }
            references_.add(
                input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcReference.parser(), extensionRegistry));
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
        references_ = java.util.Collections.unmodifiableList(references_);
      }
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTypeReferences_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTypeReferences_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcTypeReferences.class, io.evitadb.externalApi.grpc.generated.GrpcTypeReferences.Builder.class);
  }

  public static final int REFERENCES_FIELD_NUMBER = 1;
  private java.util.List<io.evitadb.externalApi.grpc.generated.GrpcReference> references_;
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
   */
  @java.lang.Override
  public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcReference> getReferencesList() {
    return references_;
  }
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
   */
  @java.lang.Override
  public java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcReferenceOrBuilder> 
      getReferencesOrBuilderList() {
    return references_;
  }
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
   */
  @java.lang.Override
  public int getReferencesCount() {
    return references_.size();
  }
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcReference getReferences(int index) {
    return references_.get(index);
  }
  /**
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcReferenceOrBuilder getReferencesOrBuilder(
      int index) {
    return references_.get(index);
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
    for (int i = 0; i < references_.size(); i++) {
      output.writeMessage(1, references_.get(i));
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    for (int i = 0; i < references_.size(); i++) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, references_.get(i));
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcTypeReferences)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcTypeReferences other = (io.evitadb.externalApi.grpc.generated.GrpcTypeReferences) obj;

    if (!getReferencesList()
        .equals(other.getReferencesList())) return false;
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
    if (getReferencesCount() > 0) {
      hash = (37 * hash) + REFERENCES_FIELD_NUMBER;
      hash = (53 * hash) + getReferencesList().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcTypeReferences prototype) {
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
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcTypeReferences}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcTypeReferences)
      io.evitadb.externalApi.grpc.generated.GrpcTypeReferencesOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTypeReferences_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTypeReferences_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcTypeReferences.class, io.evitadb.externalApi.grpc.generated.GrpcTypeReferences.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcTypeReferences.newBuilder()
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
        getReferencesFieldBuilder();
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      if (referencesBuilder_ == null) {
        references_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
      } else {
        referencesBuilder_.clear();
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEntity.internal_static_io_evitadb_externalApi_grpc_generated_GrpcTypeReferences_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTypeReferences getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcTypeReferences.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTypeReferences build() {
      io.evitadb.externalApi.grpc.generated.GrpcTypeReferences result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcTypeReferences buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcTypeReferences result = new io.evitadb.externalApi.grpc.generated.GrpcTypeReferences(this);
      int from_bitField0_ = bitField0_;
      if (referencesBuilder_ == null) {
        if (((bitField0_ & 0x00000001) != 0)) {
          references_ = java.util.Collections.unmodifiableList(references_);
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.references_ = references_;
      } else {
        result.references_ = referencesBuilder_.build();
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcTypeReferences) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcTypeReferences)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcTypeReferences other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcTypeReferences.getDefaultInstance()) return this;
      if (referencesBuilder_ == null) {
        if (!other.references_.isEmpty()) {
          if (references_.isEmpty()) {
            references_ = other.references_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureReferencesIsMutable();
            references_.addAll(other.references_);
          }
          onChanged();
        }
      } else {
        if (!other.references_.isEmpty()) {
          if (referencesBuilder_.isEmpty()) {
            referencesBuilder_.dispose();
            referencesBuilder_ = null;
            references_ = other.references_;
            bitField0_ = (bitField0_ & ~0x00000001);
            referencesBuilder_ = 
              com.google.protobuf.GeneratedMessageV3.alwaysUseFieldBuilders ?
                 getReferencesFieldBuilder() : null;
          } else {
            referencesBuilder_.addAllMessages(other.references_);
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
      io.evitadb.externalApi.grpc.generated.GrpcTypeReferences parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcTypeReferences) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    private int bitField0_;

    private java.util.List<io.evitadb.externalApi.grpc.generated.GrpcReference> references_ =
      java.util.Collections.emptyList();
    private void ensureReferencesIsMutable() {
      if (!((bitField0_ & 0x00000001) != 0)) {
        references_ = new java.util.ArrayList<io.evitadb.externalApi.grpc.generated.GrpcReference>(references_);
        bitField0_ |= 0x00000001;
       }
    }

    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcReference, io.evitadb.externalApi.grpc.generated.GrpcReference.Builder, io.evitadb.externalApi.grpc.generated.GrpcReferenceOrBuilder> referencesBuilder_;

    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcReference> getReferencesList() {
      if (referencesBuilder_ == null) {
        return java.util.Collections.unmodifiableList(references_);
      } else {
        return referencesBuilder_.getMessageList();
      }
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public int getReferencesCount() {
      if (referencesBuilder_ == null) {
        return references_.size();
      } else {
        return referencesBuilder_.getCount();
      }
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReference getReferences(int index) {
      if (referencesBuilder_ == null) {
        return references_.get(index);
      } else {
        return referencesBuilder_.getMessage(index);
      }
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder setReferences(
        int index, io.evitadb.externalApi.grpc.generated.GrpcReference value) {
      if (referencesBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureReferencesIsMutable();
        references_.set(index, value);
        onChanged();
      } else {
        referencesBuilder_.setMessage(index, value);
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder setReferences(
        int index, io.evitadb.externalApi.grpc.generated.GrpcReference.Builder builderForValue) {
      if (referencesBuilder_ == null) {
        ensureReferencesIsMutable();
        references_.set(index, builderForValue.build());
        onChanged();
      } else {
        referencesBuilder_.setMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder addReferences(io.evitadb.externalApi.grpc.generated.GrpcReference value) {
      if (referencesBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureReferencesIsMutable();
        references_.add(value);
        onChanged();
      } else {
        referencesBuilder_.addMessage(value);
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder addReferences(
        int index, io.evitadb.externalApi.grpc.generated.GrpcReference value) {
      if (referencesBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureReferencesIsMutable();
        references_.add(index, value);
        onChanged();
      } else {
        referencesBuilder_.addMessage(index, value);
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder addReferences(
        io.evitadb.externalApi.grpc.generated.GrpcReference.Builder builderForValue) {
      if (referencesBuilder_ == null) {
        ensureReferencesIsMutable();
        references_.add(builderForValue.build());
        onChanged();
      } else {
        referencesBuilder_.addMessage(builderForValue.build());
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder addReferences(
        int index, io.evitadb.externalApi.grpc.generated.GrpcReference.Builder builderForValue) {
      if (referencesBuilder_ == null) {
        ensureReferencesIsMutable();
        references_.add(index, builderForValue.build());
        onChanged();
      } else {
        referencesBuilder_.addMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder addAllReferences(
        java.lang.Iterable<? extends io.evitadb.externalApi.grpc.generated.GrpcReference> values) {
      if (referencesBuilder_ == null) {
        ensureReferencesIsMutable();
        com.google.protobuf.AbstractMessageLite.Builder.addAll(
            values, references_);
        onChanged();
      } else {
        referencesBuilder_.addAllMessages(values);
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder clearReferences() {
      if (referencesBuilder_ == null) {
        references_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
      } else {
        referencesBuilder_.clear();
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public Builder removeReferences(int index) {
      if (referencesBuilder_ == null) {
        ensureReferencesIsMutable();
        references_.remove(index);
        onChanged();
      } else {
        referencesBuilder_.remove(index);
      }
      return this;
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReference.Builder getReferencesBuilder(
        int index) {
      return getReferencesFieldBuilder().getBuilder(index);
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReferenceOrBuilder getReferencesOrBuilder(
        int index) {
      if (referencesBuilder_ == null) {
        return references_.get(index);  } else {
        return referencesBuilder_.getMessageOrBuilder(index);
      }
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcReferenceOrBuilder> 
         getReferencesOrBuilderList() {
      if (referencesBuilder_ != null) {
        return referencesBuilder_.getMessageOrBuilderList();
      } else {
        return java.util.Collections.unmodifiableList(references_);
      }
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReference.Builder addReferencesBuilder() {
      return getReferencesFieldBuilder().addBuilder(
          io.evitadb.externalApi.grpc.generated.GrpcReference.getDefaultInstance());
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcReference.Builder addReferencesBuilder(
        int index) {
      return getReferencesFieldBuilder().addBuilder(
          index, io.evitadb.externalApi.grpc.generated.GrpcReference.getDefaultInstance());
    }
    /**
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcReference references = 1;</code>
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcReference.Builder> 
         getReferencesBuilderList() {
      return getReferencesFieldBuilder().getBuilderList();
    }
    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcReference, io.evitadb.externalApi.grpc.generated.GrpcReference.Builder, io.evitadb.externalApi.grpc.generated.GrpcReferenceOrBuilder> 
        getReferencesFieldBuilder() {
      if (referencesBuilder_ == null) {
        referencesBuilder_ = new com.google.protobuf.RepeatedFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcReference, io.evitadb.externalApi.grpc.generated.GrpcReference.Builder, io.evitadb.externalApi.grpc.generated.GrpcReferenceOrBuilder>(
                references_,
                ((bitField0_ & 0x00000001) != 0),
                getParentForChildren(),
                isClean());
        references_ = null;
      }
      return referencesBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcTypeReferences)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcTypeReferences)
  private static final io.evitadb.externalApi.grpc.generated.GrpcTypeReferences DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcTypeReferences();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcTypeReferences getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcTypeReferences>
      PARSER = new com.google.protobuf.AbstractParser<GrpcTypeReferences>() {
    @java.lang.Override
    public GrpcTypeReferences parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcTypeReferences(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcTypeReferences> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcTypeReferences> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcTypeReferences getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

