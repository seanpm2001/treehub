package com.advancedtelematic.treehub.repo_metrics

import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.{TestActorRef, TestKitBase}
import cats.data.Xor
import com.advancedtelematic.treehub.object_store.{LocalFsBlobStore, ObjectStore}
import com.advancedtelematic.treehub.repo_metrics.StorageUpdate.Update
import com.advancedtelematic.util.{DatabaseSpec, TreeHubSpec}
import org.genivi.sota.messaging.MessageBus
import org.genivi.sota.messaging.Messages.ImageStorageUsage

import scala.concurrent.duration._

class StorageUpdateSpec extends TreeHubSpec with DatabaseSpec with TestKitBase  {
  override implicit lazy val system: ActorSystem = ActorSystem("StorageUpdateSpec")

  import system.dispatcher

  implicit val mat = ActorMaterializer()

  lazy val localFsDir = Files.createTempDirectory("StorageUpdateSpec")

  lazy val namespaceDir = Files.createDirectories(Paths.get(s"${localFsDir.toAbsolutePath}/${defaultNs.get}"))

  lazy val objectStore = new ObjectStore(new LocalFsBlobStore(localFsDir.toFile))

  lazy val messageBus = MessageBus.publisher(system, config) match {
    case Xor.Right(mbp) => mbp
    case Xor.Left(err) => throw err
  }

  lazy val subject = TestActorRef(new StorageUpdate(messageBus, objectStore))

  system.eventStream.subscribe(testActor, classOf[ImageStorageUsage])

  test("sends update message to bus") {
    val text = "some text, more text"

    Files.write(Paths.get(namespaceDir.toString, "somefile.txt"), text.toCharArray.map(_.toByte))

    subject ! Update(defaultNs)

    expectMsgPF(10.seconds, "message with len == text.length") {
      case p @ ImageStorageUsage(ns, _, len) if (ns == defaultNs) && (len == text.length) => p
    }
  }

  test("recovers from errors") {
    subject ! Update(defaultNs.copy(get = "notexistent"))

    expectNoMsg(1.seconds)

    subject ! Update(defaultNs)

    expectMsgType[ImageStorageUsage](5.seconds)
  }
}
