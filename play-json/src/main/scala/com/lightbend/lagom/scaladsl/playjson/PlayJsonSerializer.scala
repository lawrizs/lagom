/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.scaladsl.playjson

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.serialization.BaseSerializer
import akka.serialization.SerializerWithStringManifest
import play.api.libs.json._
import scalapb.GeneratedMessage
import scalapb.GeneratedMessageCompanion

import scala.annotation.tailrec
import scala.util.Try

/**
 * Internal API
 *
 * Akka serializer using the registered play-json serializers and migrations
 */
private[lagom] final class PlayJsonSerializer(val system: ExtendedActorSystem, registry: JsonSerializerRegistry)
    extends SerializerWithStringManifest
    with BaseSerializer {
  import Compression._

  private val charset        = StandardCharsets.UTF_8
  private val log            = Logging.getLogger(system, getClass)
  private val conf           = system.settings.config.getConfig("lagom.serialization.json")
  private val isDebugEnabled = log.isDebugEnabled

  private val compressLargerThan: Long = conf.getBytes("compress-larger-than")

  /** maps a manifestClassName to a suitable play-json Format */
  private val formatters: Map[String, Format[AnyRef]] = {
    registry.serializers
      .map((entry: JsonSerializer[_]) => (entry.entityClass.getName, entry.format.asInstanceOf[Format[AnyRef]]))
      .toMap
  }

  /** maps a manifestClassName to the serializer provided by the user */
  private val serializers: Map[String, JsonSerializer[_]] = {
    registry.serializers.map { entry =>
      entry.entityClass.getName -> entry
    }.toMap
  }

  /** maps a manifestClassName to the protobuf serializer provided by the user */
  private val protobufSerializers: Map[String, ProtobufSerializer[_]] = {
    registry.protobufSerializers.map { entry =>
      entry.entityClass.getName -> entry
    }.toMap
  }

  private def migrations: Map[String, JsonMigration] = registry.migrations

  override def manifest(o: AnyRef): String = {
    val className = o.getClass.getName
    migrations.get(className) match {
      case Some(migration) => className + "#" + migration.currentVersion
      case None            => className
    }
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    val startTime = if (isDebugEnabled) System.nanoTime else 0L

    val (_, manifestClassName: String) = parseManifest(manifest(o))

    // Try to find a protobuf serializer first
    val protoBufSerializerOpt = protobufSerializers.get(manifestClassName)

    // Serialize to bytes
    val result = protoBufSerializerOpt match {
      case Some(protoSerializer) =>
        // Convert a protobuf message to bytes
        val companion = protoSerializer.protobufCompanion.asInstanceOf[GeneratedMessageCompanion[GeneratedMessage]]
        val bytes     = companion.toByteArray(o.asInstanceOf[GeneratedMessage])

        // Compress, or pass raw bytes
        protoSerializer match {
          case ProtobufSerializer.CompressedProtobufSerializerImpl(_, _) if bytes.length > compressLargerThan =>
            compress(bytes)
          case _ => bytes
        }

      case None =>
        // Handle with the standard
        val format = formatters.getOrElse(
          manifestClassName,
          throw new RuntimeException(s"Missing play-json serializer for [$manifestClassName]")
        )

        val json               = format.writes(o)
        val bytes: Array[Byte] = Json.stringify(json).getBytes(charset)

        serializers(manifestClassName) match {
          case JsonSerializer.CompressedJsonSerializerImpl(_, _) if bytes.length > compressLargerThan => compress(bytes)
          case _                                                                                      => bytes
        }
    }

    if (isDebugEnabled) {
      val durationMicros = (System.nanoTime - startTime) / 1000

      log.debug(
        "Serialization of [{}] took [{}] µs, size [{}] bytes",
        o.getClass.getName,
        durationMicros,
        result.length
      )
    }
    result
  }

  override def fromBinary(storedBytes: Array[Byte], manifest: String): AnyRef = {
    val startTime = if (isDebugEnabled) System.nanoTime else 0L

    val (fromVersion: Int, manifestClassName: String) = parseManifest(manifest)

    // Try to find a protobuf serializer first
    val protoBufSerializerOpt = protobufSerializers.get(manifestClassName)

    // Deserialize using protobuf or JSON deserializer
    val result: AnyRef = protoBufSerializerOpt match {
      case Some(protoSerializer) =>
        // Get the bytes, zipped, or not zipped
        val bytes =
          if (isGZipped(storedBytes))
            Try { decompress(storedBytes) }.getOrElse(storedBytes) // Back-off to raw bytes in case of failutes
          else
            storedBytes

        // Deserialize using protobuf
        protoSerializer.protobufCompanion.parseFrom(bytes).asInstanceOf[AnyRef]

      case None =>
        val renameMigration = migrations.get(manifestClassName)

        val migratedManifest = renameMigration match {
          case Some(migration) if fromVersion < migration.currentVersion =>
            migration.transformClassName(fromVersion, manifestClassName)
          case Some(migration) if fromVersion == migration.currentVersion =>
            manifestClassName
          case Some(migration) if fromVersion <= migration.supportedForwardVersion =>
            migration.transformClassName(fromVersion, manifestClassName)
          case Some(migration) if fromVersion > migration.supportedForwardVersion =>
            throw new IllegalStateException(
              s"Migration supported version ${migration.supportedForwardVersion} is " +
                s"behind version $fromVersion of deserialized type [$manifestClassName]"
            )
          case None => manifestClassName
        }

        val transformMigration = migrations.get(migratedManifest)

        val format = formatters.getOrElse(
          migratedManifest,
          throw new RuntimeException(
            s"Missing play-json serializer for [$migratedManifest], " +
              s"defined are [${formatters.keys.mkString(", ")}]"
          )
        )

        // Get the bytes, zipped, or not zipped
        val bytes =
          if (isGZipped(storedBytes))
            decompress(storedBytes)
          else
            storedBytes

        val json = Json.parse(bytes) match {
          case jsValue: JsValue => jsValue
          case other =>
            throw new RuntimeException(
              "Unexpected serialized json data. " +
                s"Expected a JSON object, but was [${other.getClass.getName}]"
            )
        }

        val migratedJson = transformMigration match {
          case None                                                       => json
          case Some(migration) if fromVersion == migration.currentVersion => json
          case Some(migration) if fromVersion <= migration.supportedForwardVersion =>
            json match {
              case js: JsObject =>
                migration.transform(fromVersion, js)
              case js: JsValue =>
                migration.transformValue(fromVersion, js)
            }
        }

        format.reads(migratedJson) match {
          case JsSuccess(obj, _) => obj
          case JsError(errors) =>
            throw new JsonSerializationFailed(
              s"Failed to de-serialize bytes with manifest [$migratedManifest]",
              errors,
              migratedJson
            )
        }
    }

    if (isDebugEnabled) {
      val durationMicros = (System.nanoTime - startTime) / 1000

      log.debug(
        "Deserialization of [{}] took [{}] µs, size [{}] bytes",
        manifest,
        durationMicros,
        storedBytes.length
      )
    }
    result
  }

  private def parseManifest(manifest: String) = {
    val i                 = manifest.lastIndexOf('#')
    val fromVersion       = if (i == -1) 1 else manifest.substring(i + 1).toInt
    val manifestClassName = if (i == -1) manifest else manifest.substring(0, i)
    (fromVersion, manifestClassName)
  }
}

// This code is copied from JacksonJsonSerializer
private[lagom] object Compression {
  private final val BufferSize = 1024 * 4

  def compress(bytes: Array[Byte]): Array[Byte] = {
    val bos = new ByteArrayOutputStream(BufferSize)
    val zip = new GZIPOutputStream(bos)
    try zip.write(bytes)
    finally zip.close()
    bos.toByteArray
  }

  def decompress(bytes: Array[Byte]): Array[Byte] = {
    val in     = new GZIPInputStream(new ByteArrayInputStream(bytes))
    val out    = new ByteArrayOutputStream()
    val buffer = new Array[Byte](BufferSize)

    @tailrec def readChunk(): Unit = in.read(buffer) match {
      case -1 => ()
      case n =>
        out.write(buffer, 0, n)
        readChunk()
    }

    try readChunk()
    finally in.close()
    out.toByteArray
  }

  def isGZipped(bytes: Array[Byte]): Boolean = {
    (bytes != null) && (bytes.length >= 2) &&
    (bytes(0) == GZIPInputStream.GZIP_MAGIC.toByte) &&
    (bytes(1) == (GZIPInputStream.GZIP_MAGIC >> 8).toByte)
  }
}
