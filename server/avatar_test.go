package main

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"testing"

	"stars-trail/server/api"
)

func meOf(t *testing.T, w *httptest.ResponseRecorder) api.Me {
	t.Helper()
	var m api.Me
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &m) != nil {
		t.Fatalf("me: %d %s", w.Code, w.Body)
	}
	return m
}

// #185: a 头像 hangs on the account: any logged-in caller fetches it by id, a new one gets a new id and the
// old one goes, and it comes back on a new phone (login).
func TestAvatarUploadReplaceAndDrop(t *testing.T) {
	h := teamHandler(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	if me(t, h, a).Avatar != nil {
		t.Fatal("new account has an avatar")
	}
	pic := testJPEG(t, 256, 256)
	first := meOf(t, do(h, "PUT", "/v1/me/avatar", a, string(pic)))
	if first.Avatar == nil || !imageID.MatchString(*first.Avatar) {
		t.Fatalf("upload: %+v", first)
	}
	w := do(h, "GET", "/v1/avatars/"+*first.Avatar, b, "")
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), pic) || w.Header().Get("Cache-Control") == "" {
		t.Fatalf("get: %d %q", w.Code, w.Header().Get("Cache-Control"))
	}
	if m := me(t, h, login(t, h, "13800138000")); m.Avatar == nil || *m.Avatar != *first.Avatar {
		t.Fatalf("on a new phone: %+v", m)
	}
	second := meOf(t, do(h, "PUT", "/v1/me/avatar", a, string(testJPEG(t, 256, 256))))
	if second.Avatar == nil || *second.Avatar == *first.Avatar {
		t.Fatalf("replace: %+v", second)
	}
	if w := do(h, "GET", "/v1/avatars/"+*first.Avatar, b, ""); w.Code != 404 {
		t.Fatalf("old one: %d", w.Code)
	}
	if m := meOf(t, do(h, "DELETE", "/v1/me/avatar", a, "")); m.Avatar != nil {
		t.Fatalf("drop: %+v", m)
	}
	if w := do(h, "GET", "/v1/avatars/"+*second.Avatar, b, ""); w.Code != 404 {
		t.Fatalf("dropped one: %d", w.Code)
	}
	for _, body := range []string{"not a jpeg", string(testJPEG(t, 600, 256))} {
		if w := do(h, "PUT", "/v1/me/avatar", a, body); w.Code != 400 {
			t.Fatalf("bad body: %d", w.Code)
		}
	}
	for _, id := range []string{"..avatar", "0123456789abcdef0123456789abcdef"} {
		if w := do(h, "GET", "/v1/avatars/"+id, b, ""); w.Code != 404 {
			t.Fatalf("%s: %d", id, w.Code)
		}
	}
}

// #185: like a rename (#184), a new 头像 reaches the team's sockets at once.
func TestAvatarReachesTheTeamAtOnce(t *testing.T) {
	h := teamHandler(t)
	srv := httptest.NewServer(h)
	defer srv.Close()
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	tm := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+tm.Code+`"}`)
	c := dial(t, srv, path(tm, "/live"), b)
	if snap := next(t, c); member(snap, 1).Avatar != nil {
		t.Fatalf("before: %+v", snap)
	}
	m := meOf(t, do(h, "PUT", "/v1/me/avatar", a, string(testJPEG(t, 256, 256))))
	if ev := next(t, c); member(ev, 1).Avatar == nil || *member(ev, 1).Avatar != *m.Avatar {
		t.Fatalf("upload event: %+v", ev)
	}
	do(h, "DELETE", "/v1/me/avatar", a, "")
	if ev := next(t, c); member(ev, 1).Avatar != nil {
		t.Fatalf("drop event: %+v", ev)
	}
}
