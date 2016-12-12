package com.advancedtelematic.util

import java.nio.file.Files

import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.{HttpEntity, MediaTypes, Multipart}
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.advancedtelematic.common.DigestCalculator
import com.advancedtelematic.data.DataType.Commit
import com.advancedtelematic.treehub.http._
import com.advancedtelematic.treehub.object_store.{LocalFsBlobStore, ObjectStore}
import eu.timepit.refined.api.Refined
import org.genivi.sota.http.NamespaceDirectives
import org.scalatest.Suite

import scala.util.Random

object ResourceSpec {
  class ClientTObject(blobStr: String = Random.nextString(10)) {
    lazy val blob = blobStr.getBytes

    lazy val checkSum = DigestCalculator.digest()(blobStr)

    private lazy val (prefix, rest) = checkSum.splitAt(2)

    lazy val objectId = s"$prefix/$rest.commit"

    lazy val commit: Commit = Refined.unsafeApply(checkSum)

    lazy val formFile =
      BodyPart("file", HttpEntity(MediaTypes.`application/octet-stream`, blobStr.getBytes),
        Map("filename" -> s"$checkSum.commit"))

    lazy val form = Multipart.FormData(formFile)
  }

}

trait ResourceSpec extends ScalatestRouteTest with DatabaseSpec {
  self: Suite =>

  def apiUri(path: String): String = "/api/v2/" + path

  val testCore = new FakeCore()

  lazy val namespaceExtractor = NamespaceDirectives.defaultNamespaceExtractor.map(_.namespace)
  val objectStore = new ObjectStore(new LocalFsBlobStore(Files.createTempDirectory("treehub").toFile))

  lazy val routes = new TreeHubRoutes(Directives.pass,
    namespaceExtractor,
    testCore,
    namespaceExtractor, objectStore).routes
}


