package org.archive.webservices.ars.processing.jobs

import io.archivesunleashed.ArchiveRecord
import io.archivesunleashed.app.PDFInformationExtractor
import org.apache.spark.rdd.RDD
import org.archive.webservices.ars.model.ArsCloudJobCategories
import org.archive.webservices.ars.processing.jobs.shared.AutJob

object PdfInformationExtraction extends AutJob {
  val name = "Extract PDF information"
  val category = ArsCloudJobCategories.BinaryInformation
  val description =
    "This will output a single file with the following columns: crawl date, URL of the PDF file, filename, PDF extension, MIME type as provided by the web server, MIME type as detected by Apache TIKA, PDF MD5 hash and PDF SHA1 hash."

  val targetFile: String = "pdf-information.csv.gz"

  def df(rdd: RDD[ArchiveRecord]) = PDFInformationExtractor(rdd.pdfs())
}
