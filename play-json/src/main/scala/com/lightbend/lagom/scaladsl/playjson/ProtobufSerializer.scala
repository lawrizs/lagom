/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.scaladsl.playjson

import scalapb.GeneratedMessage
import scalapb.GeneratedMessageCompanion

import scala.reflect.ClassTag

object ProtobufSerializer {

  /**
   * Create a serializer for the PlayJsonSerializationRegistry, describes how a specific class can be read and written
   * as a protobuf binary message
   */
  def apply[T <: GeneratedMessage : GeneratedMessageCompanion : ClassTag]: ProtobufSerializer[T] =
    ProtobufSerializerImpl(implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]], implicitly[GeneratedMessageCompanion[T]])

  /**
   * Create a serializer for the PlayJsonSerializationRegistry, describes how a specific class can be read and written
   * as a protobuf binary message
   */
  def apply[T <: GeneratedMessage: ClassTag](protobufCompanion: GeneratedMessageCompanion[T]): ProtobufSerializer[T] =
    ProtobufSerializerImpl(implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]], protobufCompanion)

  private[lagom] case class ProtobufSerializerImpl[T <: GeneratedMessage](
      entityClass: Class[T],
      protobufCompanion: GeneratedMessageCompanion[T]
  ) extends ProtobufSerializer[T]

}

/**
 * Describes how to serialize and deserialize a type using Protobuf
 */
sealed trait ProtobufSerializer[T <: GeneratedMessage] {
  // the reason we need it over Format is to capture the type here
  def entityClass: Class[T]

  /** A protobuf companion object, which is used for serialization/deserialization */
  def protobufCompanion: GeneratedMessageCompanion[T]
}
