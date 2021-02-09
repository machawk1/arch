package org.archive.webservices.ars.processing.jobs

import io.archivesunleashed.ArchiveRecord
import io.archivesunleashed.app.VideoInformationExtractor
import org.apache.spark.rdd.RDD
import org.archive.webservices.ars.model.ArsCloudJobCategories
import org.archive.webservices.ars.processing.jobs.shared.AutJob

object VideoInformationExtraction extends AutJob {
  val name = "Extract video information"
  val category = ArsCloudJobCategories.BinaryInformation
  val description =
    "This will output a single file with the following columns: crawl date, URL of the video file, filename, video extension, MIME type as provided by the web server, MIME type as detected by Apache TIKA, video MD5 hash and video SHA1 hash."

  val targetFile: String = "video-information.csv.gz"

  def df(rdd: RDD[ArchiveRecord]) = VideoInformationExtractor(rdd.videos())
}
