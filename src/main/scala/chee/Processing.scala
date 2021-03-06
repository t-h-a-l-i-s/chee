package chee

import CheeApi.PubkeySecret
import chee.crypto.{ Algorithm, CheeCrypt, FileProcessor }
import chee.query.Index
import chee.query.Index.UpdateParam
import org.bouncycastle.openpgp.PGPPublicKey
import scala.util.Try
import chee.properties._
import chee.properties.MapGet._
import com.sksamuel.scrimage._
import com.sksamuel.scrimage.nio._
import better.files._

object Processing {

  // -- image procesing ----------------------------------------------------------------------------

  private val image: MapGet[Option[Image]] = existingPath.map {
    case Some(f) => Try(Image.fromPath(f.path)).toOption
    case _ => None
  }

  val targetWidth = Ident.width.in("target")
  val targetHeight = Ident.height.in("target")

  private def setTargetSize(size: Size): MapGet[Unit] =
    modify(m => m +
      (targetWidth -> size.width.toString) +
      (targetHeight -> size.height.toString))

  private val removeTargetSize =
    modify(lm => lm remove targetWidth remove targetHeight)


  private def makeOut(size: Size, outFile: MapGet[File]): MapGet[File] =
    for {
      _ <- setTargetSize(size)
      f <- outFile
      _ <- removeTargetSize
    } yield f

  private val originProps = Ident.imageProperties.filterNot(id => id == Ident.width || id == Ident.height)

  def originMapping: Ident => Ident = id => id.in("origin")

  def imageOverlay(outFile: Option[File]): MapGet[Boolean] = outFile match {
    case None => set(LazyMap()).map(_ => false)
    case Some(out) =>
      val mod = modify { omap =>
        val map = originProps.foldLeft(LazyMap.fromFile(out)) { (m, id) =>
          m addVirtual(VirtualProperty.defaults.alias(id -> originMapping(id)))
        }
        map ++ omap.mapIdents(originMapping)
      }
      mod.map(_ => true)
  }

  private def processImage(outFile: MapGet[File])(p: Image => Image): MapGet[Option[File]] =
    outFile.flatMap { out =>
      val success = unit(Some(out))
      if (out.exists) success
      else image.flatMap {
        case Some(img) =>
          out.parent.createDirectories()
          p(img).output(out.path)(JpegWriter())
          success
        case None =>
          unit(None)
      }
    }

  def cover(size: Size, outFile: MapGet[File], method: ScaleMethod = ScaleMethod.FastScale): MapGet[Option[File]] =
    processImage(makeOut(size, outFile)) { img =>
      img.cover(size.width, size.height)
    }

  def scaleTo(size: Size, outFile: MapGet[File], method: ScaleMethod = ScaleMethod.Bicubic): MapGet[Option[File]] =
    processImage(makeOut(size, outFile)) { img =>
      img.scaleTo(size.width, size.height, method)
    }

  def scaleByFactor(factor: Double, outFile: MapGet[File], method: ScaleMethod = ScaleMethod.Bicubic): MapGet[Option[File]] =
    pair(value(Ident.width), value(Ident.height)).flatMap {
      case (Some(w), Some(h)) =>
        scaleTo(Size((w.toInt * factor).toInt, (h.toInt * factor).toInt), outFile, method)
      case _ =>
        unit(None)
    }

  def scaleMaxLen(maxlen: Int, outFile: MapGet[File], method: ScaleMethod = ScaleMethod.Bicubic): MapGet[Option[File]] =
    pair(value(Ident.width), value(Ident.height)).flatMap {
      case (Some(w), Some(h)) =>
        val max = math.max(w.toInt, h.toInt)
        if (max <= maxlen) MapGet.path.map(Some(_))
        else {
          val factor = maxlen.toDouble / max
          scaleTo(Size((w.toInt * factor).toInt, (h.toInt * factor).toInt), outFile, method)
        }
      case _ =>
        unit(None)
    }


  // -- encryption ----------------------------------------------------------------------------

  val originPath = originMapping(Ident.path)

  private val originFile: MapGet[File] =
    valueForce(originPath).map(File(_))

  private def originPathIndexed(index: Index): MapGet[Boolean] =
    index.exists(Condition.lookup(Comp.Eq, Ident.path, originPath)).map(_.get)

  private def cryptFile(outFile: MapGet[File], cf: (File, File) => Unit, skip: MapGet[Boolean]) =
    pair(existingPath.whenNot(skip), outFile).flatMap {
      case (Some(Some(in)), out) =>
        if (!out.exists) cf(in, out)
        add(originPath -> in.pathAsString, Ident.path -> out.pathAsString).map(_ => true)
      case _ =>
        unit(false)
    }

  /** Encrypts the input file to `outFile' and updates the `path'
    * property to the new encrypted file. The original path property
    * is saved to `origin-path'. */
  def encryptPubkey(key: PGPPublicKey, outFile: MapGet[File]): MapGet[Boolean] =
    cryptFile(outFile, FileProcessor.encryptPubkey(_, key, _), CheeCrypt.isEncrypted)

  /** Encrypts the input file to `outFile' and updates the `path'
    * property to the new encrypted file. The original path property
    * is saved to `origin-path'. */
  def encryptPassword(passphrase: Array[Char], algo: Algorithm, outFile: MapGet[File]): MapGet[Boolean] =
    cryptFile(outFile, FileProcessor.encryptSymmetric(_, _, passphrase, algo), CheeCrypt.isEncrypted)

  /** Decrypts the input file to `outFile' and updates the `path'
    * property to the new decrypted file. The original path property
    * is saved to `origin-path'. */
  def decryptPubkey(keyFile: File, pass: Array[Char], outFile: MapGet[File]): MapGet[Boolean] =
    cryptFile(outFile, FileProcessor.decryptPubkey(_, keyFile, pass, _), CheeCrypt.isNotEncrypted)

  /** Decrypts the input file to `outFile' and updates the `path'
    * property to the new decrypted file. The original path property
    * is saved to `origin-path'. */
  def decryptPassword(passphrase: Array[Char], outFile: MapGet[File]): MapGet[Boolean] =
    cryptFile(outFile, FileProcessor.decryptSymmetric(_, _, passphrase), CheeCrypt.isNotEncrypted)

  /** Decrypts the file either with the given password or secret key. If
    * one is not given, those files are skipped. If both are not
    * given, an exception is thrown. */
  def decryptFile(pubSecret: Option[PubkeySecret], passphrase: Option[Array[Char]], outFile: MapGet[File]): MapGet[Boolean] = {
    import CheeCrypt._
    if (pubSecret.isEmpty && passphrase.isEmpty) {
      throw UserError("Either a secret key or passphrase muste be given!")
    }
    value(VirtualProperty.idents.encrypted).flatMap {
      case Some(`passwordEncryptExtension`) if passphrase.isDefined =>
        decryptPassword(passphrase.get, outFile)
      case Some(`publicKeyEncryptExtension`) if pubSecret.isDefined =>
        val s = pubSecret.get
        decryptPubkey(s.keyFile, s.keyPass, outFile)
      case _ => unit(false)
    }
  }

  /** Postprocess encryption/decryption.
    *
    * Deletes the old file. If the old file is indexed, its path
    * property is updated to reflect the new existing file. */
  def cryptInplacePostProcess(index: Index): MapGet[Boolean] =
    tuple3(path, originFile, originPathIndexed(index)).flatMap {
      case (newFile, oldFile, indexed) if newFile.exists =>
        if (indexed) {
          modify { m =>
            val updateCond = Condition.lookup(Comp.Eq, Ident.path, originPath)
            val (next, success) = index.updateOne(UpdateParam(unit(Seq(Ident.path)), updateCond)).run(m)
            if (success.get && oldFile.exists) oldFile.delete()
            next.add(originPath -> oldFile.pathAsString)
          } map (_ => true)
        } else {
          if (oldFile.exists) oldFile.delete()
          unit(true)
        }
      case _ =>
        unit(false)
    }

}
