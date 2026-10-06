package com.starsdom.trail

// 队伍传输: the server's team routes (openapi.yaml), one method each, and the team's live stream. [TeamSession] reaches
// the network only through it; [HttpTeamTransport] wraps [Api] and its WebSocket.

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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

/** The production [TeamTransport]: blocking [Api] calls on [io], the live stream on OkHttp's WebSocket. */
class HttpTeamTransport(private val api: Api, private val io: CoroutineDispatcher = Dispatchers.IO) : TeamTransport {
  private suspend fun <T> call(block: Api.() -> T): T = withContext(io) { api.block() }

  override suspend fun create(account: Account) = call { createTeam(account) }
  override suspend fun card(account: Account, code: String) = call { teamCard(account, code) }
  override suspend fun join(account: Account, code: String) = call { joinTeam(account, code) }
  override suspend fun team(account: Account, team: Long, after: Long) = call { team(account, team, after) }
  override suspend fun leave(account: Account, team: Long) = call { leaveTeam(account, team) }
  override suspend fun end(account: Account, team: Long) = call { endTeam(account, team) }
  override suspend fun setSharing(account: Account, team: Long, sharing: Boolean) = call { setSharing(account, team, sharing) }
  override suspend fun postPositions(account: Account, team: Long, positions: List<TeamPosition>) = call { postPositions(account, team, positions) }
  override suspend fun postMessage(account: Account, team: Long, message: String) = call { postMessage(account, team, message) }
  override suspend fun uploadImage(account: Account, team: Long, jpeg: ByteArray, progress: (Float) -> Unit) = call { uploadImage(account, team, jpeg, progress) }
  override suspend fun image(account: Account, team: Long, image: String, thumb: Boolean) = call { image(account, team, image, thumb) }
  override suspend fun putTrack(account: Account, team: Long, track: String) = call { putTeamTrack(account, team, track) }
  override suspend fun deleteTrack(account: Account, team: Long) = call { deleteTeamTrack(account, team) }
  override suspend fun track(account: Account, team: Long) = call { teamTrack(account, team) }

  // Cancelling the collection closes the socket; what it says after that goes nowhere.
  override fun live(account: Account, team: Long, after: Long): Flow<Live> = callbackFlow {
    val socket = api.teamLive(account, team, after, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        trySend(Live.Open)
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        runCatching { parseTeam(text) }.getOrNull()?.let { trySend(Live.Change(it)) }
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        close()
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        // Not in the team any more (left on another phone), or logged out: nothing to come back to.
        close(OfflineError(when (response?.code) { 404 -> "team_not_found"; 401 -> "unauthorized"; else -> "offline" }))
      }
    })
    awaitClose { socket.cancel() }
  }
}
