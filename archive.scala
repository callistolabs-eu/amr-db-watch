//> using scala 3.3.4
//> using dep com.lihaoyi::upickle:4.4.3
//> using dep com.lihaoyi::os-lib:0.11.8

// AMR database archiver.
//
// Records which version of each AMR reference database was current on which date,
// with checksums. The heavy blobs are NOT mirrored: upstream keeps them versioned
// (NCBI holds every dated release back to 2022; ResFinder is a git repo). What is
// genuinely non-recoverable is the *record* — so that is what this keeps, plus a
// mirror of the small interpretable files the benchmark actually reasons about.
//
// Idempotent: re-running on an already-recorded version is a no-op.
//
//   scala-cli run archive.scala                 # all sources
//   scala-cli run archive.scala -- amrfinder    # one source

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.MessageDigest
import java.time.{Duration, Instant}
import scala.util.{Try, Using}

// ---------------------------------------------------------------- config

val Root = os.pwd
val SnapshotDir = Root / "snapshots"
val IndexFile = Root / "index.tsv"
val ChecksFile = Root / "checks.log"

/** Files small and interpretable enough to be worth mirroring. AMR.LIB (~103 MB of
  * HMMs) is deliberately excluded: it is upstream-stable and never read by hand. */
val AmrFinderMirror = Seq(
  "ReferenceGeneCatalog.txt",
  "ReferenceGeneHierarchy.txt",
  "AMRProt-mutation.tsv",
  "AMRProt-susceptible.tsv",
  "AMRProt-suppress.tsv"
)

val AmrFinderBase =
  "https://ftp.ncbi.nlm.nih.gov/pathogen/Antimicrobial_resistance/AMRFinderPlus/database"
val CardLatest = "https://card.mcmaster.ca/latest/data"
val ResFinderCommits =
  "https://api.bitbucket.org/2.0/repositories/genomicepidemiology/resfinder_db/commits?pagelen=1"

// ---------------------------------------------------------------- http

val http = HttpClient
  .newBuilder()
  .followRedirects(HttpClient.Redirect.NORMAL)
  .connectTimeout(Duration.ofSeconds(30))
  .build()

def request(url: String) =
  HttpRequest
    .newBuilder(URI.create(url))
    .timeout(Duration.ofMinutes(10))
    .header("User-Agent", "amr-db-archive (callistolabs.eu)")
    .GET()
    .build()

def getBytes(url: String): Array[Byte] =
  val res = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray())
  if res.statusCode() != 200 then
    sys.error(s"GET $url -> HTTP ${res.statusCode()}")
  res.body()

def getString(url: String): String = String(getBytes(url), "UTF-8")

/** Last-Modified as reported by the server, when present. */
def lastModified(url: String): Option[String] =
  Try {
    val res = http.send(request(url), HttpResponse.BodyHandlers.discarding())
    res.headers().firstValue("last-modified").orElse(null)
  }.toOption.flatMap(Option(_))

def sha256(bytes: Array[Byte]): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

// ---------------------------------------------------------------- manifest

case class FileEntry(name: String, bytes: Long, sha256: String)
object FileEntry:
  given upickle.default.ReadWriter[FileEntry] = upickle.default.macroRW

case class Manifest(
    source: String,
    version: String,
    releaseDate: Option[String],
    sourceUrl: String,
    checkedAt: String,
    mirrored: Seq[FileEntry],
    listing: Seq[String]
)
object Manifest:
  given upickle.default.ReadWriter[Manifest] = upickle.default.macroRW

def alreadyRecorded(source: String, version: String): Boolean =
  os.exists(SnapshotDir / source / version / "manifest.json")

/** Every check is logged, not just every release. Two reasons: it is evidence that
  * the record has no unobserved gaps, and the resulting commit keeps the repository
  * active — GitHub disables scheduled workflows after 60 days of inactivity, which
  * is shorter than the interval between some upstream releases. */
def logCheck(source: String, version: String, status: String): Unit =
  if !os.exists(ChecksFile) then
    os.write(ChecksFile, "checked_at\tsource\tversion\tstatus\n")
  os.write.append(ChecksFile, s"$now\t$source\t$version\t$status\n")

def record(m: Manifest, blobs: Seq[(String, Array[Byte])]): Unit =
  val dir = SnapshotDir / m.source / m.version
  os.makeDir.all(dir)
  blobs.foreach { case (name, bytes) => os.write.over(dir / name, bytes) }
  os.write.over(dir / "manifest.json", upickle.default.write(m, indent = 2))
  val row = Seq(m.checkedAt, m.source, m.version, m.releaseDate.getOrElse(""), m.sourceUrl)
  if !os.exists(IndexFile) then
    os.write(IndexFile, "checked_at\tsource\tversion\trelease_date\turl\n")
  os.write.append(IndexFile, row.mkString("\t") + "\n")
  logCheck(m.source, m.version, "new")
  println(s"  recorded ${m.source} ${m.version} (${blobs.size} files mirrored)")

def now: String = Instant.now().toString

// ---------------------------------------------------------------- sources

/** Directory entries of an NCBI FTP-over-HTTP index page. */
def ncbiEntries(url: String): Seq[(String, Option[Long])] =
  val row = """<a href="([^"?][^"]*)">[^<]*</a>\s*(\S+\s+\S+)?\s*([0-9.]+[KMG]?)?""".r
  row
    .findAllMatchIn(getString(url))
    .map(m => (m.group(1), Option(m.group(3)).flatMap(parseSize)))
    .filterNot(_._1.startsWith("/"))
    .toSeq

def parseSize(s: String): Option[Long] =
  val n = s.dropRight(if s.last.isLetter then 1 else 0)
  n.toDoubleOption.map { v =>
    val mult = s.last match
      case 'K' => 1024L
      case 'M' => 1024L * 1024
      case 'G' => 1024L * 1024 * 1024
      case _   => 1L
    (v * mult).toLong
  }

/** NCBI keeps every dated release under database/<major.minor>/<yyyy-mm-dd.n>/ */
def archiveAmrFinder(): Unit =
  println("amrfinderplus:")
  val minors = ncbiEntries(s"$AmrFinderBase/")
    .map(_._1)
    .filter(_.endsWith("/"))
    .map(_.stripSuffix("/"))
    .filter(_.headOption.exists(_.isDigit))
  val newestMinor = minors.maxBy { v =>
    val parts = v.split('.').map(_.toIntOption.getOrElse(0))
    (parts.lift(0).getOrElse(0), parts.lift(1).getOrElse(0))
  }
  val releases = ncbiEntries(s"$AmrFinderBase/$newestMinor/")
    .map(_._1)
    .filter(_.endsWith("/"))
    .map(_.stripSuffix("/"))
  if releases.isEmpty then sys.error(s"no releases under $newestMinor")
  val release = releases.max // yyyy-mm-dd.n sorts lexicographically
  val version = s"$newestMinor/$release".replace('/', '_')
  val base = s"$AmrFinderBase/$newestMinor/$release"

  if alreadyRecorded("amrfinderplus", version) then
    logCheck("amrfinderplus", version, "unchanged")
    println(s"  $version already recorded")
  else
    val listing = ncbiEntries(s"$base/")
    val blobs = AmrFinderMirror.flatMap { name =>
      Try(getBytes(s"$base/$name")).toOption.map(name -> _)
    }
    record(
      Manifest(
        source = "amrfinderplus",
        version = version,
        releaseDate = Some(release.takeWhile(_ != '.')),
        sourceUrl = s"$base/",
        checkedAt = now,
        mirrored = blobs.map((n, b) => FileEntry(n, b.length.toLong, sha256(b))),
        listing = listing.map((n, s) => s"$n\t${s.getOrElse(-1L)}")
      ),
      blobs
    )

/** CARD ships a single small tarball; version lives in card.json inside it. */
def archiveCard(): Unit =
  println("card:")
  val bytes = getBytes(CardLatest)
  val digest = sha256(bytes)
  val version = cardVersion(bytes).getOrElse(s"sha-${digest.take(12)}")
  if alreadyRecorded("card", version) then
    logCheck("card", version, "unchanged")
    println(s"  $version already recorded")
  else
    record(
      Manifest(
        source = "card",
        version = version,
        releaseDate = lastModified(CardLatest),
        sourceUrl = CardLatest,
        checkedAt = now,
        mirrored = Seq(FileEntry("card-data.tar.bz2", bytes.length.toLong, digest)),
        listing = Nil
      ),
      Seq("card-data.tar.bz2" -> bytes)
    )

/** Best-effort: read _version out of card.json without unpacking the whole archive. */
def cardVersion(tarball: Array[Byte]): Option[String] =
  Try {
    os.temp(tarball, suffix = ".tar.bz2")
  }.toOption.flatMap { tmp =>
    val out = Try(os.proc("tar", "-xjOf", tmp.toString, "./card.json").call(check = false).out.text())
    os.remove.all(tmp)
    out.toOption
      .flatMap(t => """"_version"\s*:\s*"([^"]+)"""".r.findFirstMatchIn(t))
      .map(_.group(1))
  }

/** ResFinder's database is a git repo: the commit hash *is* the version. */
def archiveResFinder(): Unit =
  println("resfinder:")
  val json = ujson.read(getString(ResFinderCommits))
  val head = json("values").arr.head
  val hash = head("hash").str
  val date = head("date").str.take(10)
  val version = s"$date-${hash.take(12)}"
  if alreadyRecorded("resfinder", version) then
    logCheck("resfinder", version, "unchanged")
    println(s"  $version already recorded")
  else
    record(
      Manifest(
        source = "resfinder",
        version = version,
        releaseDate = Some(date),
        sourceUrl =
          s"https://bitbucket.org/genomicepidemiology/resfinder_db/commits/$hash",
        checkedAt = now,
        mirrored = Nil,
        listing = Seq(s"commit\t$hash", s"message\t${head("message").str.linesIterator.next()}")
      ),
      Nil
    )

// ---------------------------------------------------------------- main

@main def run(sources: String*): Unit =
  val all = Map(
    "amrfinder" -> (() => archiveAmrFinder()),
    "card" -> (() => archiveCard()),
    "resfinder" -> (() => archiveResFinder())
  )
  val selected = if sources.isEmpty then all.keys.toSeq.sorted else sources
  var failed = 0
  selected.foreach { name =>
    all.get(name) match
      case None => System.err.println(s"unknown source: $name"); failed += 1
      case Some(fn) =>
        // One broken source must not stop the others: a missed check is data lost forever.
        Try(fn()).failed.foreach { e =>
          logCheck(name, "", "failed")
          System.err.println(s"$name FAILED: ${e.getMessage}")
          failed += 1
        }
  }
  if failed > 0 then sys.exit(1)
