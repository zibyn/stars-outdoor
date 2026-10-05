package main

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"

	"stars-outdoor/server/api"
)

const twoPoints = `[{"t":0,"lat":34.0,"lon":108.0,"s":0},{"t":0,"lat":34.01,"lon":108.0,"ele":1200,"s":0}]`

func trackBody(name string, reversed bool, start float64) string {
	b, _ := json.Marshal(map[string]any{"uuid": "u1", "name": name, "reversed": reversed, "start": start, "points": json.RawMessage(twoPoints)})
	return string(b)
}

func TestTeamTrackGivenChangedDroppedAndGoneAtEnd(t *testing.T) {
	h, _ := teamServer(t)
	srv := httptest.NewServer(h)
	defer srv.Close()
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	if w := do(h, "GET", path(tm, "/track"), b, ""); w.Code != 404 || !strings.Contains(w.Body.String(), "no_team_track") {
		t.Fatalf("none yet: %d %s", w.Code, w.Body)
	}
	c := dial(t, srv, path(tm, "/live"), b)
	next(t, c)

	if w := do(h, "PUT", path(tm, "/track"), b, trackBody("武功山环线", false, 0)); w.Code != 403 {
		t.Fatalf("a member: %d", w.Code)
	}
	if w := do(h, "PUT", path(tm, "/track"), a, trackBody("武功山环线", false, 0)); w.Code != 204 {
		t.Fatalf("given: %d %s", w.Code, w.Body)
	}
	ev := next(t, c)
	if ev.Track == nil || ev.Track.Name != "武功山环线" || ev.Track.Uuid != "u1" || len(ev.Messages) != 1 ||
		ev.Messages[0].Kind != api.MessageKindSystem || *ev.Messages[0].Text != "发起人把队伍轨迹设为 武功山环线" {
		t.Fatalf("given event: %+v %+v", ev.Track, ev.Messages)
	}
	first := ev.Track.Version
	var tr api.TeamTrack
	if w := do(h, "GET", path(tm, "/track"), b, ""); w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &tr) != nil || len(tr.Points) != 2 || *tr.Points[1].Ele != 1200 || tr.Version != first {
		t.Fatalf("points: %d %s", w.Code, w.Body)
	}

	// 改起算点 is a change like 更换.
	do(h, "PUT", path(tm, "/track"), a, trackBody("武功山环线", true, 0))
	ev = next(t, c)
	if ev.Track.Version <= first || !ev.Track.Reversed || *ev.Messages[0].Text != "发起人把队伍轨迹换成 武功山环线（反向）" {
		t.Fatalf("reversed: %+v %s", ev.Track, *ev.Messages[0].Text)
	}
	do(h, "PUT", path(tm, "/track"), a, trackBody("武功山环线", true, 1200))
	if ev = next(t, c); *ev.Messages[0].Text != "发起人把队伍轨迹换成 武功山环线（反向、换了起点）" {
		t.Fatalf("start: %s", *ev.Messages[0].Text)
	}

	// The same again changes nothing: no version, no message.
	if w := do(h, "PUT", path(tm, "/track"), a, trackBody("武功山环线", true, 1200)); w.Code != 204 {
		t.Fatalf("same again: %d", w.Code)
	}
	do(h, "PUT", path(tm, "/track"), a, trackBody("武功山环线", false, 0))
	if ev = next(t, c); *ev.Messages[0].Text != "发起人把队伍轨迹换成 武功山环线（正向）" {
		t.Fatalf("back to 正向 (the same-again PUT sent nothing before it): %s", *ev.Messages[0].Text)
	}

	do(h, "DELETE", path(tm, "/track"), a, "")
	ev = next(t, c)
	if ev.Track != nil || *ev.Messages[0].Text != "发起人取消了队伍轨迹" {
		t.Fatalf("dropped: %+v %+v", ev.Track, ev.Messages)
	}
	// Nothing to drop: no message.
	if w := do(h, "DELETE", path(tm, "/track"), a, ""); w.Code != 204 || len(teamOf(t, do(h, "GET", path(tm, ""), a, "")).Messages) != 6 { // and 加入了
		t.Fatalf("again: %d", w.Code)
	}

	do(h, "PUT", path(tm, "/track"), a, trackBody("武功山环线", false, 0))
	do(h, "POST", path(tm, "/end"), a, "")
	if w := do(h, "GET", path(tm, "/track"), b, ""); w.Code != 404 {
		t.Fatalf("after end: %d", w.Code)
	}
	if got := teamOf(t, do(h, "GET", path(tm, ""), b, "")); got.Track != nil {
		t.Fatalf("after end: %+v", got.Track)
	}
	if w := do(h, "PUT", path(tm, "/track"), a, trackBody("x", false, 0)); w.Code != 409 {
		t.Fatalf("put after end: %d", w.Code)
	}
}

func TestTeamTrackChecked(t *testing.T) {
	h := teamHandler(t)
	a := login(t, h, "13800138000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	for _, body := range []string{
		trackBody("", false, 0),
		trackBody(strings.Repeat("名", 101), false, 0),
		trackBody("x", false, -1),
		`{"uuid":"u","name":"x","reversed":false,"start":0,"points":[{"t":0,"lat":34,"lon":108,"s":0}]}`,
		`{"uuid":"u","name":"x","reversed":false,"start":0,"points":[{"t":0,"lat":91,"lon":108,"s":0},{"t":0,"lat":34,"lon":108,"s":0}]}`,
	} {
		if w := do(h, "PUT", path(tm, "/track"), a, body); w.Code != 400 {
			t.Fatalf("%s: %d", body, w.Code)
		}
	}
	// Only the server writes system messages.
	if w := do(h, "POST", path(tm, "/messages"), a, `{"kind":"system","text":"发起人把队伍轨迹换成 假的"}`); w.Code != 400 {
		t.Fatalf("system from a member: %d", w.Code)
	}
}
