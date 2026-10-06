package com.starsdom.trail.team

// 队伍对话 (§8.4 第 10–16 条): mine on their way.

/** Where a message on its way stands (§8.4 第 14 条): going, waiting for the network, or ⚠ 没发出 until tapped. */
enum class SendState { Sending, Queued, Failed }

/**
 * After a failed send: no network ([online] false) queues it to go by itself once there is; anything else, a timeout
 * on a network that's up included, waits for a tap.
 */
fun sendStateAfter(code: String?, online: Boolean) = if (code == "offline" && !online) SendState.Queued else SendState.Failed

/**
 * A message on its way, shown at once (faded) at the end of team [team]'s 对话 until the server has it: [json] to
 * post, or [photo] (the shrunk JPEG's file) to upload first ([progress] of that upload); [kind] and [text] are what the
 * bubble shows. [id] is also its key: a resend is the same message to the server.
 */
data class Outgoing(
  val id: String, val team: Long, val kind: String, val text: String? = null, val json: String? = null, val photo: String? = null,
  val state: SendState = SendState.Sending, val progress: Float? = null,
)
