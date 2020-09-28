package lila.swiss

import akka.stream.scaladsl._
import reactivemongo.api.bson._

import lila.db.dsl._
import lila.user.User

// https://www.fide.com/FIDE/handbook/C04Annex2_TRF16.pdf
final class SwissTrf(
    sheetApi: SwissSheetApi,
    colls: SwissColls,
    baseUrl: lila.common.config.BaseUrl
)(implicit ec: scala.concurrent.ExecutionContext) {

  private type Bits      = List[(Int, String)]
  private type PlayerIds = Map[User.ID, Int]

  def apply(swiss: Swiss): Source[String, _] = Source futureSource {
    fetchPlayerIds(swiss) map { playerIds =>
      tournamentLines(swiss) concat sheetApi
        .source(swiss)
        .map((playerLine(swiss, playerIds) _).tupled)
        .map(formatLine)
    }
  }

  private def tournamentLines(swiss: Swiss) =
    Source(
      List(
        s"012 ${swiss.name}",
        s"022 $baseUrl/swiss/${swiss.id}",
        s"032 Lichess",
        s"042 ${dateFormatter print swiss.startsAt}",
        s"052 ${swiss.finishedAt ?? dateFormatter.print}",
        s"062 ${swiss.nbPlayers}",
        s"092 Individual: Swiss-System",
        s"102 $baseUrl/swiss",
        s"XXR ${swiss.settings.nbRounds}",
        s"XXC ${chess.Color.fromWhite(swiss.id.value(0).toInt % 2 == 0).name}1"
      )
    )

  private def playerLine(
      swiss: Swiss,
      playerIds: PlayerIds
  )(p: SwissPlayer, pairings: Map[SwissRound.Number, SwissPairing], sheet: SwissSheet): Bits =
    List(
      3                    -> "001",
      8                    -> playerIds.getOrElse(p.userId, 0).toString,
      (15 + p.userId.size) -> p.userId,
      52                   -> p.rating.toString,
      84                   -> f"${sheet.points.value}%1.1f"
    ) ::: {
      swiss.allRounds.zip(sheet.outcomes).flatMap { case (rn, outcome) =>
        val pairing = pairings get rn
        List(
          95 -> pairing.map(_ opponentOf p.userId).flatMap(playerIds.get).??(_.toString),
          97 -> pairing.map(_ colorOf p.userId).??(_.fold("w", "b")),
          99 -> {
            import SwissSheet._
            outcome match {
              case Absent  => "-"
              case Late    => "H"
              case Bye     => "U"
              case Draw    => "="
              case Win     => "1"
              case Loss    => "0"
              case Ongoing => "Z"
            }
          }
        ).map { case (l, s) => (l + (rn.value - 1) * 10, s) }
      }
    } ::: {
      p.absent && swiss.round.value < swiss.settings.nbRounds
    }.?? {
      List( // http://www.rrweb.org/javafo/aum/JaVaFo2_AUM.htm#_Unusual_info_extensions
        95 -> "0000",
        97 -> "",
        99 -> "-"
      ).map { case (l, s) => (l + swiss.round.value * 10, s) }
    }

  private def formatLine(bits: Bits): String =
    bits.foldLeft("") { case (acc, (pos, txt)) =>
      s"""$acc${" " * (pos - txt.length - acc.length)}$txt"""
    }

  private val dateFormatter = org.joda.time.format.DateTimeFormat forStyle "M-"

  private def fetchPlayerIds(swiss: Swiss): Fu[PlayerIds] =
    SwissPlayer
      .fields { p =>
        import BsonHandlers._
        colls.player
          .aggregateOne() { framework =>
            import framework._
            Match($doc(p.swissId -> swiss.id)) -> List(
              Sort(Descending(p.rating)),
              Group(BSONNull)("us" -> PushField(p.userId))
            )
          }
          .map {
            ~_.flatMap(_.getAsOpt[List[User.ID]]("us"))
          }
          .map {
            _.view.zipWithIndex.map { case (userId, index) =>
              (userId, index + 1)
            }.toMap
          }
      }
}
