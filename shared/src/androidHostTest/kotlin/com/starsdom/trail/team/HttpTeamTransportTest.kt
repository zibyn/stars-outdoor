package com.starsdom.trail.team

import com.starsdom.trail.OfflineError
import com.starsdom.trail.account.Account
import com.starsdom.trail.net.model.MemberDto
import com.starsdom.trail.net.model.MessageDto
import com.starsdom.trail.net.model.MessageKindDto
import com.starsdom.trail.net.model.PositionDto
import com.starsdom.trail.net.model.PositionsDto
import com.starsdom.trail.net.model.TeamDto
import com.starsdom.trail.net.model.TeamTrackRefDto
import com.starsdom.trail.net.option
import com.starsdom.trail.net.orNull
import com.starsdom.trail.net.trailClient
import com.starsdom.trail.net.wire
import com.starsdom.trail.track.toDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The HTTP 队伍传输 keeps the contract over the network: the memory transport behind the server's routes (openapi.yaml)
// and its WebSocket, a non-member refused before the upgrade as server/teams.go does.
class HttpTeamTransportTest : TeamTransportContract() {
  override fun serve(test: suspend Served.() -> Unit) = testApplication {
    val memory = MemoryTeamTransport()
    // The memory transport is single-threaded; the server's calls take turns.
    val one = Dispatchers.Default.limitedParallelism(1)
    fun ApplicationCall.account() = Account("", request.headers["Authorization"]!!.removePrefix("Bearer "))
    fun ApplicationCall.id() = parameters["id"]!!.toLong()
    suspend fun ApplicationCall.refuse(e: OfflineError) =
      respondText("""{"error":"${e.code}"}""", ContentType.Application.Json, if (e.code == "team_not_found") HttpStatusCode.NotFound else HttpStatusCode.BadRequest)
    suspend fun ApplicationCall.answer(block: suspend (Account) -> String?) = withContext(one) {
      try {
        respondText(block(account()) ?: "", ContentType.Application.Json)
      } catch (e: OfflineError) {
        refuse(e)
      }
    }
    install(WebSockets)
    routing {
      post("/v1/teams") { call.answer { wire.encodeToString(memory.create(it).toDto()) } }
      post("/v1/teams/join") { call.answer { wire.encodeToString(memory.join(it, wire.parseToJsonElement(call.receiveText()).jsonObject["code"]!!.jsonPrimitive.content).toDto()) } }
      get("/v1/teams/{id}") { call.answer { wire.encodeToString(memory.team(it, call.id(), call.parameters["after"]!!.toLong()).toDto()) } }
      post("/v1/teams/{id}/end") { call.answer { memory.end(it, call.id()); null } }
      post("/v1/teams/{id}/positions") {
        call.answer { memory.postPositions(it, call.id(), wire.decodeFromString<PositionsDto>(call.receiveText()).positions.map { p -> TeamPosition(p.time, p.lat, p.lon, p.battery.orNull()) }); null }
      }
      post("/v1/teams/{id}/messages") { call.answer { wire.encodeToString(memory.postMessage(it, call.id(), call.receiveText()).toDto()) } }
      route("/v1/teams/{id}/live") {
        install(createRouteScopedPlugin("Member") {
          onCall { call -> withContext(one) { runCatching { memory.team(call.account(), call.id(), 0) }.onFailure { call.refuse(it as OfflineError) } } }
        })
        webSocket {
          withContext(one) {
            memory.live(call.account(), call.id(), call.parameters["after"]!!.toLong()).collect { if (it is Live.Change) outgoing.send(Frame.Text(wire.encodeToString(it.team.toDto()))) }
          }
        }
      }
    }
    val api = trailClient("http://localhost", "device", 5, client.engine) {}
    coroutineScope { Served(HttpTeamTransport(api), memory.account(1), memory.account(2), this).test() }
  }
}

private fun TeamMessage.toDto() = MessageDto(
  seq, from.option(), name, timeS, MessageKindDto.fromSerial(kind), text.option(), lat.option(), lon.option(), image.option(), along.option(),
)

private fun Team.toDto() = TeamDto(
  id, me, code, initiator, ended, cursor,
  members.map { m -> MemberDto(m.id, m.name, m.avatar.option(), m.sharing, m.trail.map { PositionDto(it.timeS, it.lat, it.lon, it.battery.option()) }) },
  messages.map { it.toDto() },
  track?.let { TeamTrackRefDto(it.version, it.uuid, it.name, it.start.reversed, it.start.startM) }.option(),
)
