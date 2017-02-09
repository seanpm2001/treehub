package com.advancedtelematic.treehub.object_store

import java.io.File
import java.nio.file.Paths
import java.time.{Duration, Instant}

import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpResponse, StatusCodes, Uri}
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Source, StreamConverters}
import akka.util.ByteString
import cats.data.Xor
import com.advancedtelematic.common.DigestCalculator
import com.advancedtelematic.data.DataType.ObjectId
import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider}
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import org.genivi.sota.data.Namespace
import org.slf4j.LoggerFactory

import scala.async.Async._
import scala.concurrent.blocking
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class S3BlobStore(s3Credentials: S3Credentials)
                 (implicit ec: ExecutionContext, mat: Materializer) extends BlobStore {

  private val log = LoggerFactory.getLogger(this.getClass)

  private val bucketId = s3Credentials.bucketId

  private lazy val s3client = AmazonS3ClientBuilder.standard()
      .withCredentials(s3Credentials)
      .withRegion(s3Credentials.region)
      .build()

  override def store(namespace: Namespace, id: ObjectId, blob: Source[ByteString, _]): Future[Long] = {
    val filename = objectFilename(namespace, id)
    val tempFile = File.createTempFile(filename, ".tmp")

    // The s3 sdk requires us to specify the file size if using a stream
    // so we always need to cache the file into the filesystem before uploading
    val sink = FileIO.toPath(tempFile.toPath).mapMaterializedValue {
      _.flatMap { result =>
        if(result.wasSuccessful) {
          upload(tempFile, filename).andThen { case _ => Try(tempFile.delete()) }
        } else
          Future.failed(result.getError)
      }
    }

    blob.runWith(sink)
  }

  protected def upload(file: File, filename: String): Future[Long] = {
    val request = new PutObjectRequest(s3Credentials.bucketId, filename, file)
      .withCannedAcl(CannedAccessControlList.AuthenticatedRead)

    log.info(s"Uploading $filename to amazon s3")

    async {
      await(Future { blocking { s3client.putObject(request) } })
      val metadata = await(Future { blocking { s3client.getObjectMetadata(bucketId, filename) } })

      log.info(s"$filename uploaded to s3")

      metadata.getContentLength
    }
  }

  private def streamS3Bytes(namespace: Namespace, id: ObjectId): Future[Source[ByteString, _]] = {
    val filename = objectFilename(namespace, id)
    Future {
      blocking {
        val s3ObjectInputStream = s3client.getObject(bucketId, filename).getObjectContent
        StreamConverters.fromInputStream(() ⇒ s3ObjectInputStream)
      }
    }
  }

  override def buildResponse(namespace: Namespace, id: ObjectId): Future[HttpResponse] = {
    streamS3Bytes(namespace, id).map(buildResponseFromBytes)
  }

  override def readFull(namespace: Namespace, id: ObjectId): Future[ByteString] = {
    val filename = objectFilename(namespace, id)
    val is = s3client.getObject(bucketId, filename).getObjectContent
    val source = StreamConverters.fromInputStream(() => is)
    source.runFold(ByteString.empty)(_ ++ _)
  }

  override def exists(namespace: Namespace, id: ObjectId): Future[Boolean] = {
    val filename = objectFilename(namespace, id)
    Future { blocking { s3client.doesObjectExist(bucketId, filename) } }
  }

  private def namespaceDir(namespace: Namespace): String =
    DigestCalculator.digest()(namespace.get)

  private def objectFilename(namespace: Namespace, objectId: ObjectId): String =
    objectId.path(Paths.get(namespaceDir(namespace))).toString
}

class S3Credentials(accessKey: String, secretKey: String, val bucketId: String, val region: Regions)
  extends AWSCredentials with AWSCredentialsProvider {
  override def getAWSAccessKeyId: String = accessKey

  override def getAWSSecretKey: String = secretKey

  override def refresh(): Unit = ()

  override def getCredentials: AWSCredentials = this
}
