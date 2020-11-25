package cromwell.backend.google.pipelines.common

import _root_.io.circe.generic.auto._
import _root_.io.circe.parser._
import cats.effect.IO
import cats.implicits._
import com.google.api.services.storage.StorageScopes
import com.google.cloud.storage.{BlobId, Storage, StorageOptions}
import cromwell.backend.google.pipelines.common.io.PipelinesApiDockerImageCacheDisk
import cromwell.cloudsupport.gcp.auth.GoogleAuthMode
import cromwell.filesystems.gcs.GcsPath
import cromwell.filesystems.gcs.GcsPathBuilder.ValidFullGcsPath

/**
  * This file contains logic related to mapping Docker image names to GCP disk images on which caches of those
  * images may be found. This may take significant time, so the best way to do it is during Cromwell startup.
  */
case class DockerImage(name: String)

case class PipelinesApiDockerImageCacheMapping(validImageCacheMap: Map[DockerImage, PipelinesApiDockerImageCacheDisk])

object PipelinesApiDockerImageCacheMapping extends PipelinesApiDockerImageCacheMappingOperations

// functionality extracted into the trait for testing purposes
protected trait PipelinesApiDockerImageCacheMappingOperations {
  case class ManifestFile(imageIdentifier: String, diskSizeGb: Int, images: List[DockerImage])

  def generateDockerImageCacheMapping(auth: GoogleAuthMode,
                                      dockerImageCacheManifestFiles: Option[List[ValidFullGcsPath]]): PipelinesApiDockerImageCacheMapping = {

    val validDockerImageCacheMapIO: IO[Map[DockerImage, PipelinesApiDockerImageCacheDisk]] = dockerImageCacheManifestFiles match {
      case None =>
        IO.pure(Map.empty[DockerImage, PipelinesApiDockerImageCacheDisk])
      case Some(manifestFilesPaths) =>
        val gcsClient = StorageOptions
          .newBuilder()
          .setCredentials(auth.credentials(Set(StorageScopes.DEVSTORAGE_READ_ONLY)))
          .build
          .getService
        val manifestFilesIo: IO[List[ManifestFile]] = manifestFilesPaths traverse { manifestFilePath => readDockerImageCacheManifestFileFromGCS(gcsClient, manifestFilePath) }

        // Given a List of ManifestFiles, return a mapping from a DockerImage to the ManifestFile that contains it.
        // If a DockerImage appears in multiple ManifestFiles, the last ManifestFile "wins".
        def buildDockerImageMap(manifestFiles: List[ManifestFile]): Map[DockerImage, ManifestFile] = {
          manifestFiles.foldLeft(Map.empty[DockerImage, ManifestFile]) { case (acc, manifestFile) =>
            manifestFile.images.foldLeft(acc) { case (a, image) => a + (image -> manifestFile)}
          }
        }

        // Per the above, because the last ManifestFile "wins", sort the ManifestFiles by decreasing order of disk image
        // size so we always use the smallest disk possible that contains a cache for a specified Docker image.
        val manifestFilesIoSortedDescendingSize: IO[List[ManifestFile]] = manifestFilesIo map { _.sortBy(- _.diskSizeGb) }

        // Transforms the value of the Map from a ManifestFile to a PipelinesApiDockerImageCacheDisk
        manifestFilesIoSortedDescendingSize map buildDockerImageMap map { _.map { case (k, v) =>
          k -> PipelinesApiDockerImageCacheDisk(v.imageIdentifier, v.diskSizeGb)
        }}
    }
    PipelinesApiDockerImageCacheMapping(validDockerImageCacheMapIO.unsafeRunSync())
  }

  def getReferenceInputsToMountedPathMappings(pipelinesApiReferenceFilesMapping: PipelinesApiReferenceFilesMapping,
                                              inputFiles: List[PipelinesApiInput]): Map[PipelinesApiInput, String] = {
    val gcsPathsToInputs = inputFiles.collect { case i if i.cloudPath.isInstanceOf[GcsPath] => (i.cloudPath.asInstanceOf[GcsPath].pathAsString, i) }.toMap
    pipelinesApiReferenceFilesMapping.validReferenceFilesMap.collect {
      case (path, disk) if gcsPathsToInputs.keySet.contains(s"gs://$path")  =>
        (gcsPathsToInputs(s"gs://$path"), s"${disk.mountPoint.pathAsString}/$path")
    }
  }

  protected def readDockerImageCacheManifestFileFromGCS(gcsClient: Storage, gcsPath: ValidFullGcsPath): IO[ManifestFile] = {
    val manifestFileBlobIo = IO { gcsClient.get(BlobId.of(gcsPath.bucket, gcsPath.path.substring(1))) }
    manifestFileBlobIo flatMap { manifestFileBlob =>
      val jsonStringIo = IO { manifestFileBlob.getContent().map(_.toChar).mkString }
      jsonStringIo.flatMap(jsonStr => IO.fromEither(decode[ManifestFile](jsonStr)))
    }
  }
}
