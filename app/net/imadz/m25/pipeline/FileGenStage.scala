package net.imadz.m25.pipeline

import net.imadz.infrastructure.connector.EncodingStrategy
import net.imadz.m25.component.{FileGenerator, GeneratedFile}

import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Paths}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * 文件生成阶段——将业务 items 编码为传输文件。
 *
 * 支持 XML / JSON / CSV / 加密文件等编码策略。
 */
class FileGenStage[Item](
    encoding: EncodingStrategy[Seq[Item], String],
    tempDir: String = System.getProperty("java.io.tmpdir"),
    fileExtension: String = ".xml"
)(implicit ec: ExecutionContext) extends FileGenerator[Item] {

  override def generate(items: Seq[Item], context: Map[String, Any]): Future[GeneratedFile] = {
    val batchId = context.getOrElse("batchId", UUID.randomUUID().toString).toString
    val fileName = s"$batchId$fileExtension"
    val localPath = Paths.get(tempDir, fileName).toString

    // Ensure temp dir exists
    Files.createDirectories(Paths.get(tempDir))

    val encoded = encoding.encode(items.toSeq)

    Future {
      val file = new File(localPath)
      val fos = new FileOutputStream(file)
      try {
        fos.write(encoded.getBytes("UTF-8"))
      } finally {
        fos.close()
      }

      GeneratedFile(
        localPath = localPath,
        fileName = fileName,
        byteSize = file.length(),
        encoding = fileExtension.stripPrefix(".")
      )
    }
  }
}

/**
 * 文件名由外部提供的文件生成器。
 * 适用于需要特定命名规则的场景（如银行要求特定文件名格式）。
 */
class TemplatedFileGenStage[Item](
    encoding: EncodingStrategy[Seq[Item], String],
    tempDir: String = System.getProperty("java.io.tmpdir"),
    /** 文件名模板：支持 {{batchId}} / {{timestamp}} */
    fileNameTemplate: String = "{{batchId}}.xml"
)(implicit ec: ExecutionContext) extends FileGenerator[Item] {

  override def generate(items: Seq[Item], context: Map[String, Any]): Future[GeneratedFile] = {
    val batchId = context.getOrElse("batchId", UUID.randomUUID().toString).toString
    val ts = System.currentTimeMillis()

    val fileName = fileNameTemplate
      .replace("{{batchId}}", batchId)
      .replace("{{timestamp}}", ts.toString)

    val localPath = Paths.get(tempDir, fileName).toString
    Files.createDirectories(Paths.get(tempDir))

    val encoded = encoding.encode(items.toSeq)

    Future {
      val file = new File(localPath)
      val fos = new FileOutputStream(file)
      try { fos.write(encoded.getBytes("UTF-8")) }
      finally { fos.close() }

      GeneratedFile(localPath, fileName, file.length(),
        fileName.substring(fileName.lastIndexOf('.') + 1))
    }
  }
}
