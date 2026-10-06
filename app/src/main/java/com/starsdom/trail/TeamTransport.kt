package com.starsdom.trail

// 队伍传输: the server's team routes (openapi.yaml), one method each, and the team's live stream. [TeamSession] reaches
// the network only through it; [HttpTeamTransport] wraps the generated client and its WebSocket.

import com.starsdom.trail.net.client.BaseApi
import com.starsdom.trail.net.model.JoinRequestDto
import com.starsdom.trail.net.model.SharingDto
import com.starsdom.trail.net.model.TeamRequestDto
import com.starsdom.trail.net.wire
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.ktor.util.reflect.typeInfo
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** What the live stream says: it's up ([Open], once, first), or what changed ([Change], to [mergeTeam]). */
sealed interface Live {
  data object Open : Live
  data class Change(val team: Team) : Live
}

/**
 * Every failure is an [OfflineError]: the server's code (team_not_found: not a member, team_ended, unauthorized) or the
 * network's (offline, timeout).
 */
interface TeamTransport {
  suspend fun create(account: Account): Team
  suspend fun card(account: Account, code: String): TeamCard
  suspend fun join(account: Account, code: String): Team
  /** The team with what's stored after [after] (its cursor). */
  suspend fun team(account: Account, team: Long, after: Long): Team
  suspend fun leave(account: Account, team: Long)
  suspend fun end(account: Account, team: Long)
  suspend fun setSharing(account: Account, team: Long, sharing: Boolean)
  suspend fun postPositions(account: Account, team: Long, positions: List<TeamPosition>)
  /** A [messageJson]; the message as stored (the first one stored, for a resend with the same key). */
  suspend fun postMessage(account: Account, team: Long, message: String): TeamMessage
  /** A 对话 photo up, telling [progress] how much went (0–1); its id. */
  suspend fun uploadImage(account: Account, team: Long, jpeg: ByteArray, progress: (Float) -> Unit): String
  suspend fun image(account: Account, team: Long, image: String, thumb: Boolean): ByteArray
  /** 队伍轨迹: the 发起人 gives a [teamTrackJson] or drops it; members fetch it ([parseTeamTrack]). */
  suspend fun putTrack(account: Account, team: Long, track: String)
  suspend fun deleteTrack(account: Account, team: Long)
  suspend fun track(account: Account, team: Long): String
  /** The team as it changes after [after]: [Live.Open], then changes; it ends when the server closes it (结束行程). */
  fun live(account: Account, team: Long, after: Long): Flow<Live>
}

/** The production [TeamTransport]: the generated client on [io], the live stream on its WebSocket. */
class HttpTeamTransport(private val api: BaseApi, private val io: CoroutineDispatcher = Dispatchers.IO) : TeamTransport {
  private suspend fun <T> call(block: suspend BaseApi.() -> T): T = withContext(io) { api.block() }

  override suspend fun create(account: Account) = call { postTeam(teamRequestDto = TeamRequestDto(JsonObject(emptyMap())), block = account.auth()).body().toTeam() }
  override suspend fun card(account: Account, code: String) = call { getTeamCard(code = code, block = account.auth()).body().toCard() }
  override suspend fun join(account: Account, code: String) = call { postTeamJoin(joinRequestDto = JoinRequestDto(code), block = account.auth()).body().toTeam() }
  override suspend fun team(account: Account, team: Long, after: Long) = call { getTeam(id = team, after = after, block = account.auth()).body().toTeam() }
  override suspend fun leave(account: Account, team: Long) {
    call { postTeamLeave(id = team, block = account.auth()) }
  }
  override suspend fun end(account: Account, team: Long) {
    call { postTeamEnd(id = team, block = account.auth()) }
  }
  override suspend fun setSharing(account: Account, team: Long, sharing: Boolean) {
    call { putTeamSharing(id = team, sharingDto = SharingDto(sharing), block = account.auth()) }
  }
  override suspend fun postPositions(account: Account, team: Long, positions: List<TeamPosition>) {
    call { postTeamPositions(id = team, positionsDto = positionsDto(positions), block = account.auth()) }
  }
  override suspend fun postMessage(account: Account, team: Long, message: String) =
    call { postTeamMessage(id = team, messageRequestDto = wire.decodeFromString(message), block = account.auth()).body().toMessage() }
  override suspend fun uploadImage(account: Account, team: Long, jpeg: ByteArray, progress: (Float) -> Unit) = call {
    postTeamImage(id = team, string = jpeg, bodyType = typeInfo<ByteArray>()) {
      bearerAuth(account.token)
      onUpload { sent, total -> progress(sent.toFloat() / (total ?: jpeg.size.toLong())) }
    }.body().image
  }
  override suspend fun image(account: Account, team: Long, image: String, thumb: Boolean) =
    call { prepareGetTeamImage(id = team, image = image, thumb = thumb, block = account.auth()).execute { it.bodyAsBytes() } }
  override suspend fun putTrack(account: Account, team: Long, track: String) {
    call { putTeamTrack(id = team, teamTrackRequestDto = wire.decodeFromString(track), block = account.auth()) }
  }
  override suspend fun deleteTrack(account: Account, team: Long) {
    call { deleteTeamTrack(id = team, block = account.auth()) }
  }
  // As sent, for [parseTeamTrack].
  override suspend fun track(account: Account, team: Long) = call { prepareGetTeamTrack(id = team, block = account.auth()).execute { it.bodyAsText() } }

  // Cancelling the collection closes the socket; what it says after that goes nowhere.
  override fun live(account: Account, team: Long, after: Long): Flow<Live> = channelFlow {
    var opened = false
    try {
      api.client.httpClient.webSocket({
        url.takeFrom(api.client.buildUrl("/teams/{id}/live", mapOf("id" to team.toString())))
        url.protocol = if (url.protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
        parameter("after", after)
        bearerAuth(account.token)
      }) {
        opened = true
        send(Live.Open)
        for (frame in incoming) (frame as? Frame.Text)?.let { runCatching { parseTeam(it.readText()) }.getOrNull() }?.let { send(Live.Change(it)) }
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      // A refused upgrade may come without its status or body (OkHttp's engine): the same question over REST says why
      // (not in the team any more, left on another phone, or logged out: nothing to come back to).
      if (!opened) team(account, team, after)
      throw OfflineError("offline")
    }
  }
}
