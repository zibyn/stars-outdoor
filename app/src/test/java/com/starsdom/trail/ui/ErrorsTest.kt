package com.starsdom.trail.ui

import com.starsdom.trail.R
import org.junit.Assert.assertEquals
import org.junit.Test

// CS-07: the server answers a code; the phone says one of R9's reasons.
class ErrorsTest {
  @Test fun codesToReasons() {
    assertEquals(R.string.reason_offline, reasonOf("offline"))
    assertEquals(R.string.reason_server, reasonOf("timeout"))
    for (code in listOf("data_unavailable", "limit", "invalid_request", "something_new", null)) assertEquals(R.string.reason_server, reasonOf(code))
    assertEquals(R.string.reason_image, reasonOf("image_not_found"))
    assertEquals(R.string.reason_too_large, reasonOf("invalid_region"))
    assertEquals(R.string.reason_too_large, reasonOf("region_too_large"))
    assertEquals(R.string.reason_unsupported, reasonOf("region_unsupported"))
    assertEquals(R.string.reason_no_team, reasonOf("team_not_found"))
    assertEquals(R.string.reason_not_initiator, reasonOf("not_initiator"))
    assertEquals(R.string.reason_team_ended, reasonOf("team_ended"))
    assertEquals(R.string.reason_logged_out, reasonOf("unauthorized"))
    assertEquals(R.string.reason_phone, reasonOf("invalid_phone"))
    assertEquals(R.string.reason_code, reasonOf("wrong_code"))
    assertEquals(R.string.reason_sms_frequent, reasonOf("sms_too_frequent"))
    assertEquals(R.string.reason_sms_unavailable, reasonOf("sms_unavailable"))
    assertEquals(R.string.reason_rate_limited, reasonOf("rate_limited"))
  }
}
