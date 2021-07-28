package org.archive.webservices.ars.processing.jobs

import java.io.PrintStream

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.desc
import org.apache.spark.sql.{Dataset, Row}
import org.archive.helge.sparkling.io.HdfsIO
import org.archive.helge.sparkling.warc.WarcRecord
import org.archive.webservices.ars.aut.{AutLoader, AutUtil}
import org.archive.webservices.ars.model.{ArchJobCategories, ArchJobCategory}
import org.archive.webservices.ars.processing.DerivationJobConf
import org.archive.webservices.ars.processing.jobs.shared.AutJob
import org.archive.webservices.ars.util.Common

object DomainFrequencyExtraction extends AutJob[(String, Long)] {
  val name = "Domain frequency"
  val category: ArchJobCategory = ArchJobCategories.Collection
  val description =
    "Create a CSV with the following columns: domain and count."

  val targetFile: String = "domain-frequency.csv.gz"

  override def printToOutputStream(out: PrintStream): Unit = out.println("domain,count")

  override def df(rdd: RDD[(String, Long)]): Dataset[Row] = {
    val rows = rdd
      .reduceByKey(_ + _)
      .map {
        case (domain, count) =>
          Row(domain, count)
      }
    AutLoader.domainFrequency(rows).orderBy(desc("count"))
  }

  override def prepareRecords(rdd: RDD[WarcRecord]): RDD[(String, Long)] = {
    rdd
      .flatMap { r =>
        Common.tryOrElse[Option[(String, Long)]](None) {
          r.http.filter(AutUtil.validPage(r, _)).map { _ =>
            val url = AutUtil.url(r)
            (AutUtil.extractDomainRemovePrefixWWW(url), 1L)
          }
        }
      }
  }

  override def templateName: Option[String] = Some("jobs/DomainFrequencyExtraction")

  override def templateVariables(conf: DerivationJobConf): Seq[(String, Any)] = {
    val topDomains = HdfsIO
      .lines(conf.outputPath + relativeOutPath + "/" + targetFile, 11)
      .drop(1)
      .flatMap { line =>
        val comma = line.lastIndexOf(',')
        if (comma < 0) None
        else
          Some {
            val (domain, freq) =
              (line.take(comma).stripPrefix("\"").stripSuffix("\""), line.drop(comma + 1))
            (domain, freq.toInt)
          }
      }
    super.templateVariables(conf) ++ Seq("topDomains" -> topDomains)
  }
}
