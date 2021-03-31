package org.archive.webservices.ars.processing.jobs.shared

import io.archivesunleashed.ArchiveRecord
import org.apache.spark.rdd.RDD
import org.archive.helge.sparkling.Sparkling
import org.archive.helge.sparkling.io.HdfsIO
import org.archive.helge.sparkling.util.{RddUtil, SurtUtil}
import org.archive.webservices.ars.io.IOHelper
import org.archive.webservices.ars.model.{ArsCloudJobCategories, ArsCloudJobCategory}
import org.archive.webservices.ars.processing.{DerivationJobConf, ProcessingState}

abstract class NetworkAutJob extends AutJob {
  val SampleTopNNodes = 50

  val category: ArsCloudJobCategory = ArsCloudJobCategories.Network

  val sampleGraphFile: String = "sample-graph.tsv.gz"

  def srcDstFields: (String, String)

  override def runSpark(rdd: RDD[ArchiveRecord], outPath: String): Unit = {
    val data = df(rdd).cache

    val (srcField, dstField) = srcDstFields

    val hostEdges = data.rdd.flatMap { row =>
      val src = row.getAs[String](srcField)
      val dst = row.getAs[String](dstField)
      val srcHost = SurtUtil.validateHost(SurtUtil.fromUrl(src))
      val dstHost = SurtUtil.validateHost(SurtUtil.fromUrl(dst))
      if (srcHost.isDefined && dstHost.isDefined && srcHost.get != dstHost.get) {
        Iterator((srcHost.get, dstHost.get))
      } else Iterator.empty
    }.cache

    val nodes = hostEdges
      .flatMap {
        case (src, dst) =>
          Iterator((src, 1L), (dst, 1L))
      }
      .reduceByKey(_ + _)
      .sortBy(-_._2)
      .take(SampleTopNNodes)
      .map(_._1)
      .toSet
    val nodesBc = hostEdges.context.broadcast(nodes)

    val sample = hostEdges.filter {
      case (src, dst) =>
        val nodes = nodesBc.value
        nodes.contains(src) && nodes.contains(dst)
    }

    data.write
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
      .format("csv")
      .csv(outPath + "/_" + targetFile)

    RddUtil.saveAsTextFile(
      sample.map { case (s, d) => s"$s\t$d" },
      outPath + "/_" + sampleGraphFile)
  }

  override def checkSparkState(outPath: String): Option[Int] =
    super.checkSparkState(outPath).map { state =>
      if (!HdfsIO.exists(outPath + "/_" + sampleGraphFile + "/" + Sparkling.CompleteFlagFile))
        ProcessingState.Failed
      else state
    }

  override def postProcess(outPath: String): Boolean = super.postProcess(outPath) && {
    IOHelper.concatLocal(
      outPath + "/_" + sampleGraphFile,
      sampleGraphFile,
      _.endsWith(".tsv.gz"),
      compress = true,
      deleteSrcFiles = true,
      deleteSrcPath = true) { tmpFile =>
      val outFile = outPath + "/" + sampleGraphFile
      HdfsIO.copyFromLocal(tmpFile, outFile, move = true, overwrite = true)
      HdfsIO.exists(outFile)
    }
  }

  override def checkFinishedState(outPath: String): Option[Int] =
    super.checkFinishedState(outPath).map { state =>
      if (!HdfsIO.exists(outPath + "/" + sampleGraphFile)) ProcessingState.Failed
      else state
    }

  override def templateName: Option[String] = Some("jobs/NetworkExtraction")

  override def templateVariables(conf: DerivationJobConf): Seq[(String, Any)] = {
    val edges = HdfsIO
      .lines(conf.outputPath + relativeOutPath + "/" + sampleGraphFile)
      .map(_.split("\t", 2))
      .map {
        case Array(src, dst) =>
          (src, dst)
      }

    val nodes =
      edges.flatMap { case (src, dst) => Iterator(src, dst) }.distinct.sorted.zipWithIndex
    val nodeMap = nodes.toMap

    super.templateVariables(conf) ++ Seq("nodes" -> nodes.map {
      case (node, id) => (node.split(',').reverse.mkString("."), id)
    }, "edges" -> edges.map { case (src, dst) => (nodeMap(src), nodeMap(dst)) })
  }
}