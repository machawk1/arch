package org.archive.webservices.ars.processing.jobs

import io.archivesunleashed.app.WebPagesExtractor
import org.archive.helge.sparkling.io.HdfsIO
import org.archive.helge.sparkling.util.{IteratorUtil, RddUtil}
import org.archive.helge.sparkling.warc.WarcLoader
import org.archive.webservices.ars.io.AutRecordLoader
import org.archive.webservices.ars.processing._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

object WebPagesExtraction extends ChainedJob {
  val id = "WebPagesExtraction"
  val name = "Extract webpages"
  val relativeOutPath = s"/$id"

  addJob(new PartialDerivationJob(_) with SparkJob {
    def succeeded(conf: DerivationJobConf): Boolean = HdfsIO.exists(conf.outputPath + relativeOutPath + "/_SUCCESS")

    def run(conf: DerivationJobConf): Future[Boolean] = {
      SparkJobManager.context.map { _ =>
        // cf. https://github.com/archivesunleashed/aut/blob/5d2bf57c8bb9e19b60df6c85fff28501b276d690/src/main/scala/io/archivesunleashed/app/CommandLineApp.scala#L286
        val df = RddUtil.loadBinary(conf.inputPath, decompress = false, close = false) { (filename, in) =>
          IteratorUtil.cleanup(WarcLoader.load(in).map(AutRecordLoader.fromWarc(filename, _, bufferBytes = true)), in.close)
        }.webpages()
        WebPagesExtractor(df).write.option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ").format("csv").csv(conf.outputPath + relativeOutPath)
        succeeded(conf)
      }
    }

    override def history(conf: DerivationJobConf): DerivationJobInstance = {
      val instance = super.history(conf)
      val started = HdfsIO.exists(conf.outputPath + relativeOutPath)
      if (started) {
        val completed = succeeded(conf)
        instance.state = if (completed) ProcessingState.Finished else ProcessingState.Failed
      }
      instance
    }
  })

  addJob(new PartialDerivationJob(_) with GenericJob {
    def run(conf: DerivationJobConf): Future[Boolean] = Future {
      import sys.process._
      val outPath = conf.outputPath + relativeOutPath
      Try {
        Seq("/bin/sh", "-c", "tar fczP " + outPath + "/out.tar.gz " + outPath + "/*.csv --remove-files").! == 0
      }.getOrElse(false)
    }

    override def history(conf: DerivationJobConf): DerivationJobInstance = {
      val instance = super.history(conf)
      val outPath = conf.outputPath + relativeOutPath
      if (HdfsIO.exists(outPath + "/out.tar.gz")) {
        instance.state = if (HdfsIO.files(outPath + "/*.csv").isEmpty) ProcessingState.Finished else ProcessingState.Failed
      }
      instance
    }

    override def templateVariables(conf: DerivationJobConf): Seq[(String, Any)] = {
      val size = HdfsIO.files(conf.outputPath + relativeOutPath + "/*.tar.gz").map(HdfsIO.length).sum
      Seq(
        "resultSize" -> size
      )
    }
  })
}
