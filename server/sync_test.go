package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"stars-outdoor/server/api"
)

// syncServer serves every account route from an emptied PostgreSQL at TEST_DATABASE_URL: sync and 注销账号
// are mostly SQL, so there is no in-memory stand-in and the tests skip without one.
func syncServer(t *testing.T) (http.Handler, *pgxpool.Pool, *cloud, *teams) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	db, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	if _, err := db.Exec(context.Background(), "DROP TABLE IF EXISTS public_tracks, sync_photos, sync_waypoints, sync_groups, sync_tracks, team_tracks, team_messages, team_images, team_positions, team_members, teams, sessions, users CASCADE; DROP SEQUENCE IF EXISTS sync_rev;"+usersSchema+teamsSchema+syncSchema+publicTracksSchema); err != nil {
		t.Fatal(err)
	}
	sms := &aliyunSMS{endpoint: (&fakeAliyun{}).serve(t).URL, keyID: "ID", secret: "S", signName: "x", template: "1", client: http.DefaultClient}
	dir := t.TempDir()
	tm := newTeams(pgTeams{db}, dir)
	cl := newCloud(db, dir, 1<<30)
	return withMiddleware(routes(1, okDB, nil, nil, nil, nil, newAccounts(sms, pgUsers{db}), tm, cl), 1), db, cl, tm
}

func pull(t *testing.T, h http.Handler, token string, after int64) api.Sync {
	t.Helper()
	w := do(h, "GET", "/v1/sync?after="+strconv.FormatInt(after, 10), token, "")
	var s api.Sync
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &s) != nil {
		t.Fatalf("pull: %d %s", w.Code, w.Body)
	}
	return s
}

func push(t *testing.T, h http.Handler, token, body string) {
	t.Helper()
	if w := do(h, "POST", "/v1/sync", token, body); w.Code != 204 {
		t.Fatalf("push %s: %d %s", body, w.Code, w.Body)
	}
}

const (
	trackID  = "0123456789abcdef0123456789abcdef"
	wptID    = "fedcba9876543210fedcba9876543210"
	groupID  = "00112233445566778899aabbccddeeff"
	newTrack = `{"id":"` + trackID + `","startedAt":1000,"endedAt":2000,"planned":false,"points":[{"t":1000,"lat":34,"lon":108,"ele":1200,"s":0},{"t":2000,"lat":34.001,"lon":108,"s":1}]}`
)

// Acceptance (#47): two phones rename the same track; both end up with the name the server got last.
func TestTwoPhonesRenameTrackLastWriteWins(t *testing.T) {
	h, _, _, _ := syncServer(t)
	a := login(t, h, "13800138000")
	b := login(t, h, "13800138000") // the same account on a second phone
	push(t, h, a, `{"tracks":[`+newTrack+`],"waypoints":[]}`)
	s := pull(t, h, b, 0)
	if len(s.Tracks) != 1 || len(s.Tracks[0].Points) != 2 || s.Tracks[0].Points[1].S != 1 || s.Tracks[0].Points[1].Ele != nil || s.Tracks[0].Datum != api.WGS84 {
		t.Fatalf("first pull: %+v", s)
	}
	// Per attribute: A's 纠偏 and B's name both stay; the later name wins.
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","name":"太白山","datum":"GCJ02"}],"waypoints":[]}`)
	push(t, h, b, `{"tracks":[{"id":"`+trackID+`","name":"鳌太线"}],"waypoints":[]}`)
	for _, tok := range []string{a, b} {
		got := pull(t, h, tok, s.Cursor)
		if len(got.Tracks) != 1 || got.Tracks[0].Name != "鳌太线" || got.Tracks[0].Datum != api.GCJ02 {
			t.Fatalf("after renames: %+v", got.Tracks)
		}
		if again := pull(t, h, tok, got.Cursor); len(again.Tracks) != 0 || again.More {
			t.Fatalf("nothing new: %+v", again)
		}
	}
	// Points never change, and another account sees nothing.
	push(t, h, b, `{"tracks":[{"id":"`+trackID+`","startedAt":5,"endedAt":6,"planned":true,"points":[]}],"waypoints":[]}`)
	if tr := pull(t, h, a, 0).Tracks[0]; len(tr.Points) != 2 || tr.StartedAt != 1000 || tr.Planned {
		t.Fatalf("points changed: %+v", tr)
	}
	if other := pull(t, h, login(t, h, "13900139000"), 0); len(other.Tracks) != 0 {
		t.Fatalf("other account: %+v", other)
	}
}

func TestDeletedIsATombstone(t *testing.T) {
	h, _, _, _ := syncServer(t)
	a := login(t, h, "13800138000")
	push(t, h, a, `{"tracks":[`+newTrack+`],"waypoints":[{"id":"`+wptID+`","track":"`+trackID+`","time":1500,"lat":34,"lon":108,"name":"垭口"}]}`)
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","deleted":true}],"waypoints":[{"id":"`+wptID+`","deleted":true}]}`)
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","name":"late"}],"waypoints":[{"id":"`+wptID+`","name":"late"}]}`)
	s := pull(t, h, a, 0)
	if len(s.Tracks) != 1 || !s.Tracks[0].Deleted || len(s.Tracks[0].Points) != 0 || s.Tracks[0].Name != "" {
		t.Fatalf("track: %+v", s.Tracks)
	}
	if len(s.Waypoints) != 1 || !s.Waypoints[0].Deleted || s.Waypoints[0].Name != "" {
		t.Fatalf("waypoint: %+v", s.Waypoints)
	}
}

// Acceptance (#121): a 标注组 made on A reaches B with its 标注; A's deleting it reaches B too.
func TestGroupsSyncWithTheirWaypoints(t *testing.T) {
	h, _, _, _ := syncServer(t)
	a := login(t, h, "13800138000")
	b := login(t, h, "13800138000")
	push(t, h, a, `{"tracks":[],"groups":[{"id":"`+groupID+`","name":"水源"}],"waypoints":[{"id":"`+wptID+`","group":"`+groupID+`","time":1,"lat":34,"lon":108}]}`)
	s := pull(t, h, b, 0)
	if len(s.Groups) != 1 || s.Groups[0].Name != "水源" || s.Groups[0].Deleted || len(s.Waypoints) != 1 || s.Waypoints[0].Group != groupID {
		t.Fatalf("first pull: %+v", s)
	}
	// Renamed, and the 标注 taken out of it, as attributes.
	push(t, h, b, `{"tracks":[],"groups":[{"id":"`+groupID+`","name":"水源 (1)"}],"waypoints":[{"id":"`+wptID+`","group":""}]}`)
	got := pull(t, h, a, s.Cursor)
	if len(got.Groups) != 1 || got.Groups[0].Name != "水源 (1)" || len(got.Waypoints) != 1 || got.Waypoints[0].Group != "" {
		t.Fatalf("after rename: %+v", got)
	}
	push(t, h, a, `{"tracks":[],"groups":[{"id":"`+groupID+`","deleted":true}],"waypoints":[{"id":"`+wptID+`","deleted":true}]}`)
	push(t, h, a, `{"tracks":[],"groups":[{"id":"`+groupID+`","name":"late"}],"waypoints":[]}`)
	got = pull(t, h, b, got.Cursor)
	if len(got.Groups) != 1 || !got.Groups[0].Deleted || got.Groups[0].Name != "" || len(got.Waypoints) != 1 || !got.Waypoints[0].Deleted {
		t.Fatalf("after delete: %+v", got)
	}
	if w := do(h, "POST", "/v1/sync", a, `{"tracks":[],"groups":[{"id":"`+wptID+`"}],"waypoints":[]}`); w.Code != 400 {
		t.Fatalf("new group without a name: %d", w.Code)
	}
}

func TestPushRejectsWhatItCantStore(t *testing.T) {
	h, _, _, _ := syncServer(t)
	a := login(t, h, "13800138000")
	for _, body := range []string{
		`{"tracks":[{"id":"` + trackID + `","name":"no points yet"}],"waypoints":[]}`,
		`{"tracks":[],"waypoints":[{"id":"` + wptID + `","name":"no place yet"}]}`,
		`{"tracks":[{"id":"../../etc","startedAt":1,"endedAt":2,"planned":false,"points":[]}],"waypoints":[]}`,
	} {
		if w := do(h, "POST", "/v1/sync", a, body); w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_request") {
			t.Fatalf("%s: %d %s", body, w.Code, w.Body)
		}
	}
	// All or nothing: the good track before the bad one isn't kept.
	do(h, "POST", "/v1/sync", a, `{"tracks":[`+newTrack+`,{"id":"`+wptID+`"}],"waypoints":[]}`)
	if s := pull(t, h, a, 0); len(s.Tracks) != 0 {
		t.Fatalf("kept: %+v", s)
	}
	if w := do(h, "POST", "/v1/sync", a, `{"tracks":[],"waypoints":[{"id":"`+wptID+`","time":1,"lat":34,"lon":108,"photo":"00000000000000000000000000000000"}]}`); w.Code != 400 || !strings.Contains(w.Body.String(), "image_not_found") {
		t.Fatalf("someone else's photo: %d %s", w.Code, w.Body)
	}
}

func TestPullPages(t *testing.T) {
	h, _, _, _ := syncServer(t)
	a := login(t, h, "13800138000")
	var ws []string
	for i := range syncPage + 5 {
		ws = append(ws, `{"id":"`+strings.Repeat("0", 29)+strconv.FormatInt(int64(100+i), 10)+`","time":1,"lat":34,"lon":108}`)
	}
	push(t, h, a, `{"tracks":[`+newTrack+`],"waypoints":[`+strings.Join(ws, ",")+`]}`)
	seen := map[string]bool{}
	var after int64
	for range 5 {
		s := pull(t, h, a, after)
		for _, w := range s.Waypoints {
			seen[w.Id] = true
		}
		for _, tr := range s.Tracks {
			seen[tr.Id] = true
		}
		if after = s.Cursor; !s.More {
			break
		}
	}
	if len(seen) != syncPage+6 {
		t.Fatalf("saw %d", len(seen))
	}
}

func TestPhotosQuotaAndReplace(t *testing.T) {
	h, _, cl, _ := syncServer(t)
	a := login(t, h, "13800138000")
	photo := testJPEG(t, 400, 300)
	cl.quota = int64(len(photo)) + 10
	upload := func() *api.Photo {
		w := do(h, "POST", "/v1/sync/photos", a, string(photo))
		if w.Code == 413 {
			if !strings.Contains(w.Body.String(), "photo_quota_exceeded") {
				t.Fatalf("413: %s", w.Body)
			}
			return nil
		}
		var p api.Photo
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &p) != nil {
			t.Fatalf("upload: %d %s", w.Code, w.Body)
		}
		return &p
	}
	p := upload()
	if p == nil || upload() != nil {
		t.Fatal("second photo should pass the quota")
	}
	push(t, h, a, `{"tracks":[],"waypoints":[{"id":"`+wptID+`","time":1,"lat":34,"lon":108,"photo":"`+p.Photo+`"}]}`)
	if w := do(h, "GET", "/v1/sync/photos/"+p.Photo, a, ""); w.Code != 200 || !bytes.Equal(w.Body.Bytes(), photo) {
		t.Fatalf("get: %d", w.Code)
	}
	if w := do(h, "GET", "/v1/sync/photos/"+p.Photo, login(t, h, "13900139000"), ""); w.Code != 404 {
		t.Fatalf("someone else's: %d", w.Code)
	}
	// Taking the photo off the 标注 frees its room.
	push(t, h, a, `{"tracks":[],"waypoints":[{"id":"`+wptID+`","photo":""}]}`)
	if w := do(h, "GET", "/v1/sync/photos/"+p.Photo, a, ""); w.Code != 404 {
		t.Fatalf("replaced: %d", w.Code)
	}
	if upload() == nil {
		t.Fatal("room again after the old photo went")
	}
}

// Acceptance (#47): after 注销账号 the server holds nothing of the user's.
func TestDeleteAccountLeavesNothing(t *testing.T) {
	h, db, cl, tm := syncServer(t)
	a, b := login(t, h, "13800138000"), login(t, h, "13900139000")
	w := do(h, "POST", "/v1/sync/photos", a, string(testJPEG(t, 400, 300)))
	var p api.Photo
	json.Unmarshal(w.Body.Bytes(), &p)
	push(t, h, a, `{"tracks":[`+newTrack+`],"groups":[{"id":"`+groupID+`","name":"g"}],"waypoints":[{"id":"`+wptID+`","time":1,"lat":34,"lon":108,"photo":"`+p.Photo+`"}]}`)
	team := teamOf(t, do(h, "POST", "/v1/teams", a, `{}`))
	do(h, "POST", "/v1/teams/join", b, `{"code":"`+team.Code+`"}`)
	do(h, "POST", path(team, "/positions"), a, `{"positions":[{"time":100,"lat":34,"lon":108}]}`)
	w = do(h, "POST", path(team, "/images"), a, string(testJPEG(t, 400, 300)))
	var img api.Image
	json.Unmarshal(w.Body.Bytes(), &img)
	do(h, "POST", path(team, "/messages"), a, `{"kind":"image","image":"`+img.Image+`"}`)
	do(h, "POST", path(team, "/messages"), a, `{"kind":"text","text":"我的电话 138…"}`)
	do(h, "POST", path(team, "/messages"), b, `{"kind":"text","text":"收到"}`)
	avatar := meOf(t, do(h, "PUT", "/v1/me/avatar", a, string(testJPEG(t, 256, 256)))).Avatar

	if w := do(h, "DELETE", "/v1/me", a, ""); w.Code != 204 {
		t.Fatalf("delete: %d %s", w.Code, w.Body)
	}
	if w := do(h, "GET", "/v1/me", a, ""); w.Code != 401 {
		t.Fatalf("token after delete: %d", w.Code)
	}
	for _, q := range []string{
		"SELECT count(*) FROM users WHERE phone = '13800138000'",
		"SELECT count(*) FROM sessions WHERE user_id = 1",
		"SELECT count(*) FROM sync_tracks WHERE user_id = 1",
		"SELECT count(*) FROM sync_waypoints WHERE user_id = 1",
		"SELECT count(*) FROM sync_groups WHERE user_id = 1",
		"SELECT count(*) FROM sync_photos WHERE user_id = 1",
		"SELECT count(*) FROM team_members WHERE user_id = 1",
		"SELECT count(*) FROM team_positions WHERE user_id = 1",
		"SELECT count(*) FROM team_images WHERE user_id = 1 OR user_id IS NULL",
		"SELECT count(*) FROM team_messages WHERE user_id = 1 OR text LIKE '%138%' OR image IS NOT NULL",
		"SELECT count(*) FROM teams WHERE initiator = 1",
	} {
		var n int
		if err := db.QueryRow(context.Background(), q).Scan(&n); err != nil || n != 0 {
			t.Errorf("%s: %d %v", q, n, err)
		}
	}
	for _, f := range []string{filepath.Join(cl.photos, p.Photo+".jpg"), tm.avatarPath(*avatar)} {
		if _, err := os.Stat(f); !os.IsNotExist(err) {
			t.Errorf("%s still there", f)
		}
	}
	orig, thumb := tm.imagePaths(img.Image)
	for _, f := range []string{orig, thumb} {
		if _, err := os.Stat(f); !os.IsNotExist(err) {
			t.Errorf("%s still there", f)
		}
	}
	// The teammate's 对话 keeps its shape: A's messages as 已注销用户, content gone; B's own (加入了 first) untouched.
	got := teamOf(t, do(h, "GET", path(team, ""), b, ""))
	if len(got.Messages) != 4 || got.Messages[1].Name != deletedUser || got.Messages[1].From != nil || *got.Messages[1].Text != "消息已删除" ||
		got.Messages[3].Text == nil || *got.Messages[3].Text != "收到" {
		t.Fatalf("messages: %+v", got.Messages)
	}
	if len(got.Members) != 1 || got.Ended {
		t.Fatalf("team: %+v", got)
	}
	// The number can sign up again, as a new account with nothing synced.
	if s := pull(t, h, login(t, h, "13800138000"), 0); len(s.Tracks)+len(s.Waypoints) != 0 {
		t.Fatalf("new account: %+v", s)
	}
}

// #89: a watch's FIT import keeps where it came from; set once with the points, like them it never changes.
func TestTrackSourceSyncs(t *testing.T) {
	h, _, _, _ := syncServer(t)
	a := login(t, h, "13800138000")
	b := login(t, h, "13800138000")
	push(t, h, a, `{"tracks":[`+strings.Replace(newTrack, `"planned"`, `"source":"来自 佳明 fēnix 7","planned"`, 1)+`],"waypoints":[]}`)
	push(t, h, a, `{"tracks":[{"id":"`+trackID+`","source":"别的"}],"waypoints":[]}`)
	if s := pull(t, h, b, 0); s.Tracks[0].Source != "来自 佳明 fēnix 7" {
		t.Fatalf("source: %+v", s.Tracks[0])
	}
}
