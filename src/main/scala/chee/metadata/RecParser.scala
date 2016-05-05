package chee.metadata

import better.files.File
import fastparse.all._
import chee.properties.{ Ident, LazyMap, Property }
import chee.util.parsing._
import RecFormat._
import RecElParser._

/** See https://www.gnu.org/software/recutils/manual/recutils.html#The-Rec-Format */
object RecElParser {
  /** Any line having an # (ASCII 0x23) character in the first column is
    * a comment line. comments in recfiles must be complete lines. */
  val comment: P[String] = P("#" ~ CharNotIn("\n").rep.!)

  /** A field name is a sequence of alphanumeric characters plus
    * underscores (_), starting with a letter or the character %. The
    * regular expression denoting a field name is:
    * [a-zA-Z%][a-zA-Z0-9_]* */
  val label: P0 = P(alphaPlus("%") ~ alphanumPlus("_").rep)

  /** The value of a field is a sequence of characters terminated by a
    * single newline character (\n). */
  val line: P0 = P(!StringIn("\\\n", "\n+", "\n") ~ AnyChar).rep(1)

  /** The sequence \n (newline) + (PLUS) and an optional _ (SPACE) is
    * interpreted as a newline. */
  val newLine: P[String] = P("\n" ~ "+" ~ (" ".?)).map(_ => "\n")

  /** It is possible to physically split a logical line by escaping a
    * newline with a backslash character. */
  val lineWrap: P[String] = P("\\" ~ "\n").map(_ => "")

  /** A field value */
  val value: P[String] = P((lineWrap | newLine | line.!).rep(1).map {
    case seq => seq.mkString
  })

  val field: P[Field] = P(Index ~ label.! ~ ":" ~ " ".? ~ value).map {
    case (i, l, v) => Field(l, v, i)
  }

  val recordEl: P[RecordEl] = P(!"%" ~ field)

  val descrEl: P[RecordEl] = P(&("%") ~ field)

  val recordComment: P[RecordEl] = P(Index ~ comment).map {
    case (i, c) => Comment(c, i)
  }
}

class RecParser[T](descriptor: Descriptor, f: Entry => Option[T]) {

  /** A record is a group of one or more fields written one after the
    * other. */
  lazy val record: P[Option[T]] =
    P(Index ~ !"#" ~ (recordComment | recordEl).rep(1, sep = "\n")).map {
      case (pos, fields) => f(Record(fields.toVector, descriptor, pos))
    }

  lazy val descript: P[Descriptor] =
    P(Index ~ !"#" ~ (recordComment | descrEl).rep(1, sep = "\n")).map {
      case (pos, fields) => Descriptor(fields.toVector, pos)
    }

  lazy val withDescr: P[Seq[T]] =
    P(descript).flatMap(d => new RecParser(d, f).allRecords.map(ts => f(d).toVector ++ ts))

  lazy val records: P[Seq[T]] = P(record.map {
    case x => x.toVector
  })

  lazy val recdesc: P[Seq[T]] = P("\n".rep ~ (records | withDescr))

  lazy val comments: P[Seq[T]] = P("\n".rep ~ Index ~ (comment.rep(sep = "\n"))).map {
    case (p, cs) => cs.foldLeft(Vector[T]()) { (v, s) => f(Comment(s, p)).toVector }
  }

  lazy val recdescWithComments: P[Seq[T]] =
    P(comments ~ recdesc).map {
      case (c1, db) => c1 ++ db
    }

  lazy val allRecords: P[Seq[T]] =
    P(recdescWithComments.rep(sep = "\n\n") ~ comments).map {
      case (x, c) if x.isEmpty => c
      case (x, c) => x.reduce(_ ++ _) ++ c
    }
}

final class DatabaseParser(descriptor: Descriptor = Descriptor.Empty)
    extends RecParser(descriptor, e => Some(e)) {
  lazy val rec: P[Database] = allRecords.map {
    case seq => seq.foldLeft(Database.Empty){
      case (d, r) => d + r
    }
  }
  def parse(str: String) = rec.parseAll(str.trim)
  def parseFile(f: File) = parse(f.contentAsString)
}

final class MapParser(descriptor: Descriptor = Descriptor.Empty)
    extends RecParser(descriptor, MapParser.mapper) {
  lazy val lazyMap: P[Stream[LazyMap]] = allRecords.map(_.toStream)

  def parse(str: String) = lazyMap.parseAll(str)
  def parseFile(f: File) = parse(f.contentAsString)
}


object MapParser {
  val mapper: Entry => Option[LazyMap] = {
    case r: Record =>
      Some(r.fields.groupBy(_.label).foldLeft(LazyMap())(_ + fieldToProperty.tupled(_)))
    case _ => None
  }

  private val fieldToProperty: (String, Vector[Field]) => Property =
    (name, fs) =>
      if (fs.drop(1).isEmpty) Ident(name.toLowerCase()) -> fs.head.value
      else Ident(name.toLowerCase()) -> fs.map(_.value).mkString("[", "][", "]")
}
